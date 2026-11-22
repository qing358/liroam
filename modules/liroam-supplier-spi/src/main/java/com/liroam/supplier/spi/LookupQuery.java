package com.liroam.supplier.spi;

/**
 * 查档：判定某次写操作是否已在上游生效。
 *
 * @param operation 被查的写操作
 * @param card      池模式开通时为分配的卡；下单即建且尚未拿到卡号时 iccid/upstreamRef 为空，按 requestNo 查
 */
public record LookupQuery(String requestNo, WriteOperation operation, CardRef card) {

    public enum WriteOperation { PROVISION, ADD_PLAN, SUSPEND, RESUME, TERMINATE }
}
