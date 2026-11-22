package cn.lili.modules.promotion.serviceimpl;

import cn.lili.common.enums.PromotionTypeEnum;
import cn.lili.common.exception.ServiceException;
import cn.lili.modules.goods.entity.dos.GoodsSku;
import cn.lili.modules.goods.support.GoodsSkuReadSupport;
import cn.lili.modules.order.cart.entity.dto.TradeDTO;
import cn.lili.modules.order.cart.entity.enums.CartTypeEnum;
import cn.lili.modules.order.cart.entity.vo.CartSkuVO;
import cn.lili.modules.order.cart.entity.vo.CartVO;
import cn.lili.modules.order.cart.entity.vo.FullDiscountVO;
import cn.lili.modules.order.cart.render.impl.FullDiscountRender;
import cn.lili.modules.order.cart.render.impl.SkuPromotionRender;
import cn.lili.modules.order.cart.render.impl.CouponRender;
import cn.lili.modules.order.cart.render.impl.CheckDataRender;
import cn.lili.modules.goods.service.GoodsSkuService;
import cn.lili.modules.search.entity.dos.GoodsIndex;
import cn.lili.modules.search.service.GoodsIndexService;
import cn.lili.modules.order.cart.render.util.PromotionPriceUtil;
import cn.lili.modules.promotion.entity.dos.FullDiscount;
import cn.lili.modules.promotion.entity.dos.MemberCoupon;
import cn.lili.modules.promotion.entity.dos.PromotionGoods;
import cn.lili.modules.promotion.entity.dos.Seckill;
import cn.lili.modules.promotion.entity.dto.FullDiscountRuleDTO;
import cn.lili.modules.promotion.entity.dto.KanjiaActivityGoodsDTO;
import cn.lili.modules.promotion.entity.dto.KanjiaActivityGoodsOperationDTO;
import cn.lili.modules.promotion.entity.vos.PintuanVO;
import cn.lili.modules.promotion.entity.vos.SeckillApplyVO;
import cn.lili.modules.goods.entity.enums.GoodsStatusEnum;
import cn.lili.modules.promotion.support.SeckillMetadataSupport;
import cn.lili.modules.promotion.mapper.FullDiscountMapper;
import cn.lili.modules.promotion.mapper.PromotionGoodsMapper;
import cn.lili.modules.promotion.service.PromotionConflictService;
import cn.lili.modules.promotion.service.PromotionGoodsService;
import cn.lili.modules.promotion.support.SingleReductionSupport;
import cn.lili.modules.promotion.support.SkuPromotionQuerySupport;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 单品立减的服务端金额及归属校验、活动冲突、实时计价、叠加分摊和历史玩法隔离测试。
 *
 * @author Bulbasaur
 * @since 2026-11-14
 */
class SingleReductionTest {

    /**
     * 验证每件减额独立保存，不能夹带门槛、阶梯、赠品和其他权益。
     */
    @Test
    void validatesOneRuleAndPersistsPerItemReduction() {
        FullDiscountVO activity = activity(20);
        activity.setCouponFlag(true);
        activity.setGiftFlag(true);
        activity.setPointFlag(true);
        activity.setFreeFreightFlag(true);
        service(100).checkPromotions(activity);
        assertThat(activity.getTitle()).isEqualTo("每件立减20元");
        assertThat(activity.getFullMinus()).isEqualTo(20);
        assertThat(activity.getFullMoney()).isZero();
        assertThat(activity.getCouponFlag()).isFalse();
        assertThat(activity.getGiftFlag()).isFalse();
        assertThat(activity.getPointFlag()).isFalse();
        assertThat(activity.getFreeFreightFlag()).isFalse();
        assertThat(new FullDiscountVO(activity).notice()).contains("每件立减20元");
    }

