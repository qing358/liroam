package cn.lili.modules.promotion.entity.dos;

import cn.lili.mybatis.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.util.Date;

/**
 * 积分兑券活动，独立保存兑换定价、总量、限兑次数及有效区间。
 * 上架仅在兑换时间内生效，下架不影响已经发放的会员券；已兑数量只能由兑换事务增加。
 *
 * @author Bulbasaur
 * @since 2026-11-14
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("li_points_coupon")
public class PointsCoupon extends BaseEntity {
    @Schema(description = "兑换活动名称，最多100字")
    private String name;
    @Schema(description = "平台活动优惠券ID，对应li_coupon.id；创建后不可更换")
    private String couponId;
    @Schema(description = "每张券消耗的整数积分，必须大于0")
    private Long points;
    @Schema(description = "活动累计可兑换张数，必须大于0且不小于已兑数量")
    private Integer total;
    @Schema(description = "活动累计已成功兑换张数，失败事务不计数")
    private Integer exchanged;
    @Schema(description = "每位会员在本活动累计限兑次数，必须大于0，使用或过期不恢复次数")
    private Integer memberLimit;
    @Schema(description = "是否上架：true上架、false下架；仍受兑换时间和券有效性约束")
    private Boolean enabled;
    @Schema(description = "是否曾上架发布：false草稿、true已发布；已下架后不允许再编辑或重新上架")
    private Boolean published;
    @Schema(description = "兑换开始时间，Asia/Shanghai，含此时刻")
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private Date startTime;
    @Schema(description = "兑换结束时间，Asia/Shanghai，不含此时刻")
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private Date endTime;
}
