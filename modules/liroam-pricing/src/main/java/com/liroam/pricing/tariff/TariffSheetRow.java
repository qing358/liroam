package com.liroam.pricing.tariff;

/** 后台资费表列表行。 */
public record TariffSheetRow(long id, long supplierId, String fileName, String status, String effectiveFromText, String importedBy,
        String createdText) {
}
