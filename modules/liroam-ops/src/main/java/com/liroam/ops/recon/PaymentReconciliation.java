package com.liroam.ops.recon;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

import com.liroam.common.setting.Settings;
import com.liroam.ops.recon.ReconRuns.DiffKind;
import com.liroam.ops.recon.ReconRuns.NewDiff;
import com.liroam.ops.recon.ReconRuns.Run;
import com.liroam.ops.recon.StatementCsv.Row;

/**
 * 下游支付日对账：运营导入支付机构当日账单（CSV：order_sn, trade_no, amount 元），与壳当日已支付订单按订单号比对。
 * 只比 {@link ReconSettings#PAYMENT_METHODS} 覆盖的支付方式；差异 = 我方有账单无（MISSING_THEIRS）、账单有我方无（MISSING_OURS）、金额不符。
 */
public class PaymentReconciliation {

    public static final String KIND = "PAYMENT_DAILY";

    private final TransactionOperations tx;
    private final JdbcClient jdbc;
    private final ShellPayments shell;
    private final ReconRuns runs;
    private final Settings settings;

    public PaymentReconciliation(TransactionOperations tx, JdbcClient jdbc, ShellPayments shell, ReconRuns runs, Settings settings) {
        this.tx = tx;
        this.jdbc = jdbc;
        this.shell = shell;
        this.runs = runs;
        this.settings = settings;
    }

    public Run run(LocalDate day, String csv, String actor) {
        List<Row> rows = StatementCsv.parse(csv, "order_sn", "trade_no", "amount");
        Map<String, Long> theirs = new LinkedHashMap<>();
        Map<String, String> tradeNos = new LinkedHashMap<>();
        for (Row r : rows) {
            String sn = r.get("order_sn");
            if (sn.isEmpty() || sn.length() > 64) {
                throw StatementCsv.bad(r, "订单号为空或过长");
            }
            theirs.merge(sn, StatementCsv.minor(r, "amount"), Long::sum);
            tradeNos.put(sn, r.get("trade_no"));
        }
        Set<String> methods = Arrays.stream(settings.get(ReconSettings.PAYMENT_METHODS).split(",")).map(String::trim).filter(m -> !m.isEmpty())
                .collect(Collectors.toSet());
        Map<String, Long> ours = new LinkedHashMap<>();
        for (ShellPayments.PaidOrder o : shell.paidOrders(day)) {
            if (methods.contains(o.paymentMethod())) {
                ours.merge(o.orderSn(), o.amountFen(), Long::sum);
            }
        }
        List<NewDiff> diffs = new ArrayList<>();
        int matched = 0;
        for (Map.Entry<String, Long> o : ours.entrySet()) {
            Long their = theirs.get(o.getKey());
            if (their == null) {
                diffs.add(new NewDiff(DiffKind.MISSING_THEIRS, o.getKey(), null, o.getValue(), null));
            } else if (their.longValue() != o.getValue()) {
                diffs.add(new NewDiff(DiffKind.AMOUNT, o.getKey(), tradeNos.get(o.getKey()), o.getValue(), their));
            } else {
                matched++;
            }
        }
        theirs.forEach((sn, amount) -> {
            if (!ours.containsKey(sn)) {
                diffs.add(new NewDiff(DiffKind.MISSING_OURS, null, sn, null, amount));
            }
        });
        int matchedCount = matched;
        long runId = tx.execute(s -> {
            long id = runs.record(KIND, day.toString(), null, matchedCount, diffs, "CNY", actor);
            for (Row r : rows) {
                jdbc.sql("INSERT INTO lr_payment_statement_line (run_id, order_sn, trade_no, amount_fen) VALUES (?, ?, ?, ?)")
                        .params(id, r.get("order_sn"), r.get("trade_no").isEmpty() ? null : r.get("trade_no"), StatementCsv.minor(r, "amount")).update();
            }
            return id;
        });
        return runs.run(runId);
    }
}
