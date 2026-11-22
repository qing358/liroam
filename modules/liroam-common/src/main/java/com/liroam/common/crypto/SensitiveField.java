package com.liroam.common.crypto;

/** 需要加密存储的字段种类；名字作为 GCM 附加数据与哈希域分隔，密文不能跨字段挪用。 */
public enum SensitiveField {
    ICCID,
    CREDENTIAL,
    PHONE,
    BUYER_CONTACT
}
