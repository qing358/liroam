package cn.lili.modules.promotion.serviceimpl;

import com.alibaba.fastjson2.JSON;
import cn.lili.common.enums.PromotionTypeEnum;
import cn.lili.common.exception.ServiceException;
import cn.lili.modules.goods.entity.dos.GoodsSku;
import cn.lili.modules.goods.support.GoodsSkuReadSupport;
import cn.lili.modules.order.cart.entity.dto.TradeDTO;
import cn.lili.modules.order.cart.entity.vo.CartSkuVO;
import cn.lili.modules.order.cart.entity.vo.CartVO;
import cn.lili.modules.order.cart.entity.vo.FullDiscountVO;
import cn.lili.modules.order.cart.render.impl.FullDiscountRender;
import cn.lili.modules.promotion.entity.dto.FullDiscountRuleDTO;
import cn.lili.modules.promotion.entity.dos.FullDiscount;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.ArgumentMatchers.any;

/**
 * 满件减钱的阶梯校验、混买计数、金额封顶、尾差与历史金额活动兼容测试。
 *
 * @author Bulbasaur
 * @since 2026-11-14
 */
class FullCountMinusTest {

    /**
     * 验证件数与金额分别校验，减免金额可以大于件数，并持久化件数规则。
     */
    @Test
    void validatesAndPersistsCountRules() {
        FullDiscountVO activity = activity(tier(2, 10), tier(3, 20));
        validate(activity);
        assertThat(activity.getFullMoney()).isZero();
        assertThat(activity.getFullMinus()).isEqualTo(10);
        assertThat(activity.getTitle()).contains("满2件减10元", "满3件减20元");
        FullDiscountVO restored = new FullDiscountVO(JSON.parseObject(JSON.toJSONString(activity), FullDiscountVO.class));
        assertThat(restored.notice()).contains("满2件减10元", "满3件减20元");
    }

    /**
     * 验证非整数、非法值、缺失件数、越界及非递增门槛被服务端拒绝。
     */
    @Test
    void rejectsInvalidCountThresholds() {
        for (Double count : new Double[]{null, 0D, 1D, 2.5D, 1000000D, Double.NaN, Double.POSITIVE_INFINITY}) {
            FullDiscountRuleDTO rule = tier(2, 10);
            rule.setFullNum(count);
            assertThrows(ServiceException.class, () -> validate(activity(rule)));
        }
        assertThrows(ServiceException.class, () -> validate(activity(tier(3, 10), tier(2, 20))));
        assertThrows(ServiceException.class, () -> validate(activity(tier(2, 10), tier(2, 20))));
    }

