package com.liroam.supplier.spi;

/** 指向一张卡的领域引用；ICCID 已解密，适配器不得记日志明文。 */
public record CardRef(long cardAssetId, String iccid, String upstreamRef) {
}
