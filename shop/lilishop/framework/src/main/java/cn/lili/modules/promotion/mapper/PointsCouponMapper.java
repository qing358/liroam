package cn.lili.modules.promotion.mapper;

import cn.lili.modules.promotion.entity.dos.PointsCoupon;
import cn.lili.modules.promotion.entity.dos.Coupon;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 积分兑券活动存储及事务内当前读，串行化活动库存、配置和券模板变更。
 *
 * @author Bulbasaur
 * @since 2026-11-14
 */
public interface PointsCouponMapper extends BaseMapper<PointsCoupon> {
    /**
     * 锁定活动至事务结束，防止超兑和配置更新穿透。
     *
     * @param id 活动ID
     * @return 当前活动
     */
    @Select("SELECT * FROM li_points_coupon WHERE id=#{id} AND delete_flag=0 FOR UPDATE")
    PointsCoupon lockActivity(@Param("id") String id);

    /**
     * 锁定券模板至发券结束，防止读取旧状态或被同时改成免费券。
     *
     * @param id 券ID
     * @return 当前券
     */
    @Select("SELECT * FROM li_coupon WHERE id=#{id} AND delete_flag=0 FOR UPDATE")
    Coupon lockCoupon(@Param("id") String id);
}
