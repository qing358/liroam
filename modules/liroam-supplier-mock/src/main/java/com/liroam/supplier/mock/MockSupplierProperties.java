package com.liroam.supplier.mock;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.liroam.supplier.mock.MockFaults.Failure;
import com.liroam.supplier.mock.MockFaults.Operation;

/** 模拟供应商配置（前缀 {@code liroam.mock.supplier}）。 */
@ConfigurationProperties(MockSupplierProperties.PREFIX)
public class MockSupplierProperties {

    public static final String PREFIX = "liroam.mock.supplier";

    /** 形状 B 回调验签用的模拟密钥，仅 dev/test；不是任何真实上游的密钥。 */
    public static final String DEV_WEBHOOK_SECRET = "mock-shape-b-dev-webhook-secret";

    private String webhookSecret = DEV_WEBHOOK_SECRET;

    /** 总开关：false 时 {@link #rules} 全部不生效（{@link MockFaults#failNext} 仍可用）。 */
    private boolean rulesEnabled = true;

    /**
     * 静态故障规则。配置了 {@code rules[0]...} 即整体替换默认表。默认表只命中刻意挑选的输入，正常演示数据不会撞上：
     * <ul>
     *   <li>形状 A 开卡，ICCID 以 {@code 9991} 结尾：该卡首次开卡限流（Retryable，带 retryAfter），重试成功。</li>
     *   <li>形状 A 开卡，ICCID 以 {@code 9992} 结尾：该卡首次开卡写后超时（Unknown），上游实际已开通，查档为已生效。</li>
     *   <li>形状 B 开卡，套餐码 {@code 9GB-9D}：每次都被上游拒绝（Rejected），用于演示转人工。</li>
     * </ul>
     */
    private List<Rule> rules = defaultRules();

    public static List<Rule> defaultRules() {
        List<Rule> rules = new ArrayList<>();
        rules.add(Rule.iccidSuffix(MockShapeAAdapter.ADAPTER_KEY, Operation.PROVISION, "9991", Failure.THROTTLED, 1));
        rules.add(Rule.iccidSuffix(MockShapeAAdapter.ADAPTER_KEY, Operation.PROVISION, "9992", Failure.TIMEOUT_AFTER_SEND, 1));
        Rule reject = new Rule();
        reject.setAdapterKey(MockShapeBAdapter.ADAPTER_KEY);
        reject.setOperation(Operation.PROVISION);
        reject.setOfferingCode("9GB-9D");
        reject.setFailure(Failure.BAD_REQUEST);
        reject.setTimes(0);
        rules.add(reject);
        return rules;
    }

    public String getWebhookSecret() {
        return webhookSecret;
    }

    public void setWebhookSecret(String webhookSecret) {
        this.webhookSecret = webhookSecret;
    }

    public boolean isRulesEnabled() {
        return rulesEnabled;
    }

    public void setRulesEnabled(boolean rulesEnabled) {
        this.rulesEnabled = rulesEnabled;
    }

    public List<Rule> getRules() {
        return rules;
    }

    public void setRules(List<Rule> rules) {
        this.rules = rules;
    }

    /**
     * 一条规则：{@code adapterKey}、{@code failure} 必填；{@code iccidSuffix} 与 {@code offeringCode} 至少填一个，都填则同时满足。
     * {@code operation} 为空表示任一写操作（开卡、加包、停用、恢复、终止）。
     * {@code times}：同一 ICCID（按尾号匹配时）或同一 requestNo 最多命中几次，默认 1；≤0 表示每次都命中。
     */
    public static class Rule {
        private String adapterKey;
        private Operation operation;
        private String iccidSuffix;
        private String offeringCode;
        private Failure failure;
        private int times = 1;

        static Rule iccidSuffix(String adapterKey, Operation operation, String suffix, Failure failure, int times) {
            Rule rule = new Rule();
            rule.setAdapterKey(adapterKey);
            rule.setOperation(operation);
            rule.setIccidSuffix(suffix);
            rule.setFailure(failure);
            rule.setTimes(times);
            return rule;
        }

        public String getAdapterKey() {
            return adapterKey;
        }

        public void setAdapterKey(String adapterKey) {
            this.adapterKey = adapterKey;
        }

        public Operation getOperation() {
            return operation;
        }

        public void setOperation(Operation operation) {
            this.operation = operation;
        }

        public String getIccidSuffix() {
            return iccidSuffix;
        }

        public void setIccidSuffix(String iccidSuffix) {
            this.iccidSuffix = iccidSuffix;
        }

        public String getOfferingCode() {
            return offeringCode;
        }

        public void setOfferingCode(String offeringCode) {
            this.offeringCode = offeringCode;
        }

        public Failure getFailure() {
            return failure;
        }

        public void setFailure(Failure failure) {
            this.failure = failure;
        }

        public int getTimes() {
            return times;
        }

        public void setTimes(int times) {
            this.times = times;
        }

        @Override
        public String toString() {
            return "Rule[" + adapterKey + " " + (operation == null ? "任一写操作" : operation) + " iccidSuffix=" + iccidSuffix
                    + " offeringCode=" + offeringCode + " → " + failure + " times=" + times + "]";
        }
    }
}
