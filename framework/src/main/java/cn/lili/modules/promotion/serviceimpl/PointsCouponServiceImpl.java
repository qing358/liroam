package cn.lili.modules.promotion.serviceimpl;

import cn.lili.common.enums.ResultCode;
import cn.lili.common.exception.ServiceException;
import cn.lili.common.vo.PageVO;
import cn.lili.modules.member.entity.dos.Member;
import cn.lili.modules.member.entity.dos.MemberPointsHistory;
import cn.lili.modules.member.entity.enums.PointSourceEnum;
import cn.lili.modules.member.entity.enums.PointTypeEnum;
import cn.lili.modules.member.mapper.MemberMapper;
import cn.lili.modules.member.mapper.MemberPointsHistoryMapper;
import cn.lili.modules.member.service.MemberService;
import cn.lili.modules.promotion.entity.dos.*;
import cn.lili.modules.promotion.entity.enums.MemberCouponStatusEnum;
import cn.lili.modules.promotion.entity.vos.PointsCouponVO;
import cn.lili.modules.promotion.mapper.*;
import cn.lili.modules.promotion.service.PointsCouponService;
import cn.lili.modules.promotion.service.MemberCouponService;
import cn.lili.modules.promotion.support.CouponReceiveSupport;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Date;
import java.util.Objects;

/**
 * 平台积分兑券实现。按会员、活动、券模板顺序加数据库行锁，扣积分、库存、发券和流水共同提交。
 * 每次一张、仅纯积分；成功后不退兑，券使用或过期不返积分，也不恢复活动累计限额。
 *
 * @author Bulbasaur
 * @since 2026-11-14
 */
@Service
@RequiredArgsConstructor
public class PointsCouponServiceImpl implements PointsCouponService {
    private final PointsCouponMapper activities;
    private final PointsCouponRecordMapper records;
    private final CouponMapper coupons;
    private final MemberMapper members;
    private final MemberService memberService;
    private final MemberPointsHistoryMapper histories;
    private final MemberCouponService memberCoupons;
    private final CouponReceiveSupport receive;

    /**
     * 查询活动，买家仅看到当前时间内上架且券模板仍可使用的活动。
     *
     * @param page 分页
     * @param buyer 买家视图
     * @return 展示分页
     */
    @Override
    public IPage<PointsCouponVO> activities(PageVO page, boolean buyer) {
        var query = new LambdaQueryWrapper<PointsCoupon>().orderByDesc(PointsCoupon::getCreateTime).orderByDesc(PointsCoupon::getId);
        if (buyer) {
            Date now = new Date();
            query.eq(PointsCoupon::getEnabled, true).le(PointsCoupon::getStartTime, now).gt(PointsCoupon::getEndTime, now)
                .apply("EXISTS (SELECT 1 FROM li_coupon c WHERE c.id=li_points_coupon.coupon_id AND c.delete_flag=0 AND c.store_id='0' AND c.get_type='ACTIVITY' AND ((c.range_day_type='DYNAMICTIME' AND c.effective_days BETWEEN 1 AND 365) OR (c.range_day_type='FIXEDTIME' AND c.start_time <= {0} AND c.end_time > {0})))", now);
        }
        IPage<PointsCoupon> result = activities.selectPage(page(page), query);
        return result.convert(activity -> {
            PointsCouponVO view = new PointsCouponVO();
            BeanUtils.copyProperties(activity, view);
            view.setRemaining(0);
            Coupon coupon = coupons.selectById(activity.getCouponId());
            if (coupon != null) {
                view.setCouponName(coupon.getCouponName());
                view.setCouponType(coupon.getCouponType());
                view.setPrice(coupon.getPrice());
                view.setCouponDiscount(coupon.getCouponDiscount());
                view.setConsumeThreshold(coupon.getConsumeThreshold());
                view.setRangeDayType(coupon.getRangeDayType());
                view.setEffectiveDays(coupon.getEffectiveDays());
                view.setDescription(coupon.getDescription());
                view.setCouponStartTime(coupon.getStartTime());
                view.setCouponEndTime(coupon.getEndTime());
                view.setScopeType(coupon.getScopeType());
                int remaining = Math.max(0, activity.getTotal() - activity.getExchanged());
                if (coupon.getPublishNum() != null && coupon.getPublishNum() > 0)
                    remaining = Math.min(remaining, Math.max(0, coupon.getPublishNum() - Objects.requireNonNullElse(coupon.getReceivedNum(), 0)));
                view.setRemaining(remaining);
            }
            return view;
        });
    }

