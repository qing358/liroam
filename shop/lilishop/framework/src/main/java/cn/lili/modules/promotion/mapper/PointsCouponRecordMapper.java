package cn.lili.modules.promotion.mapper;

import cn.lili.modules.promotion.entity.dos.PointsCouponRecord;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 积分兑券成功流水存储，以会员和请求号唯一约束兜底重复请求。
 *
 * @author Bulbasaur
 * @since 2026-11-14
 */
public interface PointsCouponRecordMapper extends BaseMapper<PointsCouponRecord> {
    /**
     * 当前读重复请求，避免事务快照漏掉刚提交的兑换。
     *
     * @param memberId 会员ID
     * @param requestId 请求号
     * @return 成功流水或null
     */
    @Select("SELECT * FROM li_points_coupon_record WHERE member_id=#{memberId} AND request_id=#{requestId} FOR UPDATE")
    PointsCouponRecord findRequest(@Param("memberId") String memberId, @Param("requestId") String requestId);
}
