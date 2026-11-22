package com.liroam.supplier.spi;

/** API 交码结果；{@code payload} 入库前加密。 */
public record CredentialResult(Kind kind, String payload) {

    public enum Kind { LPA, QR, ACTIVATION_CODE }

    @Override
    public String toString() {
        return "CredentialResult[kind=" + kind + ", payload=***]";
    }
}