    /**
     * 校验配置并只复制可编辑字段；活动关联券不可更换，历史定价由兑换流水保留。
     *
     * @param input 配置
     * @return 保存结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public PointsCoupon configure(PointsCoupon input) {
        if (input.getName() == null || input.getName().isBlank() || input.getName().length() > 100
                || input.getPoints() == null || input.getPoints() <= 0 || input.getPoints() > 9007199254740991L
                || input.getTotal() == null || input.getTotal() <= 0 || input.getMemberLimit() == null || input.getMemberLimit() <= 0
                || input.getStartTime() == null || input.getEndTime() == null || !input.getStartTime().before(input.getEndTime()))
            throw error("请检查名称、积分、数量、限兑次数和兑换时间");
        boolean creating = input.getId() == null || input.getId().isBlank();
        PointsCoupon row = creating ? new PointsCoupon() : requireActivity(input.getId());
        if (!creating && !Boolean.TRUE.equals(row.getEnabled()) && Boolean.TRUE.equals(row.getPublished()))
            throw error("已下架的活动不允许编辑");
        if ((creating || !Objects.equals(row.getStartTime(), input.getStartTime())) && input.getStartTime().before(new Date()))
            throw error("开始时间需要大于等于当前时间");
        if (!creating && !Objects.equals(row.getCouponId(), input.getCouponId())) throw error("已有活动不能更换优惠券，请新建活动");
        Coupon coupon = activities.lockCoupon(input.getCouponId());
        checkCoupon(coupon, false);
        if (input.getTotal() < (creating ? 0 : row.getExchanged())) throw error("总数量不能少于已兑换数量");
        row.setName(input.getName().trim());
        row.setCouponId(input.getCouponId());
        row.setPoints(input.getPoints());
        row.setTotal(input.getTotal());
        row.setMemberLimit(input.getMemberLimit());
        row.setStartTime(input.getStartTime());
        row.setEndTime(input.getEndTime());
        // 创建默认下架，发布前由管理端显式上架；更新不接受客户端覆盖上架状态或已兑数量。
        if (creating) { row.setEnabled(false); row.setPublished(false); row.setExchanged(0); }
        if ((creating ? activities.insert(row) : activities.updateById(row)) != 1) throw error("活动保存失败");
        return row;
    }

    /**
     * 上架时检查券与活动仍有效；下架保留兑换历史和已发券。
     *
     * @param id 活动ID
     * @param enabled 是否上架
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(String id, boolean enabled) {
        PointsCoupon row = requireActivity(id);
        if (enabled) {
            if (!Boolean.TRUE.equals(row.getEnabled()) && Boolean.TRUE.equals(row.getPublished()))
                throw error("已下架的活动不允许重新上架");
            checkCoupon(activities.lockCoupon(row.getCouponId()), false);
            if (!row.getEndTime().after(new Date())) throw error("兑换活动已结束，请先修改时间");
            row.setPublished(true);
        }
        row.setEnabled(enabled);
        if (activities.updateById(row) != 1) throw error("活动状态保存失败");
    }

    /**
     * 在同一事务中扣积分、增加活动及券发行计数、保存会员券与兑换流水。
     * 会员锁阻止并发超限，活动锁阻止超兑；同请求号重试返回原结果，不受后续下架或价格变化影响。
     *
     * @param id 活动ID
     * @param memberId 当前登录会员ID
     * @param requestId 客户端稳定请求号，失败重试复用
     * @param expectedPoints 用户确认的积分数，实际定价始终取服务端，变化时拒绝并提示刷新
     * @return 唯一成功流水
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public PointsCouponRecord exchange(String id, String memberId, String requestId, Long expectedPoints) {
        if (requestId == null || !requestId.matches("[A-Za-z0-9-]{1,64}")) throw error("兑换请求号不合法");
        Member member = members.lockPointMember(memberId);
        if (member == null || !Boolean.TRUE.equals(member.getDisabled())) throw error("会员不存在或已禁用");
        PointsCouponRecord previous = records.findRequest(memberId, requestId);
        if (previous != null) {
            if (!id.equals(previous.getActivityId())) throw error("请求号已用于其他兑换活动");
            return previous;
        }
        PointsCoupon row = requireActivity(id);
        if (!Objects.equals(expectedPoints, row.getPoints())) throw error("兑换积分已变化，请刷新后重新确认");
        Date now = new Date();
        if (!Boolean.TRUE.equals(row.getEnabled()) || now.before(row.getStartTime()) || !now.before(row.getEndTime())) throw error("活动未开始、已结束或已下架");
        if (row.getExchanged() >= row.getTotal()) throw error("兑换数量已用完");
        Coupon coupon = activities.lockCoupon(row.getCouponId());
        checkCoupon(coupon, true);
        long count = records.selectCount(new LambdaQueryWrapper<PointsCouponRecord>().eq(PointsCouponRecord::getActivityId, id).eq(PointsCouponRecord::getMemberId, memberId));
        if (count >= row.getMemberLimit()) throw error("已达到本活动限兑次数");
        memberCoupons.checkCouponLimit(coupon.getId(), memberId);
        if (member.getPoint() == null || member.getPoint() < row.getPoints()) throw error("积分不足");

        String content = "积分兑换优惠券「" + coupon.getCouponName() + "」";
        LambdaQueryWrapper<MemberPointsHistory> historyQuery = new LambdaQueryWrapper<MemberPointsHistory>()
                .eq(MemberPointsHistory::getMemberId, memberId)
                .eq(MemberPointsHistory::getPointSource, PointSourceEnum.COUPON_EXCHANGE.name())
                .eq(MemberPointsHistory::getContent, content);
        long beforeHistories = histories.selectCount(historyQuery);
        if (!Boolean.TRUE.equals(memberService.updateMemberPoint(row.getPoints(), PointTypeEnum.REDUCE.name(), memberId, content, PointSourceEnum.COUPON_EXCHANGE.name())))
            throw error("积分扣减失败");
        // 现有积分切面可能吞掉流水异常，显式确认本次新增一条流水，否则回滚余额和整个兑换。
        if (histories.selectCount(historyQuery) != beforeHistories + 1)
            throw error("积分流水保存失败");
        receive.receiveCoupon(coupon.getId(), 1);
        MemberCoupon issued = new MemberCoupon(coupon);
        issued.setMemberId(memberId);
        issued.setMemberName(member.getUsername());
        issued.setPlatformFlag(true);
        issued.setMemberCouponStatus(MemberCouponStatusEnum.NEW.name());
        if (!memberCoupons.save(issued)) throw error("优惠券发放失败");
        row.setExchanged(row.getExchanged() + 1);
        if (activities.updateById(row) != 1) throw error("兑换库存更新失败");
        PointsCouponRecord record = new PointsCouponRecord();
        record.setActivityId(id);
        record.setMemberId(memberId);
        record.setRequestId(requestId);
        record.setCouponId(coupon.getId());
        record.setCouponName(coupon.getCouponName());
        record.setPoints(row.getPoints());
        record.setMemberCouponId(issued.getId());
        if (records.insert(record) != 1) throw error("兑换记录保存失败");
        return record;
    }

    /**
     * 按当前用户隔离买家流水，管理端可查询全部成功记录。
     *
     * @param page 分页
     * @param memberId 会员ID或null
     * @return 流水分页
     */
    @Override
    public IPage<PointsCouponRecord> records(PageVO page, String memberId) {
        return records.selectPage(page(page), new LambdaQueryWrapper<PointsCouponRecord>().eq(memberId != null, PointsCouponRecord::getMemberId, memberId)
                .orderByDesc(PointsCouponRecord::getCreateTime).orderByDesc(PointsCouponRecord::getId));
    }

