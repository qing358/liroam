package cn.lili.modules.order.order.entity.dto;

import cn.lili.common.enums.PromotionTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 优惠信息详情
 *
 * @author liushuai(liushuai711 @ gmail.com)
 * @version v4.0
 * @Description:
 *
 * @since 2022/12/23 14:52
 */
@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DiscountPriceItem {


    @Schema(description = "促销类型")
    private PromotionTypeEnum promotionTypeEnum;

    @Schema(description = "促销id")
    private String promotionId;

    @Schema(description = "减免金额")
    private Double discountPrice;

    @Schema(description = "涉及 商品ID")
    private String goodsId;

    @Schema(description = "涉及 SKU ID")
    private String skuId;


    @Schema(description = "活动子类型快照，FULL_COUNT_MINUS表示满件减钱；历史记录可为空")
    private String activityType;

    @Schema(description = "成交命中阶梯的规则快照，如满3件减20元；活动后续调整不改变此记录")
    private String promotionRule;

    /**
     * 获取成交优惠名称，多件优惠使用子类型快照，兼容没有快照的历史记录。
     *
     * @return 优惠名称
     */
    public String getPromotionName() {
        if ("FULL_COUNT_MINUS".equals(activityType)) {
            return "多件优惠";
        }
        return promotionTypeEnum == null ? "历史优惠" : promotionTypeEnum.description();
    }
}
