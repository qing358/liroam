package cn.lili.modules.promotion.entity.vos;

import cn.lili.modules.promotion.entity.dos.PointsCoupon;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.util.Date;

/**
 * 积分兑券展示信息，只附带买家需要的券面信息，不返回平台承担比例等内部配置。
 *
 * @author Bulbasaur
 * @since 2026-11-14
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PointsCouponVO extends PointsCoupon {
    @Schema(description = "优惠券名称")
    private String couponName;
    @Schema(description = "券类型：PRICE现金券、DISCOUNT折扣券")
    private String couponType;
    @Schema(description = "现金券抵扣金额，单位元")
    private Double price;
    @Schema(description = "折扣券折数，例如8.5表示八五折")
    private Double couponDiscount;
    @Schema(description = "使用门槛金额，单位元，0表示无门槛")
    private Double consumeThreshold;
    @Schema(description = "有效期类型：FIXEDTIME固定时间、DYNAMICTIME领取后生效")
    private String rangeDayType;
    @Schema(description = "领取后有效天数，含领取当日；仅动态有效期使用")
    private Integer effectiveDays;
    @Schema(description = "券使用规则说明")
    private String description;
    @Schema(description = "固定有效期券开始时间，Asia/Shanghai")
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private Date couponStartTime;
    @Schema(description = "固定有效期券截止时间，Asia/Shanghai")
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private Date couponEndTime;
    @Schema(description = "适用范围：ALL全品类、PORTION_GOODS指定商品、PORTION_GOODS_CATEGORY指定分类、PORTION_SHOP_CATEGORY指定店铺分类")
    private String scopeType;
    @Schema(description = "当前展示可兑张数，取活动剩余数量和券模板剩余发行量的较小值；模板不限发行时取活动剩余量，实际以兑换事务校验为准")
    private Integer remaining;
}
