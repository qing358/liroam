package cn.lili.modules.promotion.entity.enums;

import cn.hutool.core.text.CharSequenceUtil;

/**
 * 满额、满件及单品立减活动子类型枚举
 *
 * @author Bulbasaur
 * @since 2026-11-09
 */
public enum FullDiscountTypeEnum {

    FULL_MINUS("满减活动", "满减"),
    FULL_RATE("满折活动", "满折"),
    FULL_GIFT("满赠活动", "满赠"),
    FULL_COUNT_MINUS("多件优惠", "多件优惠"),
    SINGLE_REDUCTION("单品立减", "单品立减");

    private final String description;
    private final String tag;

    FullDiscountTypeEnum(String description, String tag) {
        this.description = description;
        this.tag = tag;
    }

    /**
     * 获取活动子类型的业务名称。
     *
     * @return 活动子类型名称
     */
    public String description() {
        return description;
    }

    /**
     * 获取活动子类型的展示标签。
     *
     * @return 活动展示标签
     */
    public String tag() {
        return tag;
    }

    /**
     * 校验活动子类型编码是否为已定义的枚举值。
     *
     * @param code 活动子类型编码
     * @return 编码是否有效，空编码返回false
     */
    public static boolean isValid(String code) {
        if (CharSequenceUtil.isBlank(code)) {
            return false;
        }
        for (FullDiscountTypeEnum item : values()) {
            if (item.name().equals(code)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 按活动子类型编码查找枚举。
     *
     * @param code 活动子类型编码
     * @return 对应枚举，空编码或未知编码返回null
     */
    public static FullDiscountTypeEnum fromCode(String code) {
        if (CharSequenceUtil.isBlank(code)) {
            return null;
        }
        for (FullDiscountTypeEnum item : values()) {
            if (item.name().equals(code)) {
                return item;
            }
        }
        return null;
    }
}
