package com.liroam.supplier.spi;

/** @param upstreamRef 已生效时的上游引用（订阅号、套餐号等），未生效为空 */
public record LookupResult(Effect effect, String upstreamRef) {

    public enum Effect { EFFECTIVE, NOT_EFFECTIVE }
}
