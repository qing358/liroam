package cn.lili.modules.promotion.service;

import cn.lili.common.vo.PageVO;
import cn.lili.modules.promotion.entity.dos.PointsCoupon;
import cn.lili.modules.promotion.entity.dos.PointsCouponRecord;
import cn.lili.modules.promotion.entity.vos.PointsCouponVO;
import com.baomidou.mybatisplus.core.metadata.IPage;

/**
 * 平台积分兑券业务，统一配置校验、兑换事务与流水查询。
 *
 * @author Bulbasaur
 * @since 2026-11-14
 */
public interface PointsCouponService {
    /**
     * 查询活动及券面信息。
     *
     * @param page 分页
     * @param buyer 是否仅展示可兑换活动
     * @return 活动分页
     */
    IPage<PointsCouponVO> activities(PageVO page, boolean buyer);
    /**
     * 创建或更新配置，不接收已兑数量等服务端字段。
     *
     * @param input 活动配置
     * @return 保存后活动
     */
    PointsCoupon configure(PointsCoupon input);
    /**
     * 修改上架状态，不改变历史流水和已发券。
     *
     * @param id 活动ID
     * @param enabled 是否上架
     */
    void changeStatus(String id, boolean enabled);
    /**
     * 扣积分并发一张券，重复请求返回原流水。
     *
     * @param id 活动ID
     * @param memberId 当前会员ID
     * @param requestId 幂等请求号
     * @param expectedPoints 用户确认的积分数，变价需重新确认
     * @return 成功流水
     */
    PointsCouponRecord exchange(String id, String memberId, String requestId, Long expectedPoints);
    /**
     * 查询成功流水，买家必须指定当前会员。
     *
     * @param page 分页
     * @param memberId 会员ID，管理端可为空
     * @return 成功流水分页
     */
    IPage<PointsCouponRecord> records(PageVO page, String memberId);
}
