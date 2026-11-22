package com.liroam.pricing.price;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.LongConsumer;
import java.util.stream.Collectors;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

import com.liroam.catalog.SkuBinding;
import com.liroam.catalog.SkuBindings;
import com.liroam.common.audit.AuditLog;
import com.liroam.common.setting.Settings;
import com.liroam.common.web.UserFacingException;
import com.liroam.pricing.Json;
import com.liroam.pricing.price.BatchAdjust.Mode;

/**
 * 批量调价（P6-D）。建议的批量确认 / 驳回逐条各自一个事务，部分失败照常返回；
 * 批量改价先预览再执行，执行时在同一事务里按同一口径重算，任一 SKU 低于最低毛利率整批不改。
 * 执行出的售价版本 origin 为 MANUAL（运营定价），审计动作 {@code PRICE_BATCH_ACTIVATE}；这些 SKU 的待确认建议置 SUPERSEDED。
 */
public class BatchRepricing {

    private static final Log log = LogFactory.getLog(BatchRepricing.class);
    static final int MAX_SKUS = 200;
    private static final BigDecimal MAX_PERCENT = BigDecimal.TEN;

    private final TransactionOperations tx;
    private final SkuBindings bindings;
    private final CostVersions costVersions;
    private final PriceVersions prices;
    private final AuditLog audit;
    private final PricingRules rules;

    public BatchRepricing(JdbcClient jdbc, TransactionOperations tx, Settings settings, SkuBindings bindings, CostVersions costVersions,
            PriceVersions prices, AuditLog audit) {
        this.tx = tx;
        this.bindings = bindings;
        this.costVersions = costVersions;
        this.prices = prices;
        this.audit = audit;
        this.rules = new PricingRules(jdbc, settings);
    }

    public BatchDecision confirm(List<Long> suggestionIds, String actor) {
        return decide(suggestionIds, id -> prices.confirmSuggestion(id, actor));
    }

    public BatchDecision reject(List<Long> suggestionIds, String actor) {
        return decide(suggestionIds, id -> prices.rejectSuggestion(id, actor));
    }

    private BatchDecision decide(List<Long> ids, LongConsumer action) {
        List<Long> done = new ArrayList<>();
        List<BatchDecision.Failure> failed = new ArrayList<>();
        for (long id : distinct(ids, "条调价建议")) {
            try {
                action.accept(id);
                done.add(id);
            } catch (UserFacingException e) {
                failed.add(new BatchDecision.Failure(id, e.getMessage()));
            } catch (RuntimeException e) {
                log.error("调价建议 #" + id + " 批量处理失败", e);
                failed.add(new BatchDecision.Failure(id, "处理失败，请稍后单独重试"));
            }
        }
        return new BatchDecision(done, failed);
    }

    private record Planned(SkuBinding sku, long costVersionId, BatchPreview.Row row) {
    }

    public BatchPreview preview(BatchAdjust request) {
        return view(plan(request));
    }

    public BatchPreview apply(BatchAdjust request, String actor) {
        return tx.execute(status -> {
            List<Planned> planned = plan(request);
            List<String> below = planned.stream().filter(p -> p.row().belowMin()).map(p -> p.row().liGoodsSkuId()).toList();
            if (!below.isEmpty()) {
                throw new UserFacingException(below.size() + " 个 SKU 改价后低于最低毛利率，整批未执行：" + String.join("、", below));
            }
            for (Planned p : planned) {
                prices.supersedePendingSuggestions(p.sku().id());
                prices.activate(p.sku(), p.row().newPriceFen(), p.costVersionId(), "MANUAL", null, actor);
                Map<String, Object> after = new LinkedHashMap<>();
                after.put("priceFen", p.row().newPriceFen());
                after.put("mode", request.mode());
                after.put("value", request.value());
                audit.record(actor, "PRICE_BATCH_ACTIVATE", "SKU_BINDING", String.valueOf(p.sku().id()),
                        Json.write(Map.of("priceFen", p.row().currentPriceFen())), Json.write(after));
            }
            return view(planned);
        });
    }

    private List<Planned> plan(BatchAdjust request) {
        if (request == null || request.mode() == null || request.value() == null) {
            throw new UserFacingException("请选择改价方式并填写数值");
        }
        validate(request.mode(), request.value());
        List<Planned> planned = new ArrayList<>();
        for (long id : distinct(request.skuBindingIds(), "个 SKU")) {
            SkuBinding sku = bindings.findById(id).orElseThrow(() -> new UserFacingException("SKU 绑定 #" + id + " 不存在"));
            ActivePrice price = prices.active(id)
                    .orElseThrow(() -> new UserFacingException("SKU " + sku.shellSkuId() + " 还没有售价，请先单独定价"));
            CostVersion cost = costVersions.latest(id)
                    .orElseThrow(() -> new UserFacingException("SKU " + sku.shellSkuId() + " 还没有成本（缺资费或汇率），不能批量改价"));
            long next = switch (request.mode()) {
                case PERCENT -> BigDecimal.valueOf(price.priceFen()).multiply(BigDecimal.ONE.add(request.value()))
                        .setScale(0, RoundingMode.HALF_UP).longValueExact();
                case TARGET_MARGIN -> PricingRule.divideByOneMinus(cost.costCnyFen(), request.value(), RoundingMode.CEILING);
            };
            if (next <= 0) {
                throw new UserFacingException("SKU " + sku.shellSkuId() + " 改价后售价不足 1 分");
            }
            BigDecimal margin = Repricing.margin(next, cost.costCnyFen());
            BigDecimal minMargin = rules.minMargin(sku);
            planned.add(new Planned(sku, cost.id(), new BatchPreview.Row(id, sku.shellSkuId(), price.priceFen(), cost.costCnyFen(), next, margin,
                    minMargin, margin.compareTo(minMargin) < 0)));
        }
        return planned;
    }

    private static void validate(Mode mode, BigDecimal value) {
        boolean ok = switch (mode) {
            case PERCENT -> value.signum() != 0 && value.compareTo(BigDecimal.ONE.negate()) > 0 && value.compareTo(MAX_PERCENT) <= 0;
            case TARGET_MARGIN -> value.signum() >= 0 && value.compareTo(BigDecimal.ONE) < 0;
        };
        if (!ok) {
            throw new UserFacingException(mode == Mode.PERCENT ? "涨跌幅须大于 -100%、不超过 +1000% 且不为 0，如 0.05 表示涨 5%"
                    : "目标毛利率须在 0（含）与 1 之间，如 0.3 表示 30%");
        }
    }

    private static List<Long> distinct(List<Long> ids, String unit) {
        if (ids == null || ids.isEmpty()) {
            throw new UserFacingException("请至少选一" + unit);
        }
        List<Long> unique = ids.stream().collect(Collectors.toCollection(LinkedHashSet::new)).stream().toList();
        if (unique.size() > MAX_SKUS) {
            throw new UserFacingException("一次最多处理 " + MAX_SKUS + unit);
        }
        return unique;
    }

    private static BatchPreview view(List<Planned> planned) {
        List<BatchPreview.Row> rows = planned.stream().map(Planned::row).toList();
        return new BatchPreview(rows, (int) rows.stream().filter(BatchPreview.Row::belowMin).count());
    }
}