    /**
     * 验证精度、非有限值、非正金额及达到商品售价的减额被服务端拒绝。
     */
    @Test
    void rejectsInvalidAmountsAndFreeGoods() {
        for (double amount : new double[]{0, -1, 0.001, 100, 101, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(ServiceException.class, () -> service(100).checkPromotions(activity(amount)));
        }
    }

    /**
     * 验证只能配置一档且必须指定SKU，不能按全店无条件让利。
     */
    @Test
    void rejectsTiersAndAllStoreScope() {
        FullDiscountVO activity = activity(20);
        activity.setScopeType("ALL");
        assertThrows(ServiceException.class, () -> service(100).checkPromotions(activity));
        activity.setScopeType("PORTION_GOODS");
        activity.setRuleList(List.of(rule(20), rule(30)));
        assertThrows(ServiceException.class, () -> service(100).checkPromotions(activity));
    }

    /**
     * 验证篡改SKU归属被拒绝，服务端商品名称覆盖客户端元数据。
     */
    @Test
    void validatesSkuOwnership() {
        FullDiscountVO activity = activity(20);
        activity.setScopeId("foreign");
        assertThrows(ServiceException.class, () -> service(100).checkPromotions(activity));
        activity.setScopeId("sku");
        service(100).checkPromotions(activity);
        assertThat(activity.getPromotionGoodsList().get(0).getGoodsName()).isEqualTo("真实商品");
    }

    /**
     * 验证新建单品立减执行统一的秒杀、拼团、砍价冲突校验。
     */
    @Test
    void checksExclusiveSkuActivities() {
        FullDiscountServiceImpl service = service(100);
        PromotionConflictService conflicts = mock(PromotionConflictService.class);
        ReflectionTestUtils.setField(service, "promotionConflictService", conflicts);
        doThrow(new ServiceException("已存在秒杀活动")).when(conflicts)
                .checkSkuExclusiveConflict(any(), anyString(), any(), any(), any(), any());
        assertThrows(ServiceException.class, () -> service.checkPromotions(activity(20)));
        assertThat(List.of(PromotionTypeEnum.exclusiveSkuPromotion)).contains(PromotionTypeEnum.SINGLE_REDUCTION);
    }

    /**
     * 验证从其他单品活动报名时，也会反向检查单品立减的时间重叠。
     */
    @Test
    void rejectsSeckillWhenSingleReductionAlreadyExists() {
        PromotionGoodsService bindings = mock(PromotionGoodsService.class);
        when(bindings.findInnerOverlapPromotionGoods(eq("SINGLE_REDUCTION"), any(), any(), any(), any())).thenReturn(1);
        PromotionConflictServiceImpl conflicts = new PromotionConflictServiceImpl();
        ReflectionTestUtils.setField(conflicts, "promotionGoodsService", bindings);
        assertThrows(ServiceException.class, () -> conflicts.checkSkuExclusiveConflict(PromotionTypeEnum.SECKILL,
                "sku", new Date(), new Date(), null, "商品"));
    }

    /**
     * 验证单品级与满件/满额活动可同时存在，两个单品立减仍按SKU互斥。
     */
    @Test
    void allowsStorePromotionsButRejectsAnotherSingleReduction() {
        FullDiscountServiceImpl service = service(100);
        FullDiscountVO existing = activity(10);
        existing.setActivityType("FULL_COUNT_MINUS");
        doReturn(List.<FullDiscount>of(existing)).when(service).list(any(Wrapper.class));
        service.checkPromotions(activity(20));
        existing.setActivityType("SINGLE_REDUCTION");
        assertThrows(ServiceException.class, () -> service.checkPromotions(activity(20)));
    }

    /**
     * 验证立减价以当前售价计算，活动创建后的改价不会使用历史绑定价。
     */
    @Test
    void derivesPriceFromCurrentSkuAndIgnoresInvalidDiscount() {
        FullDiscountVO activity = activity(20);
        GoodsSku goods = goods(100);
        assertThat(SingleReductionSupport.price(goods, promotions(activity))).isEqualTo(80);
        goods.setPrice(120D);
        assertThat(SingleReductionSupport.price(goods, promotions(activity))).isEqualTo(100);
        goods.setPrice(10D);
        assertThat(SingleReductionSupport.price(goods, promotions(activity))).isNull();
    }

    /**
     * 验证未开始、过期和关闭活动不能从缓存继续计价。
     */
    @Test
    void ignoresInactiveActivities() {
        FullDiscountVO activity = activity(20);
        activity.setStartTime(new Date(System.currentTimeMillis() + 60000));
        assertThat(SingleReductionSupport.price(goods(100), promotions(activity))).isNull();
        activity.setStartTime(new Date(System.currentTimeMillis() - 120000));
        activity.setEndTime(new Date(System.currentTimeMillis() - 60000));
        assertThat(SingleReductionSupport.price(goods(100), promotions(activity))).isNull();
        activity.setStartTime(null);
        activity.setEndTime(null);
        assertThat(SingleReductionSupport.price(goods(100), promotions(activity))).isNull();
    }

    /**
     * 验证缓存元数据的归属、范围和减额精度也不能绕过检查。
     */
    @Test
    void rejectsMismatchedOrMalformedCachedRules() {
        FullDiscountVO activity = activity(20);
        activity.setStoreId("foreign");
        assertThat(SingleReductionSupport.price(goods(100), promotions(activity))).isNull();
        activity.setStoreId("store");
        activity.setScopeId("another-sku");
        assertThat(SingleReductionSupport.price(goods(100), promotions(activity))).isNull();
        activity.setScopeId("sku");
        activity.setFullMinus(0.001);
        assertThat(SingleReductionSupport.price(goods(100), promotions(activity))).isNull();
        assertThat(SingleReductionSupport.price(goods(100), Map.of("SINGLE_REDUCTION-invalid", "bad-json"))).isNull();
    }

    /**
     * 验证单品立减不是满减，购买件数增加时按每件减免，实付只扣一次。
     */
    @Test
    void reducesEveryItemWithoutDoubleDeduction() {
        for (int count : new int[]{1, 2, 3}) {
            TradeDTO trade = trade(count, 100, activity(20));
            new SkuPromotionRender().render(trade);
            CartSkuVO sku = trade.getSkuList().get(0);
            assertThat(sku.getPurchasePrice()).isEqualTo(80);
            assertThat(sku.getPriceDetailDTO().getGoodsPrice()).isEqualTo(count * 100D);
            assertThat(sku.getPriceDetailDTO().getDiscountPrice()).isEqualTo(count * 20D);
            assertThat(sku.getPriceDetailDTO().getFlowPrice()).isEqualTo(count * 80D);
            assertThat(sku.getPriceDetailDTO().getDiscountPriceDetail()).hasSize(1);
        }
    }

    /**
     * 验证立减与满件减钱按顺序叠加，并保留两种优惠来源。
     */
    @Test
    void stacksWithCountDiscount() {
        TradeDTO trade = trade(2, 100, activity(20));
        addStoreActivity(trade, "FULL_COUNT_MINUS", 2, 10);
        new SkuPromotionRender().render(trade);
        new FullDiscountRender().render(trade);
        CartSkuVO sku = trade.getSkuList().get(0);
        assertThat(sku.getPriceDetailDTO().getFlowPrice()).isEqualTo(150);
        assertThat(sku.getPriceDetailDTO().getDiscountPrice()).isEqualTo(50);
        assertThat(sku.getPriceDetailDTO().getDiscountPriceDetail()).hasSize(2);
    }

    /**
     * 验证满额门槛使用立减后的金额，不能按原价跨越门槛。
     */
    @Test
    void amountThresholdUsesReducedPrice() {
        TradeDTO trade = trade(2, 100, activity(20));
        addStoreActivity(trade, "FULL_MINUS", 180, 10);
        new SkuPromotionRender().render(trade);
        new FullDiscountRender().render(trade);
        assertThat(trade.getSkuList().get(0).getPriceDetailDTO().getFlowPrice()).isEqualTo(160);
        assertThat(trade.getCartList().get(0).getPromotionNotice()).contains("还差20.0元");
    }

    /**
     * 验证满折只对立减后的金额打折，不重复按商品原价让利。
     */
    @Test
    void rateDiscountUsesReducedPrice() {
        TradeDTO trade = trade(2, 100, activity(20));
        addStoreActivity(trade, "FULL_RATE", 100, 8);
        new SkuPromotionRender().render(trade);
        new FullDiscountRender().render(trade);
        assertThat(trade.getSkuList().get(0).getPriceDetailDTO().getFlowPrice()).isEqualTo(128);
    }

    /**
     * 验证特殊活动订单不享单品立减，避免积分、拼团等重复让利。
     */
    @Test
    void doesNotApplyToSpecialOrderTypes() {
        TradeDTO trade = trade(2, 100, activity(20));
        trade.setCartTypeEnum(CartTypeEnum.PINTUAN);
        ReflectionTestUtils.invokeMethod(new SkuPromotionRender(), "renderSingleReduction", trade);
        assertThat(trade.getSkuList().get(0).getPriceDetailDTO().getDiscountPrice()).isZero();
    }

    /**
     * 验证成交规则快照不依赖后续活动修改，并可读回订单优惠名称。
     */
    @Test
    void preservesRuleSnapshot() {
        FullDiscountVO activity = activity(20);
        TradeDTO trade = trade(2, 100, activity);
        new SkuPromotionRender().render(trade);
        activity.setFullMinus(30D);
        var detail = trade.getSkuList().get(0).getPriceDetailDTO().getDiscountPriceDetail().get(0);
        assertThat(detail.getPromotionRule()).isEqualTo("每件立减20元");
        assertThat(detail.getPromotionName()).isEqualTo("单品立减");
        assertThat(JSON.parseObject(JSON.toJSONString(detail)).getDouble("discountPrice")).isEqualTo(40);
    }

    /**
     * 验证从促销绑定读取新元数据，旧满额及已退役类型的路由不被恢复。
     */
    @Test
    void resolvesNewPromotionMapping() {
        FullDiscountMapper mapper = mock(FullDiscountMapper.class);
        FullDiscountVO activity = activity(20);
        when(mapper.selectById("single")).thenReturn(activity);
        SkuPromotionQuerySupport queries = new SkuPromotionQuerySupport(null, null, null, mapper, null, null, null, null);
        PromotionGoods binding = new PromotionGoods();
        binding.setPromotionId("single");
        binding.setPromotionType("SINGLE_REDUCTION");
        assertThat(queries.wrapperPromotionMapList(List.of(binding))).containsEntry("SINGLE_REDUCTION-single", activity);
        assertThat(PromotionTypeEnum.isRetired("FLASH_DISCOUNT")).isTrue();
    }

    /**
     * 验证列表活动价从当前商品价格和有效规则计算，不读取绑定表的历史活动价格。
     */
    @Test
    void queryPriceUsesLiveSku() {
        PromotionGoodsMapper bindings = mock(PromotionGoodsMapper.class);
        FullDiscountMapper activities = mock(FullDiscountMapper.class);
        GoodsSkuReadSupport reads = mock(GoodsSkuReadSupport.class);
        PromotionGoods binding = new PromotionGoods();
        binding.setPromotionId("single");
        binding.setPromotionType("SINGLE_REDUCTION");
        binding.setPrice(1D);
        when(bindings.selectList(any())).thenReturn(List.of(binding));
        when(activities.selectById("single")).thenReturn(activity(20));
        when(reads.getGoodsSkuByIdFromCache("sku")).thenReturn(goods(120));
        SkuPromotionQuerySupport queries = new SkuPromotionQuerySupport(bindings, null, null, activities, null, null, null, reads);
        assertThat(queries.getValidPromotionsGoodsPrice("sku", List.of("SINGLE_REDUCTION"))).isEqualTo(100);
        verify(bindings, never()).selectPromotionsGoodsPrice(any());
    }

    /**
     * 验证现金券在立减后按剩余金额分摊，尾差守恒且优惠总额不超过商品金额。
     */
    @Test
    void couponAllocationPreservesCentsAndCapsDiscount() {
        TradeDTO trade = trade(2, 100, activity(20));
        new SkuPromotionRender().render(trade);
        PromotionPriceUtil.recountPrice(trade, Map.of("sku", 160D), 200D, PromotionTypeEnum.COUPON, "coupon");
        var price = trade.getSkuList().get(0).getPriceDetailDTO();
        assertThat(price.getCouponPrice()).isEqualTo(160);
        assertThat(price.getFlowPrice()).isZero();
        assertThat(price.getDiscountPriceDetail().stream().mapToDouble(item -> item.getDiscountPrice()).sum()).isEqualTo(200);
    }

    /**
     * 验证立减后的商品未达券门槛时不可用，不能用商品原价判定。
     */
    @Test
    void couponThresholdUsesNetAmount() {
        TradeDTO trade = trade(1, 100, activity(20));
        new SkuPromotionRender().render(trade);
        MemberCoupon coupon = new MemberCoupon();
        coupon.setStoreId("store");
        coupon.setPlatformFlag(false);
        coupon.setScopeType("ALL");
        coupon.setConsumeThreshold(90D);
        ReflectionTestUtils.invokeMethod(new CouponRender(), "available", trade, coupon);
        assertThat(trade.getCanUseCoupons()).isEmpty();
        assertThat(trade.getCantUseCoupons()).hasSize(1);
    }

    /**
     * 验证商品先加购、活动后创建时仍会读取当前促销，而不依赖购物车旧活动映射。
     */
    @Test
    void refreshesPromotionForPreviouslyPlainCartItem() {
        TradeDTO trade = trade(1, 100, activity(20));
        CartSkuVO item = trade.getSkuList().get(0);
        item.setCartType(CartTypeEnum.CART);
        item.setPromotionMap(null);
        item.setPurchasePrice(100D);
        GoodsSku goods = item.getGoodsSku();
        goods.setAuthFlag("PASS");
        goods.setMarketEnable("UPPER");
        goods.setQuantity(100);
        goods.setPromotionFlag(false);
        GoodsSkuService reads = mock(GoodsSkuService.class);
        when(reads.getGoodsSkuByIdFromCache("sku")).thenReturn(goods);
        PromotionGoodsService promotions = mock(PromotionGoodsService.class);
        when(promotions.getCurrentGoodsPromotion(goods, "CART")).thenAnswer(invocation -> {
            goods.setPromotionFlag(true);
            goods.setPromotionPrice(80D);
            return new HashMap<>(promotions(activity(20)));
        });
        CheckDataRender render = new CheckDataRender();
        ReflectionTestUtils.setField(render, "goodsSkuService", reads);
        ReflectionTestUtils.setField(render, "promotionGoodsService", promotions);
        ReflectionTestUtils.invokeMethod(render, "checkData", trade);
        verify(promotions).getCurrentGoodsPromotion(goods, "CART");
        assertThat(item.getPurchasePrice()).isEqualTo(80);
    }

    /**
     * 验证活动过期后清除此前缓存的立减价，不能继续以旧活动价结算。
     */
    @Test
    void clearsExpiredSkuPromotionPrice() {
        FullDiscountVO activity = activity(20);
        activity.setEndTime(new Date(System.currentTimeMillis() - 1));
        GoodsSku goods = goods(100);
        goods.setPromotionFlag(true);
        goods.setPromotionPrice(80D);
        GoodsIndex index = new GoodsIndex(goods);
        index.setPromotionMapJson(JSON.toJSONString(promotions(activity)));
        GoodsIndexService indices = mock(GoodsIndexService.class);
        when(indices.findById("sku")).thenReturn(index);
        PromotionGoodsServiceImpl service = new PromotionGoodsServiceImpl();
        ReflectionTestUtils.setField(service, "goodsIndexService", indices);
        assertThat(service.getCurrentGoodsPromotion(goods, "CART")).isNullOrEmpty();
        assertThat(goods.getPromotionFlag()).isFalse();
        assertThat(goods.getPromotionPrice()).isNull();
    }

    /**
     * 验证不同SKU现金券分摊到分，不能因按原价加权造成尾差或超额。
     */
    @Test
    void couponAllocationConservesCentsAcrossSkus() {
        FullDiscountVO activity = activity(20);
        activity.setScopeId("sku,b");
        TradeDTO trade = trade(1, 100, activity);
        CartSkuVO second = new CartSkuVO(goods(120));
        second.getGoodsSku().setId("b");
        second.setNum(1);
        second.setPurchasePrice(100D);
        second.setSubTotal(100D);
        second.getPriceDetailDTO().setOriginalPrice(120D);
        second.setPromotionMap(promotions(activity));
        List<CartSkuVO> items = List.of(trade.getSkuList().get(0), second);
        trade.setSkuList(items);
        trade.getCartList().get(0).setSkuList(items);
        new SkuPromotionRender().render(trade);
        PromotionPriceUtil.recountPrice(trade, Map.of("sku", 80D, "b", 100D), 0.01D, PromotionTypeEnum.COUPON, "coupon");
        assertThat(items.stream().mapToDouble(item -> item.getPriceDetailDTO().getCouponPrice()).sum()).isEqualTo(0.01);
        assertThat(items.stream().mapToDouble(item -> item.getPriceDetailDTO().getFlowPrice()).sum()).isEqualTo(179.99);
    }

    /**
     * 验证平台券与店铺券分账正确，平台券金额不会从商品立减金额中丢失或重复计算。
     */
    @Test
    void recordsPlatformCouponSeparately() {
        TradeDTO trade = trade(1, 100, activity(20));
        new SkuPromotionRender().render(trade);
        PromotionPriceUtil.recountPrice(trade, Map.of("sku", 80D), 10D, PromotionTypeEnum.PLATFORM_COUPON, "platform");
        var price = trade.getSkuList().get(0).getPriceDetailDTO();
        assertThat(price.getSiteCouponPrice()).isEqualTo(10);
        assertThat(price.getCouponPrice()).isEqualTo(10);
        assertThat(price.getDiscountPrice()).isEqualTo(20);
        assertThat(price.getFlowPrice()).isEqualTo(70);
    }

    /**
     * 验证活动名称由后端校验，不能绕过页面必填约束。
     */
    @Test
    void rejectsBlankPromotionName() {
        FullDiscountVO activity = activity(20);
        activity.setPromotionName("  ");
        assertThrows(ServiceException.class, () -> service(100).checkPromotions(activity));
    }

    /**
     * 验证单品立减价不会污染拼团、砍价等特殊购买模式的SKU价格。
     */
    @Test
    void specialCartModeDoesNotSelectSingleReductionPrice() {
        GoodsSku goods = goods(100);
        PromotionGoodsServiceImpl service = new PromotionGoodsServiceImpl();
        ReflectionTestUtils.invokeMethod(service, "applyBestSkuPromotionPrice", goods, promotions(activity(20)), "PINTUAN");
        assertThat(goods.getPromotionFlag()).isFalse();
        assertThat(goods.getPromotionPrice()).isNull();
    }

    /**
     * 构造真实校验链并模拟存储，price为当前商品售价（元）。
     */
    private FullDiscountServiceImpl service(double price) {
        FullDiscountServiceImpl service = spy(new FullDiscountServiceImpl());
        GoodsSkuReadSupport reads = mock(GoodsSkuReadSupport.class);
        when(reads.getGoodsSkuByIdFromCache("sku")).thenReturn(goods(price));
        ReflectionTestUtils.setField(service, "skuReads", reads);
        ReflectionTestUtils.setField(service, "promotionConflictService", mock(PromotionConflictService.class));
        doReturn(List.of()).when(service).list(any(Wrapper.class));
        return service;
    }

    /**
     * 构造指定SKU的有效单品立减活动。
     */
    private FullDiscountVO activity(double amount) {
        FullDiscountVO activity = new FullDiscountVO();
        activity.setId("single");
        activity.setPromotionName("单品立减测试");
        activity.setStoreId("store");
        activity.setActivityType("SINGLE_REDUCTION");
        activity.setScopeType("PORTION_GOODS");
        activity.setScopeId("sku");
        activity.setStartTime(new Date(System.currentTimeMillis() - 60000));
        activity.setEndTime(new Date(System.currentTimeMillis() + 60000));
        activity.setFullMinus(amount);
        activity.setTitle("每件立减20元");
        activity.setRuleList(new ArrayList<>(List.of(rule(amount))));
        return activity;
    }

    /**
     * 构造唯一的每件固定减额规则。
     */
    private FullDiscountRuleDTO rule(double amount) {
        return FullDiscountRuleDTO.builder().fullMinus(amount).build();
    }

    /**
     * 构造当前店铺的SKU，不读取数据库。
     */
    private GoodsSku goods(double price) {
        GoodsSku sku = new GoodsSku();
        sku.setId("sku");
        sku.setGoodsId("goods");
        sku.setGoodsName("真实商品");
        sku.setStoreId("store");
        sku.setPrice(price);
        return sku;
    }

    /**
     * 构造活动映射，覆盖序列化对象读回的常用路径。
     */
    private Map<String, Object> promotions(FullDiscountVO activity) {
        return Map.of("SINGLE_REDUCTION-single", activity);
    }

    /**
     * 构造普通购买购物车，先按展示活动价初始化，验证后续不会重复扣减。
     */
    private TradeDTO trade(int count, double price, FullDiscountVO activity) {
        CartSkuVO sku = new CartSkuVO(goods(price));
        sku.setNum(count);
        sku.setPurchasePrice(price - activity.getFullMinus());
        sku.setSubTotal(sku.getPurchasePrice() * count);
        sku.setStoreId("store");
        sku.setPromotionMap(new HashMap<>(promotions(activity)));
        sku.getPriceDetailDTO().setOriginalPrice(price * count);
        CartVO cart = new CartVO();
        cart.setStoreId("store");
        cart.setSkuList(List.of(sku));
        TradeDTO trade = new TradeDTO();
        trade.setSkuList(List.of(sku));
        trade.setCartList(List.of(cart));
        return trade;
    }

    /**
     * 给同一SKU绑定店铺活动，threshold是件数或金额门槛，benefit是减额或折扣。
     */
    private void addStoreActivity(TradeDTO trade, String type, double threshold, double benefit) {
        FullDiscountVO activity = activity(benefit);
        activity.setId("store-activity");
        activity.setActivityType(type);
        FullDiscountRuleDTO rule = new FullDiscountRuleDTO();
        if (type.equals("FULL_COUNT_MINUS")) rule.setFullNum(threshold);
        else rule.setFullMoney(threshold);
        if (type.equals("FULL_RATE")) rule.setFullRate(benefit);
        else rule.setFullMinus(benefit);
        activity.setRuleList(new ArrayList<>(List.of(rule)));
        activity.setRulesJson(JSON.toJSONString(List.of(rule)));
        Map<String, Object> promotions = new HashMap<>(trade.getSkuList().get(0).getNotFilterPromotionMap());
        promotions.put("FULL_DISCOUNT-store-activity", activity);
        trade.getSkuList().get(0).setPromotionMap(promotions);
    }

    /**
     * 验证秒杀、拼团、砍价在报名或保存时均反向触发单品活动互斥校验，阻断与单品立减时间重叠。
     */
    @Test
    void rejectsSeckillPintuanAndKanjiaWhenExclusiveConflictExists() {
        PromotionConflictService conflicts = mock(PromotionConflictService.class);
        doThrow(new ServiceException("商品已在重叠时间段参与单品立减活动"))
                .when(conflicts).checkSkuExclusiveConflict(any(), anyString(), any(), any(), any(), any());

        GoodsSku sku = goods(100);
        sku.setQuantity(10);
        sku.setMarketEnable(GoodsStatusEnum.UPPER.name());

        // 秒杀报名冲突拦截
        SeckillApplyServiceImpl seckillApplyService = new SeckillApplyServiceImpl();
        SeckillMetadataSupport seckillMetadata = mock(SeckillMetadataSupport.class);
        GoodsSkuReadSupport skuReads = mock(GoodsSkuReadSupport.class);
        Seckill seckill = new Seckill();
        seckill.setId("seckill-1");
        seckill.setHours("10");
        seckill.setStartTime(new Date(System.currentTimeMillis() + 3600_000));
        seckill.setEndTime(new Date(System.currentTimeMillis() + 7200_000));
        when(seckillMetadata.getById("seckill-1")).thenReturn(seckill);
        when(skuReads.getGoodsSkuByIdFromCache("sku")).thenReturn(sku);
        ReflectionTestUtils.setField(seckillApplyService, "seckillMetadata", seckillMetadata);
        ReflectionTestUtils.setField(seckillApplyService, "skuReads", skuReads);
        ReflectionTestUtils.setField(seckillApplyService, "promotionConflictService", conflicts);
        SeckillApplyVO applyVO = new SeckillApplyVO();
        applyVO.setSkuId("sku");
        applyVO.setTimeLine(10);
        applyVO.setQuantity(1);
        applyVO.setPrice(50D);
        applyVO.setOriginalPrice(100D);
        assertThrows(ServiceException.class, () -> seckillApplyService.addSeckillApply("seckill-1", "store", List.of(applyVO)));

        // 拼团绑定冲突拦截
        PintuanServiceImpl pintuanService = new PintuanServiceImpl();
        GoodsSkuService goodsSkuService = mock(GoodsSkuService.class);
        when(goodsSkuService.getCanPromotionGoodsSkuByIdFromCache("sku")).thenReturn(sku);
        ReflectionTestUtils.setField(pintuanService, "goodsSkuService", goodsSkuService);
        ReflectionTestUtils.setField(pintuanService, "promotionConflictService", conflicts);
        PintuanVO pintuan = new PintuanVO();
        pintuan.setId("pintuan-1");
        pintuan.setStartTime(new Date(System.currentTimeMillis() + 3600_000));
        pintuan.setEndTime(new Date(System.currentTimeMillis() + 7200_000));
        PromotionGoods pg = new PromotionGoods(sku);
        pintuan.setPromotionGoodsList(List.of(pg));
        assertThrows(ServiceException.class, () -> ReflectionTestUtils.invokeMethod(pintuanService, "updatePintuanPromotionGoods", pintuan));

        // 砍价新增冲突拦截
        KanjiaActivityGoodsServiceImpl kanjiaService = spy(new KanjiaActivityGoodsServiceImpl());
        ReflectionTestUtils.setField(kanjiaService, "goodsSkuService", goodsSkuService);
        ReflectionTestUtils.setField(kanjiaService, "promotionConflictService", conflicts);
        doReturn(null).when(kanjiaService).getOne(any());
        KanjiaActivityGoodsOperationDTO kanjiaOp = new KanjiaActivityGoodsOperationDTO();
        kanjiaOp.setStartTime(new Date(System.currentTimeMillis() + 3600_000));
        kanjiaOp.setEndTime(new Date(System.currentTimeMillis() + 7200_000));
        KanjiaActivityGoodsDTO kanjiaDto = new KanjiaActivityGoodsDTO();
        kanjiaDto.setSkuId("sku");
        kanjiaDto.setStock(1);
        kanjiaDto.setPurchasePrice(50D);
        kanjiaDto.setSettlementPrice(50D);
        kanjiaDto.setHighestPrice(10D);
        kanjiaDto.setLowestPrice(5D);
        kanjiaOp.setPromotionGoodsList(List.of(kanjiaDto));
        assertThrows(ServiceException.class, () -> kanjiaService.add(kanjiaOp));
    }

    /**
     * 验证已关闭或已结束的活动不可再编辑或通过状态接口重新开启，只能通过复制新建。
     */
    @Test
    void rejectsEditAndReopenForClosedOrEndedActivity() {
        FullDiscountServiceImpl service = service(100);
        FullDiscountVO closed = activity(20);
        closed.setId("act-closed");
        closed.setStartTime(null);
        closed.setEndTime(null);
        doReturn(closed).when(service).getById("act-closed");
        doReturn(List.<FullDiscount>of(closed)).when(service).listByIds(List.of("act-closed"));

        assertThrows(ServiceException.class, () -> service.updatePromotions(closed));
        assertThrows(ServiceException.class, () -> service.updateStatus(
                List.of("act-closed"),
                System.currentTimeMillis() + 3600_000,
                System.currentTimeMillis() + 7200_000));

        FullDiscountVO ended = activity(20);
        ended.setId("act-ended");
        ended.setStartTime(new Date(System.currentTimeMillis() - 7200_000));
        ended.setEndTime(new Date(System.currentTimeMillis() - 3600_000));
        doReturn(ended).when(service).getById("act-ended");
        doReturn(List.<FullDiscount>of(ended)).when(service).listByIds(List.of("act-ended"));

        assertThrows(ServiceException.class, () -> service.updatePromotions(ended));
        assertThrows(ServiceException.class, () -> service.updateStatus(
                List.of("act-ended"),
                System.currentTimeMillis() + 3600_000,
                System.currentTimeMillis() + 7200_000));
    }
}