    /**
     * 验证现金精度、金额边界及优惠力度必须递增。
     */
    @Test
    void rejectsInvalidDiscounts() {
        for (double amount : new double[]{0, -1, 1.001, 100000000, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(ServiceException.class, () -> validate(activity(tier(2, amount))));
        }
        assertThrows(ServiceException.class, () -> validate(activity(tier(2, 20), tier(3, 10))));
    }

    /**
     * 验证禁止伪造跨店SKU、空范围及非支持的适用范围。
     */
    @Test
    void rejectsForeignSkuAndInvalidScope() {
        FullDiscountServiceImpl service = new FullDiscountServiceImpl();
        GoodsSkuReadSupport reads = mock(GoodsSkuReadSupport.class);
        ReflectionTestUtils.setField(service, "skuReads", reads);
        FullDiscountVO activity = activity(tier(2, 10));
        activity.setScopeType("PORTION_GOODS");
        assertThrows(ServiceException.class, () -> service.checkPromotions(activity));
        activity.setScopeId("foreign");
        GoodsSku foreign = new GoodsSku();
        foreign.setStoreId("another-store");
        when(reads.getGoodsSkuByIdFromCache("foreign")).thenReturn(foreign);
        assertThrows(ServiceException.class, () -> service.checkPromotions(activity));
        activity.setScopeType("PORTION_GOODS_CATEGORY");
        assertThrows(ServiceException.class, () -> service.checkPromotions(activity));
    }

    /**
     * 验证正常指定商品活动通过共享校验，关联信息以服务端SKU为准。
     */
    @Test
    void validatesScopeAndRebuildsSkuMetadata() {
        FullDiscountServiceImpl service = spy(new FullDiscountServiceImpl());
        GoodsSkuReadSupport reads = mock(GoodsSkuReadSupport.class);
        ReflectionTestUtils.setField(service, "skuReads", reads);
        doReturn(List.of()).when(service).list(any(Wrapper.class));
        GoodsSku goods = new GoodsSku();
        goods.setId("a");
        goods.setStoreId("store");
        goods.setGoodsName("服务端商品名称");
        when(reads.getGoodsSkuByIdFromCache("a")).thenReturn(goods);
        FullDiscountVO activity = activity(tier(2, 10));
        activity.setScopeType("PORTION_GOODS");
        activity.setScopeId("a");
        service.checkPromotions(activity);
        assertThat(activity.getPromotionGoodsList()).hasSize(1);
        assertThat(activity.getPromotionGoodsList().get(0).getSkuId()).isEqualTo("a");
        assertThat(activity.getPromotionGoodsList().get(0).getGoodsName()).isEqualTo("服务端商品名称");
    }

    /**
     * 验证历史附属权益不能通过多件优惠接口发放。
     */
    @Test
    void clearsExtraRights() {
        FullDiscountVO activity = activity(tier(2, 10));
        activity.setPointFlag(true);
        activity.setPoint(100);
        activity.setCouponFlag(true);
        activity.setGiftFlag(true);
        activity.setFreeFreightFlag(true);
        validate(activity);
        assertThat(activity.getPointFlag()).isFalse();
        assertThat(activity.getCouponFlag()).isFalse();
        assertThat(activity.getGiftFlag()).isFalse();
        assertThat(activity.getFreeFreightFlag()).isFalse();
    }

    /**
     * 验证同款多件、跨SKU混买累计，命中最高档且不重复循环减免。
     */
    @Test
    void countsMixedSkusAndUsesHighestTierOnce() {
        FullDiscountVO activity = activity(tier(2, 10), tier(3, 20));
        CartSkuVO a = sku("a", 2, 200, activity);
        CartSkuVO b = sku("b", 4, 400, activity);
        CartVO cart = render(a, b);
        assertThat(discount(a, b)).isEqualTo(20);
        assertThat(cart.getPromotionNotice()).contains("满3件减20元");
        assertThat(a.getPriceDetailDTO().getDiscountPriceDetail().get(0).getPromotionRule()).isEqualTo("满3件减20元");
        assertThat(a.getPriceDetailDTO().getDiscountPriceDetail().get(0).getPromotionName()).isEqualTo("多件优惠");
    }

    /**
     * 验证未选中、非活动SKU不凑件数，提示使用整数件数。
     */
    @Test
    void excludesUncheckedAndOutOfScopeSkus() {
        FullDiscountVO activity = activity(tier(3, 20));
        activity.setScopeType("PORTION_GOODS");
        activity.setScopeId("a,b");
        CartSkuVO a = sku("a", 2, 200, activity);
        CartSkuVO b = sku("b", 10, 1000, activity);
        b.setChecked(false);
        CartSkuVO c = sku("c", 10, 1000, null);
        CartVO cart = render(a, b, c);
        assertThat(discount(a, b, c)).isZero();
        assertThat(cart.getPromotionNotice()).contains("还差1件");
    }

    /**
     * 验证高价单件不以金额触发件数门槛，并展示下一档差额。
     */
    @Test
    void usesQuantityInsteadOfMoneyAndPromptsNextTier() {
        FullDiscountVO activity = activity(tier(2, 10), tier(3, 20));
        CartSkuVO a = sku("a", 1, 1000, activity);
        assertThat(render(a).getPromotionNotice()).contains("还差1件");
        assertThat(discount(a)).isZero();
        a.setNum(2);
        assertThat(render(a).getPromotionNotice()).contains("再买1件可享满3件减20元");
        assertThat(discount(a)).isEqualTo(10);
    }

    /**
     * 验证优惠封顶于商品可优惠金额，不抵扣会员补贴，且无超额或负数分摊。
     */
    @Test
    void capsDiscountAndPreservesMemberSubsidy() {
        FullDiscountVO activity = activity(tier(2, 100));
        CartSkuVO a = sku("a", 1, 10, activity);
        CartSkuVO b = sku("b", 1, 20, activity);
        a.getPriceDetailDTO().setPaidMemberSubsidy(5D);
        a.getPriceDetailDTO().setDiscountPrice(5D);
        render(a, b);
        assertThat(a.getPriceDetailDTO().getDiscountPrice()).isEqualTo(10);
        assertThat(a.getPriceDetailDTO().getDiscountPriceDetail().get(0).getDiscountPrice()).isEqualTo(5);
        assertThat(b.getPriceDetailDTO().getDiscountPrice()).isEqualTo(20);
        assertThat(discount(a, b)).isEqualTo(30);
    }

    /**
     * 验证分摊到分，尾差守恒，零价商品不会造成除零。
     */
    @Test
    void conservesCentsAndHandlesZeroPrice() {
        FullDiscountVO activity = activity(tier(3, 0.01));
        CartSkuVO a = sku("a", 1, 1, activity);
        CartSkuVO b = sku("b", 1, 1, activity);
        CartSkuVO c = sku("c", 1, 1, activity);
        render(a, b, c);
        assertThat(discount(a, b, c)).isEqualTo(0.01);
        CartSkuVO free = sku("free", 3, 0, activity);
        render(free);
        assertThat(discount(free)).isZero();
    }

    /**
     * 验证历史满金额减钱仍按商品金额触发，不按购买件数循环。
     */
    @Test
    void preservesAmountBasedPromotion() {
        FullDiscountRuleDTO rule = new FullDiscountRuleDTO();
        rule.setFullMoney(100D);
        rule.setFullMinus(20D);
        FullDiscountVO activity = activity(rule);
        activity.setActivityType("FULL_MINUS");
        CartSkuVO a = sku("a", 2, 200, activity);
        render(a);
        assertThat(discount(a)).isEqualTo(20);
    }

    /**
     * 验证满件活动与原有满额活动在相同时段对相同SKU互斥。
     */
    @Test
    void conflictsWithAmountPromotionOnSameSku() {
        FullDiscountServiceImpl service = spy(new FullDiscountServiceImpl());
        FullDiscountVO existing = activity(tier(2, 10));
        existing.setActivityType("FULL_MINUS");
        existing.setScopeType("PORTION_GOODS");
        existing.setScopeId("a");
        doReturn(List.<FullDiscount>of(existing)).when(service).list(any(Wrapper.class));
        FullDiscountVO current = activity(tier(2, 10));
        current.setScopeType("PORTION_GOODS");
        current.setScopeId("a");
        assertThrows(ServiceException.class, () -> service.checkSameScopeConflict(current));
        current.setScopeId("b");
        service.checkSameScopeConflict(current);
    }

    /**
     * 验证最高档前的三级件数边界及同款购买数量能够触发活动。
     */
    @Test
    void matchesThreeTierBoundaries() {
        FullDiscountVO activity = activity(tier(2, 10), tier(3, 20), tier(5, 40));
        for (int count = 1; count <= 10; count++) {
            CartSkuVO item = sku("a", count, count * 100, activity);
            render(item);
            assertThat(discount(item)).isEqualTo(count >= 5 ? 40 : count >= 3 ? 20 : count >= 2 ? 10 : 0);
        }
    }

    /**
     * 验证新规则不会改变已有满金额折扣的计算和文案单位。
     */
    @Test
    void preservesAmountRatePromotion() {
        FullDiscountRuleDTO rule = FullDiscountRuleDTO.builder().fullMoney(100D).fullRate(8D).build();
        FullDiscountVO activity = activity(rule);
        activity.setActivityType("FULL_RATE");
        CartSkuVO item = sku("a", 2, 200, activity);
        CartVO cart = render(item);
        assertThat(discount(item)).isEqualTo(40);
        assertThat(cart.getPromotionNotice()).contains("满100元打8折");
    }

    /**
     * 直接执行共享阶梯校验，避免依赖数据库及促销敏感词服务。
     */
    private void validate(FullDiscountVO activity) {
        ReflectionTestUtils.invokeMethod(new FullDiscountServiceImpl(), "checkFullDiscount", activity);
    }

    /**
     * 构造件数阶梯，金额单位为元。
     */
    private FullDiscountRuleDTO tier(double count, double amount) {
        return FullDiscountRuleDTO.builder().fullNum(count).fullMinus(amount).build();
    }

    /**
     * 构造在有效时间内的店铺活动，使用JSON规则覆盖实际缓存反序列化路径。
     */
    private FullDiscountVO activity(FullDiscountRuleDTO... rules) {
        FullDiscountVO activity = new FullDiscountVO();
        activity.setId("count-activity");
        activity.setStoreId("store");
        activity.setScopeType("ALL");
        activity.setActivityType("FULL_COUNT_MINUS");
        activity.setStartTime(new Date(System.currentTimeMillis() - 60000));
        activity.setEndTime(new Date(System.currentTimeMillis() + 60000));
        activity.setRuleList(new ArrayList<>(List.of(rules)));
        activity.setRulesJson(JSON.toJSONString(rules));
        return activity;
    }

    /**
     * 构造已勾选商品，total是该SKU购买数量对应的总金额，单位元。
     */
    private CartSkuVO sku(String id, int count, double total, FullDiscountVO activity) {
        GoodsSku goods = new GoodsSku();
        goods.setId(id);
        goods.setGoodsId("goods-" + id);
        goods.setPrice(total / count);
        CartSkuVO sku = new CartSkuVO(goods);
        sku.setNum(count);
        sku.getPriceDetailDTO().setGoodsPrice(total);
        if (activity != null) {
            sku.setPromotionMap(Map.of(PromotionTypeEnum.FULL_DISCOUNT.name() + "-" + activity.getId(), activity));
        }
        return sku;
    }

    /**
     * 执行真实购物车活动渲染，不连接数据库。
     */
    private CartVO render(CartSkuVO... skus) {
        CartVO cart = new CartVO();
        cart.setSkuList(List.of(skus));
        TradeDTO trade = new TradeDTO();
        trade.setCartList(List.of(cart));
        trade.setSkuList(List.of(skus));
        new FullDiscountRender().render(trade);
        return cart;
    }

    /**
     * 汇总商品优惠金额，用于核对分摊守恒。
     */
    private double discount(CartSkuVO... skus) {
        return java.util.Arrays.stream(skus).mapToDouble(s -> s.getPriceDetailDTO().getDiscountPrice()).sum();
    }
}
