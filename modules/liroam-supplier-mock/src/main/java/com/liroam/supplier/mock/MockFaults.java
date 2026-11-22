package com.liroam.supplier.mock;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import com.liroam.common.safety.Simulated;
import com.liroam.supplier.mock.MockSupplierProperties.Rule;

/**
 * 模拟上游的脚本化故障，两家 mock 共用、按 adapterKey 区分：
 * 运行期一次性注入 {@link #failNext}，加上启动时配置的静态规则（{@link MockSupplierProperties#getRules()}）。
 * 一次性注入优先于规则。写后超时（{@link Failure#TIMEOUT_AFTER_SEND}）会先让写操作真的生效再报超时。
 */
@Simulated
public final class MockFaults {

    /** 被注入的上游调用；与指令类型同名，另加查档。 */
    public enum Operation {
        PROVISION, FETCH_CREDENTIAL, QUERY, ADD_PLAN, SUSPEND, RESUME, TERMINATE, LOOKUP;

        boolean isWrite() {
            return switch (this) {
                case PROVISION, ADD_PLAN, SUSPEND, RESUME, TERMINATE -> true;
                case FETCH_CREDENTIAL, QUERY, LOOKUP -> false;
            };
        }
    }

    /** 与契约套件 {@code UpstreamFixture.Failure} 同名同义；适配器归一为四态的口径见 domain-design §6.3。 */
    public enum Failure {
        /** 连接未建立，请求没发出 → Retryable。 */
        CONNECT_FAILED,
        /** 上游限流 → Retryable，带 retryAfter。 */
        THROTTLED,
        /** 上游 5xx → Retryable。 */
        SERVER_ERROR,
        /** 上游登记过的参数/状态错误 → Rejected。 */
        BAD_REQUEST,
        /** 写请求已发出、读超时；上游已执行 → Unknown。 */
        TIMEOUT_AFTER_SEND,
        /** 上游返回映射表里没有的码 → Unknown。 */
        UNREGISTERED_CODE
    }

    private record PendingKey(String adapterKey, Operation op) {
    }

    private final List<Rule> rules;
    private final Map<PendingKey, Queue<Failure>> pending = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> ruleHits = new ConcurrentHashMap<>();

    public MockFaults(List<Rule> rules) {
        for (Rule rule : rules) {
            if (rule.getAdapterKey() == null || rule.getFailure() == null) {
                throw new IllegalArgumentException("mock 故障规则须填 adapterKey 与 failure：" + rule);
            }
            if (rule.getIccidSuffix() == null && rule.getOfferingCode() == null) {
                throw new IllegalArgumentException("mock 故障规则须填 iccidSuffix 或 offeringCode，否则会命中全部调用：" + rule);
            }
        }
        this.rules = List.copyOf(rules);
    }

    /** 某家 mock 的下一次 {@code op} 调用以 {@code failure} 失败；可连续注入多次，按顺序消耗。 */
    public void failNext(String adapterKey, Operation op, Failure failure) {
        pending.computeIfAbsent(new PendingKey(adapterKey, op), k -> new ConcurrentLinkedQueue<>()).add(failure);
    }

    /** 清掉某家 mock 尚未消耗的注入与规则命中计数（规则本身保留）。 */
    public void clear(String adapterKey) {
        pending.keySet().removeIf(k -> k.adapterKey().equals(adapterKey));
        ruleHits.keySet().removeIf(k -> k.startsWith(adapterKey + "|"));
    }

    /** 本次调用是否失败；命中即消耗一次。 */
    Optional<Failure> take(String adapterKey, Operation op, String requestNo, String iccid, String offeringCode) {
        Queue<Failure> queue = pending.get(new PendingKey(adapterKey, op));
        Failure injected = queue == null ? null : queue.poll();
        if (injected != null) {
            return Optional.of(injected);
        }
        for (int i = 0; i < rules.size(); i++) {
            Rule rule = rules.get(i);
            if (!matches(rule, adapterKey, op, iccid, offeringCode)) {
                continue;
            }
            String subject = rule.getIccidSuffix() != null ? "iccid:" + iccid : "req:" + requestNo;
            int hits = ruleHits.computeIfAbsent(adapterKey + "|" + i + "|" + subject, k -> new AtomicInteger()).incrementAndGet();
            if (rule.getTimes() <= 0 || hits <= rule.getTimes()) {
                return Optional.of(rule.getFailure());
            }
        }
        return Optional.empty();
    }

    private static boolean matches(Rule rule, String adapterKey, Operation op, String iccid, String offeringCode) {
        if (!rule.getAdapterKey().equals(adapterKey)) {
            return false;
        }
        if (rule.getOperation() == null ? !op.isWrite() : rule.getOperation() != op) {
            return false;
        }
        if (rule.getIccidSuffix() != null && (iccid == null || !iccid.endsWith(rule.getIccidSuffix()))) {
            return false;
        }
        return rule.getOfferingCode() == null || rule.getOfferingCode().equals(offeringCode);
    }
}
