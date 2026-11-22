package cn.lili.modules.promotion.entity.dos;

import cn.lili.mybatis.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 成功兑换流水，保存成交积分、券名称及已发放会员券的关联，成功后不主动退兑。
 * 同会员同请求号仅允许一条记录；券金额、范围和有效期快照保存在关联的会员券中。
 *
 * @author Bulbasaur
 * @since 2026-11-14
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("li_points_coupon_record")
public class PointsCouponRecord extends BaseEntity {
    @Schema(description = "积分兑券活动ID，对应li_points_coupon.id")
    private String activityId;
    @Schema(description = "兑换会员ID，对应li_member.id")
    private String memberId;
    @Schema(description = "客户端兑换请求号，1至64位字母数字或连字符，重试必须沿用")
    private String requestId;
    @Schema(description = "优惠券模板ID，对应li_coupon.id")
    private String couponId;
    @Schema(description = "兑换时优惠券名称快照")
    private String couponName;
    @Schema(description = "本次实际消耗的整数积分快照")
    private Long points;
    @Schema(description = "已发放会员券ID，对应li_member_coupon.id，含金额、范围和有效期快照")
    private String memberCouponId;
}
