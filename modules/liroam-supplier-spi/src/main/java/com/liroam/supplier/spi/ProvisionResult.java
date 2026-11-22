package com.liroam.supplier.spi;

import java.util.Optional;

/** @param credential 上游在开通时一并返回交码内容（如 LPA）则带上 */
public record ProvisionResult(String iccid, String upstreamRef, Optional<CredentialResult> credential) {
}
