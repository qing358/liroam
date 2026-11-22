package com.liroam.pricing.tariff;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 资费表解析结果：原样的行与元数据，不含任何匹配结论。 */
record RateSheet(LocalDate effectiveFrom, String currencyRaw, String notesRaw, List<Line> lines) {

    /** 卡资费行不分资源，整张表共用。 */
    static final String ALL_RESOURCES = "*";

    enum Kind {
        DATA("GB"), VOICE("MIN"), SMS("SMS"), CARD_FEE("CARD");

        final String unit;

        Kind(String unit) {
            this.unit = unit;
        }
    }

    record Line(String resourceCode, Kind kind, String continentRaw, String countryRaw, String carrierRaw, String directionRaw,
            String itemRaw, BigDecimal unitPrice, String remarkRaw) {
    }
}
