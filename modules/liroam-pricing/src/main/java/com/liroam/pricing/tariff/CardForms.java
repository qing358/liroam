package com.liroam.pricing.tariff;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import com.liroam.catalog.CardForm;

/**
 * 卡资费表「卡类型」原文 ↔ 卡形态。可售形态自动从有卡费报价的 eSIM / 实体卡导出；
 * 「纯软件卡 Profile」要买家自备软卡设备，按其卡费算成本，但是否开卖由运营按卡产品打开，重导资费时保留（新表不再报软卡卡费则关掉）。
 */
public final class CardForms {

    public static final String ESIM = CardForm.ESIM.name();
    public static final String PHYSICAL = CardForm.PHYSICAL.name();
    public static final String SOFT = CardForm.SOFT.name();
    private static final String DEFAULT_FORMS = ESIM + "," + PHYSICAL;

    private static final Map<String, String> ITEM_WORDS = Map.of("ESIM", ESIM, "实体卡", PHYSICAL, "纯软件卡", SOFT);

    /** 资费表备注 / 脚注里「本区域不支持软卡」的写法：「不支持纯软卡文件」「不支持软配置文件」「NO Soft-Profile」。 */
    private static final Pattern SOFT_UNSUPPORTED = Pattern.compile("不支持\\s*纯?软|(?i:no\\s*soft)");

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

    public static boolean pricesSoft(Collection<String> cardFeeItems) {
        return cardFeeItems.stream().map(CardForms::formOf).anyMatch(f -> f.filter(SOFT::equals).isPresent());
    }

    public static boolean allows(String allowedForms, String form) {
        return allowedForms != null && Arrays.stream(allowedForms.split(",")).map(f -> f.trim().toUpperCase(Locale.ROOT)).anyMatch(form::equals);
    }

    /** @param regionNote {@code lr_card_product_region.restriction_note} */
    public static boolean softUnsupported(String regionNote) {
        return regionNote != null && SOFT_UNSUPPORTED.matcher(regionNote).find();
    }

    /**
     * 表里没有 eSIM / 实体卡卡费时按 eSIM + 实体卡。
     *
     * @param keepSoft 运营已为该卡产品打开软卡；本表仍报软卡卡费才保留
     */
    static String fromCardFees(Collection<String> items, boolean keepSoft) {
        Set<String> forms = new LinkedHashSet<>();
        for (String form : new String[] {ESIM, PHYSICAL}) {
            if (items.stream().map(CardForms::formOf).anyMatch(f -> f.filter(form::equals).isPresent())) {
                forms.add(form);
            }
        }
        String base = forms.isEmpty() ? DEFAULT_FORMS : String.join(",", forms);
        return keepSoft && pricesSoft(items) ? base + "," + SOFT : base;
    }
}
