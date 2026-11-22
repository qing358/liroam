package com.liroam.fulfillment.worker;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;

import com.liroam.catalog.SkuBinding;
import com.liroam.catalog.SkuBindings;
import com.liroam.catalog.Supplier;
import com.liroam.catalog.SupplierAdapterRegistry;
import com.liroam.catalog.SupplierDirectory;
import com.liroam.common.setting.SettingScope;
import com.liroam.common.setting.Settings;
import com.liroam.fulfillment.FulfillmentRequest.SourceType;
import com.liroam.fulfillment.FulfillmentSettings;
import com.liroam.fulfillment.credential.CredentialTimeouts;
import com.liroam.fulfillment.delivery.ShellDeliveries;
import com.liroam.fulfillment.state.CommandPriority;
import com.liroam.fulfillment.state.CommandType;
import com.liroam.fulfillment.state.FulfillmentLineStatus;
import com.liroam.fulfillment.store.CardAssets;
import com.liroam.fulfillment.store.CardAssets.NewCard;
import com.liroam.fulfillment.store.CardPlans;
import com.liroam.fulfillment.store.FulfillmentLines;
import com.liroam.fulfillment.store.FulfillmentLines.Line;
import com.liroam.fulfillment.store.SupplierCommands;
import com.liroam.fulfillment.task.ManualTasks;
import com.liroam.supplier.spi.SupplierCapabilities.InventoryMode;

/**
 * 拆卡 worker（domain-design §7.1–7.2）：认领 RECEIVED 履约行，新卡按数量建 N 张卡资产（{@code unit_seq} 1..N）+ 各自基础套餐 + 首条指令，
 * 加包在原卡上建 1 条套餐 + ADD_PLAN 指令；行推进到 IN_PROGRESS。每行一个事务，行锁 + 状态 CAS 保证重入不重复建卡。
 * <p>
 * 同一轮顺带：开通后超时未交码转待办（{@link CredentialTimeouts}）、整单完成后回写壳发货（{@link ShellDeliveries}，事务外调端口）。
 */
public class FulfillmentWorker {

    private static final Logger log = LoggerFactory.getLogger(FulfillmentWorker.class);

    /** 拆卡失败（如 SKU 绑定被删）超过这么多次转人工，不再自动重试。 */
    static final int MAX_EXPAND_ATTEMPTS = 5;

    private final TransactionOperations tx;
    private final FulfillmentLines lines;
    private final CardAssets cards;
    private final CardPlans plans;
    private final SupplierCommands commands;
    private final SkuBindings skus;
    private final SupplierDirectory suppliers;
    private final SupplierAdapterRegistry adapters;
    private final Settings settings;
    private final ManualTasks tasks;
    private final CredentialTimeouts credentialTimeouts;
    private final ShellDeliveries shellDeliveries;
    private final Clock clock;

    public FulfillmentWorker(TransactionOperations tx, FulfillmentLines lines, CardAssets cards, CardPlans plans, SupplierCommands commands,
            SkuBindings skus, SupplierDirectory suppliers, SupplierAdapterRegistry adapters, Settings settings, ManualTasks tasks,
            CredentialTimeouts credentialTimeouts, ShellDeliveries shellDeliveries, Clock clock) {
        this.tx = tx;
        this.lines = lines;
        this.cards = cards;
        this.plans = plans;
        this.commands = commands;
        this.skus = skus;
        this.suppliers = suppliers;
        this.adapters = adapters;
        this.settings = settings;
        this.tasks = tasks;
        this.credentialTimeouts = credentialTimeouts;
        this.shellDeliveries = shellDeliveries;
        this.clock = clock;
    }

    /** @return 本轮拆开的履约行数 */
    public int runOnce(int batch) {
        int expanded = 0;
        for (int i = 0; i < batch; i++) {
            Optional<Long> done = expandNext();
            if (done == null) {
                continue;
            }
            if (done.isEmpty()) {
                break;
            }
            expanded++;
        }
        credentialTimeouts.runOnce(batch);
        shellDeliveries.runOnce(batch);
        return expanded;
    }

    /** @return 拆开的行 id；empty = 没有待拆的行；null = 本行失败（已记录），继续下一行 */
    private Optional<Long> expandNext() {
        Long[] failed = new Long[1];
        try {
            return tx.execute(status -> {
                Optional<Line> claimed = lines.claimReceived(MAX_EXPAND_ATTEMPTS);
                claimed.ifPresent(line -> {
                    failed[0] = line.id();
                    expand(line);
                });
                return claimed.map(Line::id);
            });
        } catch (RuntimeException e) {
            if (failed[0] == null) {
                throw e;
            }
            long lineId = failed[0];
            log.error("履约行[{}]拆卡失败", lineId, e);
            tx.executeWithoutResult(status -> {
                lines.failedAttempt(lineId, e.toString());
                Line line = lines.get(lineId).orElseThrow();
                if (line.attempts() >= MAX_EXPAND_ATTEMPTS) {
                    tasks.open(ManualTasks.Kind.EXPAND_FAILED, "FULFILLMENT_LINE", lineId, e.toString(), clock.instant());
                }
            });
            return null;
        }
    }

    private void expand(Line line) {
        Instant now = clock.instant();
        SkuBinding sku = skus.findById(line.skuBindingId())
                .orElseThrow(() -> new IllegalStateException("SKU 绑定 " + line.skuBindingId() + " 不存在"));
        Supplier supplier = suppliers.find(sku.supplierId())
                .orElseThrow(() -> new IllegalStateException("供应商 " + sku.supplierId() + " 不存在"));
        int maxAttempts = settings.get(FulfillmentSettings.COMMAND_MAX_ATTEMPTS, SettingScope.supplier(supplier.id()));
        if (line.sourceType() == SourceType.TOPUP) {
            long cardId = line.topupCardAssetId();
            long planId = plans.insert(cardId, line.id(), CardPlans.Kind.TOP_UP, sku.planTemplateId(), sku.offeringId());
            commands.enqueue(CommandType.ADD_PLAN, supplier.id(), cardId, planId, CommandPriority.WRITE, maxAttempts, now);
        } else {
            CommandType first = adapters.require(supplier).capabilities().inventoryMode() == InventoryMode.POOL_REQUIRED
                    ? CommandType.ALLOCATE : CommandType.PROVISION;
            String kyc = "NONE".equals(lines.kycGateMode(line.id())) ? "NOT_REQUIRED" : "PENDING";
            for (int unit = 1; unit <= line.qty(); unit++) {
                long cardId = cards.insert(new NewCard(line.id(), unit, supplier.id(), sku.cardProductId(), sku.regionId(), sku.regionGroupId(),
                        sku.form(), line.ownerMemberId(), kyc));
                long planId = plans.insert(cardId, line.id(), CardPlans.Kind.BASE, sku.planTemplateId(), sku.offeringId());
                commands.enqueue(first, supplier.id(), cardId, planId, CommandPriority.WRITE, maxAttempts, now);
            }
        }
        lines.move(line.id(), FulfillmentLineStatus.RECEIVED, FulfillmentLineStatus.EXPANDED);
        lines.move(line.id(), FulfillmentLineStatus.EXPANDED, FulfillmentLineStatus.IN_PROGRESS);
    }
}
