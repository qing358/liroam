package com.liroam.pricing.tariff;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 卡资费表「卡类型」原文 ↔ 卡形态。可售形态只从有卡费报价的 eSIM / 实体卡导出；
 * 「纯软件卡 Profile」要买家自备软卡设备，是否开卖由运营另定，不自动放开。
 */
public final class CardForms {

    public static final String ESIM = "ESIM";
    public static final String PHYSICAL = "PHYSICAL";
    private static final String DEFAULT_FORMS = ESIM + "," + PHYSICAL;

    private static final Map<String, String> ITEM_WORDS = Map.of("ESIM", ESIM, "实体卡", PHYSICAL);

    private CardForms() {
    }

    /** 卡费行对应的形态；号码月租等非卡费行为空。 */
    public static Optional<String> formOf(String itemRaw) {
        if (itemRaw == null) {
            return Optional.empty();
        }
        String text = itemRaw.toUpperCase(Locale.ROOT).replace(" ", "");
        return ITEM_WORDS.entrySet().stream().filter(e -> text.startsWith(e.getKey())).map(Map.Entry::getValue).findFirst();
    }

    /** 表里没有卡资费页时按 eSIM + 实体卡。 */
    static String fromCardFees(Collection<String> items) {
        Set<String> forms = new LinkedHashSet<>();
        for (String form : new String[] {ESIM, PHYSICAL}) {
            if (items.stream().map(CardForms::formOf).anyMatch(f -> f.filter(form::equals).isPresent())) {
                forms.add(form);
            }
        }
        return forms.isEmpty() ? DEFAULT_FORMS : String.join(",", forms);
    }
}
