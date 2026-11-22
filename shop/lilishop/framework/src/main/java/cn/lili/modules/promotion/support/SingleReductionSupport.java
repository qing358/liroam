package cn.lili.modules.promotion.support;

import com.alibaba.fastjson2.JSON;
import cn.lili.common.enums.PromotionTypeEnum;
import cn.lili.common.utils.CurrencyUtil;
import cn.lili.modules.goods.entity.dos.GoodsSku;
import cn.lili.modules.promotion.entity.dos.FullDiscount;
import cn.lili.modules.order.order.entity.dto.PriceDetailDTO;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

/**
 * 单品立减的实时价格解析，供商品列表、详情和结算共享，避免使用报名时价格覆盖当前商品价格。
 * 每件减固定金额；活动失效、范围不符或当前售价不足减免时不生效，不产生零价或负价。
 *
 * @author Bulbasaur
 * @since 2026-11-14
 */
public final class SingleReductionSupport {

    /**
     * 工具类不允许实例化。
     */
    private SingleReductionSupport() {
    }

    /**
     * 从当前映射选择有效立减活动；异常旧缓存与不属于当前店铺或SKU的记录不参与计价。
     *
     * @param sku 当前服务端SKU及售价（元）
     * @param promotions 活动映射
     * @return 有效立减活动，没有匹配时为null
     */
    public static FullDiscount findActivity(GoodsSku sku, Map<String, Object> promotions) {
        if (sku == null || sku.getPrice() == null || promotions == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        FullDiscount best = null;
        for (Map.Entry<String, Object> entry : promotions.entrySet()) {
            if (!entry.getKey().startsWith(PromotionTypeEnum.SINGLE_REDUCTION.name() + "-")) {
                continue;
            }
            FullDiscount activity;
            try {
                activity = entry.getValue() instanceof FullDiscount value ? value
                        : JSON.parseObject(entry.getValue() instanceof String value ? value : JSON.toJSONString(entry.getValue()), FullDiscount.class);
            } catch (RuntimeException ignored) {
                continue;
            }
            if (activity == null || !"SINGLE_REDUCTION".equals(activity.getActivityType())
                    || !"PORTION_GOODS".equals(activity.getScopeType())
                    || !Objects.equals(sku.getStoreId(), activity.getStoreId())
                    || Boolean.TRUE.equals(activity.getDeleteFlag())
                    || activity.getStartTime() == null || activity.getEndTime() == null
                    || activity.getStartTime().getTime() > now || activity.getEndTime().getTime() < now
                    || activity.getScopeId() == null
                    || Arrays.stream(activity.getScopeId().split(",")).map(String::trim).noneMatch(sku.getId()::equals)) {
                continue;
            }
            Double amount = activity.getFullMinus();
            if (amount == null || !Double.isFinite(amount) || amount <= 0 || amount >= sku.getPrice()
                    || BigDecimal.valueOf(amount).stripTrailingZeros().scale() > 2) {
                continue;
            }
            if (best == null || amount > best.getFullMinus()) {
                best = activity;
            }
        }
        return best;
    }

    /**
     * 按当前SKU售价计算单件活动价，活动结束或售价变化后即时重新判断。
     *
     * @param sku 当前SKU
     * @param promotions 活动映射
     * @return 单件立减价（元），无有效活动为null
     */
    public static Double price(GoodsSku sku, Map<String, Object> promotions) {
        FullDiscount activity = findActivity(sku, promotions);
        return activity == null ? null : CurrencyUtil.sub(sku.getPrice(), activity.getFullMinus());
    }

    /**
     * 判断本次商品价格中是否已经实际计入单品立减，供后续门槛和优惠分摊使用。
     *
     * @param price 当前商品价格明细
     * @return 是否存在实际单品立减明细
     */
    public static boolean hasDiscount(PriceDetailDTO price) {
        return price != null && price.getDiscountPriceDetail() != null
                && price.getDiscountPriceDetail().stream()
                .anyMatch(item -> item.getPromotionTypeEnum() == PromotionTypeEnum.SINGLE_REDUCTION);
    }
}