    /**
     * 构建受控分页，不采用客户端传入的动态排序字段。
     *
     * @param input 页码与大小
     * @param <T> 结果类型
     * @return 分页对象
     */
    private <T> Page<T> page(PageVO input) {
        int number = input.getPageNumber() == null ? 1 : Math.max(1, input.getPageNumber());
        int size = input.getPageSize() == null ? 10 : Math.max(1, Math.min(100, input.getPageSize()));
        return new Page<>(number, size);
    }

    /**
     * 加锁读取活动，不允许操作不存在或已删除的活动。
     *
     * @param id 活动ID
     * @return 活动
     */
    private PointsCoupon requireActivity(String id) {
        PointsCoupon row = activities.lockActivity(id);
        if (row == null) throw error("积分兑券活动不存在");
        return row;
    }

    /**
     * 校验平台活动券及有效性，拒绝免费券以防免费领取绕过积分；实际兑换还检查当前可领。
     *
     * @param coupon 券模板
     * @param exchanging 是否实际兑换
     */
    private void checkCoupon(Coupon coupon, boolean exchanging) {
        if (coupon == null || Boolean.TRUE.equals(coupon.getDeleteFlag()) || !"0".equals(coupon.getStoreId()) || !"ACTIVITY".equals(coupon.getGetType()))
            throw error("请选择平台发布的活动获取优惠券");
        Date now = new Date();
        boolean dynamic = "DYNAMICTIME".equals(coupon.getRangeDayType()) && coupon.getEffectiveDays() != null
                && coupon.getEffectiveDays() > 0 && coupon.getEffectiveDays() <= 365;
        boolean fixed = "FIXEDTIME".equals(coupon.getRangeDayType()) && coupon.getStartTime() != null
                && coupon.getEndTime() != null && coupon.getEndTime().after(now)
                && (!exchanging || !coupon.getStartTime().after(now));
        if (!dynamic && !fixed) throw error("优惠券已关闭、过期或尚未生效");
    }

    /**
     * 构造可展示的业务校验错误。
     *
     * @param message 原因
     * @return 业务异常
     */
    private ServiceException error(String message) {
        return new ServiceException(ResultCode.PARAMS_ERROR, message);
    }
}
