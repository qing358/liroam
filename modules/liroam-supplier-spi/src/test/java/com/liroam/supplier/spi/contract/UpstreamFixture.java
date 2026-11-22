package com.liroam.supplier.spi.contract;

import java.time.Instant;
import java.util.Map;

import com.liroam.supplier.spi.CardRef;

/**
 * 契约套件操纵「上游」的钩子。真适配器用 WireMock 回放上游样例实现；契约桩用内存实现。
 */
public interface UpstreamFixture {

    /** 上游在下一次调用时的表现。 */
    enum Failure {
        /** 连接未建立（请求没发出去）。 */
        CONNECT_FAILED,
        /** 429 / 上游限流码。 */
        THROTTLED,
        /** 5xx。 */
        SERVER_ERROR,
        /** 参数或状态错误（上游已登记的业务错误码）。 */
        BAD_REQUEST,
        /** 写请求已发出后读超时。 */
        TIMEOUT_AFTER_SEND,
        /** 返回了映射表里没有登记的上游码。 */
        UNREGISTERED_CODE
    }

    record ExpectedUsage(long remainingBytes, Instant expiresAt, String expiresAtRaw) {
    }

    record SignedCallback(Map<String, String> headers, byte[] body) {
    }

    /** 每个用例开始前清空上游状态。 */
    void reset();

    /** 池模式：上游侧预置一张 Idle 卡并返回其 ICCID。 */
    String idleIccid();

    /** 下一次上游调用以指定方式失败。 */
    void failNext(Failure failure);

    /** 上游视角下某 requestNo 实际产生的开通次数（WireMock 回放时按请求携带的幂等键去重计数）。 */
    int effectiveProvisions(String requestNo);

    /** 上游侧把卡推到「已激活」（用户已装卡上网）；激活与否不影响状态操作的上游可不实现。 */
    default void activate(CardRef card) {
    }

    /** 给卡植入一段用量，返回适配器应归一出的结果。 */
    ExpectedUsage seedUsage(CardRef card);

    String baseOfferingCode();

    String topUpOfferingCode();

    /** 声明 webhook 时：签名正确的回调。 */
    SignedCallback validCallback(CardRef card);

    /** 声明 webhook 时：签名被篡改的回调。 */
    SignedCallback tamperedCallback(CardRef card);
}
