package cn.lili.modules.promotion.serviceimpl;

import cn.lili.common.exception.ServiceException;
import cn.lili.modules.member.entity.dos.Member;
import cn.lili.modules.member.mapper.MemberMapper;
import cn.lili.modules.member.mapper.MemberPointsHistoryMapper;
import cn.lili.modules.member.service.MemberService;
import cn.lili.modules.promotion.entity.dos.*;
import cn.lili.modules.promotion.mapper.*;
import cn.lili.modules.promotion.service.MemberCouponService;
import cn.lili.modules.promotion.support.CouponReceiveSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import java.util.Date;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 积分兑券业务边界单元测试，覆盖重复请求、余额、限额、变价、券归属及失败中断。
 * 使用模拟存储，不代表真实数据库行锁、事务回滚或并发集成验收。
 *
 * @author Bulbasaur
 * @since 2026-11-14
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PointsCouponServiceImplTest {
    @Mock private PointsCouponMapper activities;
    @Mock private PointsCouponRecordMapper records;
    @Mock private CouponMapper coupons;
    @Mock private MemberMapper members;
    @Mock private MemberService memberService;
    @Mock private MemberPointsHistoryMapper histories;
    @Mock private MemberCouponService memberCoupons;
    @Mock private CouponReceiveSupport receive;
    @InjectMocks private PointsCouponServiceImpl service;
    private PointsCoupon activity;
    private Coupon coupon;
    private Member member;

    /**
     * 准备可兑换平台动态券、100积分定价和足额会员。
     */
    @BeforeEach
    void setup() {
        member = new Member(); member.setId("member"); member.setUsername("会员"); member.setDisabled(true); member.setPoint(500L);
        activity = new PointsCoupon(); activity.setId("activity"); activity.setName("积分换券"); activity.setCouponId("coupon");
        activity.setPoints(100L); activity.setTotal(10); activity.setExchanged(0); activity.setMemberLimit(2); activity.setEnabled(true);
        activity.setStartTime(new Date(System.currentTimeMillis() - 10000)); activity.setEndTime(new Date(System.currentTimeMillis() + 86400000));
        coupon = new Coupon(); coupon.setId("coupon"); coupon.setCouponName("满100减10"); coupon.setStoreId("0"); coupon.setGetType("ACTIVITY");
        coupon.setRangeDayType("DYNAMICTIME"); coupon.setEffectiveDays(7); coupon.setPrice(10D); coupon.setConsumeThreshold(100D);
        when(members.lockPointMember("member")).thenReturn(member);
        when(activities.lockActivity("activity")).thenReturn(activity);
        when(activities.lockCoupon("coupon")).thenReturn(coupon);
        when(memberService.updateMemberPoint(anyLong(), anyString(), anyString(), anyString(), anyString())).thenReturn(true);
        when(histories.selectCount(any())).thenReturn(0L, 1L);
        when(memberCoupons.save(any(MemberCoupon.class))).thenAnswer(call -> { call.<MemberCoupon>getArgument(0).setId("issued"); return true; });
        when(activities.updateById(any(PointsCoupon.class))).thenReturn(1);
        when(activities.insert(any(PointsCoupon.class))).thenReturn(1);
        when(records.insert(any(PointsCouponRecord.class))).thenReturn(1);
    }

    /**
     * 成功兑换只发一张并留存成交积分及会员券关联。
     */
    @Test void exchangesOneCouponAndPreservesTransactionEvidence() {
        PointsCouponRecord record = service.exchange("activity", "member", "request-1", 100L);
        assertThat(record.getPoints()).isEqualTo(100L);
        assertThat(record.getMemberCouponId()).isEqualTo("issued");
        assertThat(record.getCouponName()).isEqualTo("满100减10");
        assertThat(activity.getExchanged()).isEqualTo(1);
        ArgumentCaptor<MemberCoupon> issued = ArgumentCaptor.forClass(MemberCoupon.class);
        verify(memberCoupons).save(issued.capture());
        assertThat(issued.getValue().getPrice()).isEqualTo(10D);
        assertThat(issued.getValue().getMemberId()).isEqualTo("member");
        assertThat(issued.getValue().getEndTime()).isAfter(new Date());
        verify(receive).receiveCoupon("coupon", 1);
        verify(memberService).updateMemberPoint(eq(100L), eq("REDUCE"), eq("member"), eq("积分兑换优惠券「满100减10」"), eq("COUPON_EXCHANGE"));
    }

    /**
     * 已成功请求即使活动下架或变价也返回原结果，不重复扣发。
     */
    @Test void retriesReturnOriginalResultWithoutChargingAgain() {
        PointsCouponRecord previous = new PointsCouponRecord(); previous.setActivityId("activity"); previous.setPoints(100L);
        when(records.findRequest("member", "request-1")).thenReturn(previous);
        assertThat(service.exchange("activity", "member", "request-1", 200L)).isSameAs(previous);
        verifyNoInteractions(activities, memberService, histories, memberCoupons, receive);
    }

    /**
     * 同请求号不能用于其他活动。
     */
    @Test void rejectsReusingRequestForAnotherActivity() {
        PointsCouponRecord previous = new PointsCouponRecord(); previous.setActivityId("another");
        when(records.findRequest("member", "request-1")).thenReturn(previous);
        assertThatThrownBy(() -> service.exchange("activity", "member", "request-1", 100L)).hasMessageContaining("其他兑换活动");
        verifyNoInteractions(memberService, receive);
    }

    /**
     * 非法请求号在访问数据库前拒绝。
     */
    @Test void rejectsMalformedRequestId() {
        assertThatThrownBy(() -> service.exchange("activity", "member", "bad/request", 100L)).isInstanceOf(ServiceException.class);
        verifyNoInteractions(members, records, memberService);
    }

    /**
     * 禁用会员不可消费积分。
     */
    @Test void rejectsDisabledMember() {
        member.setDisabled(false);
        assertThatThrownBy(() -> service.exchange("activity", "member", "request-1", 100L)).hasMessageContaining("已禁用");
        verifyNoInteractions(memberService, receive);
    }

    /**
     * 余额不足时不调用原来不足扣零的通用积分方法。
     */
    @Test void rejectsInsufficientPointsWithoutDeduction() {
        member.setPoint(99L);
        assertThatThrownBy(() -> service.exchange("activity", "member", "request-1", 100L)).hasMessageContaining("积分不足");
        verifyNoInteractions(memberService, receive);
    }

    /**
     * 定价变化需要用户刷新并重新确认。
     */
    @Test void rejectsChangedPrice() {
        assertThatThrownBy(() -> service.exchange("activity", "member", "request-1", 90L)).hasMessageContaining("积分已变化");
        verifyNoInteractions(memberService, receive);
    }

    /**
     * 活动库存耗尽不能发券。
     */
    @Test void rejectsExhaustedActivity() {
        activity.setExchanged(10);
        assertThatThrownBy(() -> service.exchange("activity", "member", "request-1", 100L)).hasMessageContaining("数量已用完");
        verifyNoInteractions(memberService, receive);
    }

    /**
     * 活动累计限额不随优惠券使用或过期重置。
     */
    @Test void rejectsMemberLimit() {
        when(records.selectCount(any())).thenReturn(2L);
        assertThatThrownBy(() -> service.exchange("activity", "member", "request-1", 100L)).hasMessageContaining("限兑次数");
        verifyNoInteractions(memberService, receive);
    }

    /**
     * 下架或结束的活动拒绝兑换。
     */
    @Test void rejectsUnavailableActivity() {
        activity.setEnabled(false);
        assertThatThrownBy(() -> service.exchange("activity", "member", "request-1", 100L)).hasMessageContaining("已下架");
        activity.setEnabled(true); activity.setEndTime(new Date(System.currentTimeMillis() - 1));
        assertThatThrownBy(() -> service.exchange("activity", "member", "request-1", 100L)).hasMessageContaining("已结束");
        verifyNoInteractions(memberService, receive);
    }

    /**
     * 不允许免费券或店铺券混入平台积分活动。
     */
    @Test void rejectsFreeAndStoreCoupons() {
        coupon.setGetType("FREE");
        assertThatThrownBy(() -> service.exchange("activity", "member", "request-1", 100L)).hasMessageContaining("平台发布");
        coupon.setGetType("ACTIVITY"); coupon.setStoreId("store");
        assertThatThrownBy(() -> service.exchange("activity", "member", "request-1", 100L)).hasMessageContaining("平台发布");
        verifyNoInteractions(memberService, receive);
    }

    /**
     * 券模板自身限领失败也必须在积分扣款前中断。
     */
    @Test void respectsExistingCouponLimitsBeforeDeduction() {
        doThrow(new ServiceException("券已达限领次数")).when(memberCoupons).checkCouponLimit("coupon", "member");
        assertThatThrownBy(() -> service.exchange("activity", "member", "request-1", 100L)).hasMessageContaining("限领次数");
        verifyNoInteractions(memberService, receive);
    }

    /**
     * 积分流水缺失必须抛错交给事务回滚，不继续发券。
     */
    @Test void abortsWhenPointHistoryIsMissing() {
        when(histories.selectCount(any())).thenReturn(0L, 0L);
        assertThatThrownBy(() -> service.exchange("activity", "member", "request-1", 100L)).hasMessageContaining("积分流水保存失败");
        verifyNoInteractions(receive);
        verify(memberCoupons, never()).save(any(MemberCoupon.class));
        verify(records, never()).insert(any(PointsCouponRecord.class));
    }

    /**
     * 会员券落库失败不得写成功流水或活动计数。
     */
    @Test void abortsWhenCouponSaveFails() {
        when(memberCoupons.save(any(MemberCoupon.class))).thenReturn(false);
        assertThatThrownBy(() -> service.exchange("activity", "member", "request-1", 100L)).hasMessageContaining("发放失败");
        verify(records, never()).insert(any(PointsCouponRecord.class));
        verify(activities, never()).updateById(any(PointsCoupon.class));
    }

    /**
     * 配置更新不能缩减到已兑数量以下或更换关联券。
     */
    @Test void protectsIssuedHistoryDuringConfiguration() {
        activity.setExchanged(5);
        PointsCoupon input = new PointsCoupon(); org.springframework.beans.BeanUtils.copyProperties(activity, input); input.setTotal(4);
        assertThatThrownBy(() -> service.configure(input)).hasMessageContaining("少于已兑换");
        input.setCouponId("other");
        assertThatThrownBy(() -> service.configure(input)).hasMessageContaining("不能更换");
        verify(activities, never()).updateById(any(PointsCoupon.class));
    }

    /**
     * 新增不接受伪造的已兑数量和上架标记。
     */
    @Test void newConfigurationIgnoresServerOwnedFields() {
        activity.setId(null); activity.setExchanged(9); activity.setEnabled(true); activity.setPublished(true);
        activity.setStartTime(new Date(System.currentTimeMillis() + 60000));
        PointsCoupon saved = service.configure(activity);
        assertThat(saved.getExchanged()).isZero(); assertThat(saved.getEnabled()).isFalse(); assertThat(saved.getPublished()).isFalse();
    }

    /**
     * 已发布且下架后的活动，不允许再编辑或重新上架。
     */
    @Test void rejectsEditAndRelistAfterDisabled() {
        activity.setEnabled(false); activity.setPublished(true);
        PointsCoupon editing = new PointsCoupon(); org.springframework.beans.BeanUtils.copyProperties(activity, editing);
        assertThatThrownBy(() -> service.configure(editing)).hasMessageContaining("不允许编辑");
        assertThatThrownBy(() -> service.changeStatus("activity", true)).hasMessageContaining("不允许重新上架");
    }

    /**
     * 新增或修改开始时间时必须大于等于当前时间，编辑未改开始时间保留原值。
     */
    @Test void rejectsPastStartTimeOnCreateOrChange() {
        PointsCoupon creating = new PointsCoupon(); org.springframework.beans.BeanUtils.copyProperties(activity, creating);
        creating.setId(null);
        assertThatThrownBy(() -> service.configure(creating)).hasMessageContaining("大于等于当前时间");
        PointsCoupon editing = new PointsCoupon(); org.springframework.beans.BeanUtils.copyProperties(activity, editing);
        assertThat(service.configure(editing).getStartTime()).isEqualTo(activity.getStartTime());
        editing.setStartTime(new Date(System.currentTimeMillis() - 5000));
        assertThatThrownBy(() -> service.configure(editing)).hasMessageContaining("大于等于当前时间");
    }

    /**
     * 券模板被其他渠道领用时，展示库存不能超过模板剩余发行量。
     */
    @Test void displayedStockRespectsSharedCouponIssuance() {
        when(coupons.selectById("coupon")).thenReturn(coupon);
        when(activities.selectPage(any(), any())).thenAnswer(call -> {
            com.baomidou.mybatisplus.core.metadata.IPage<PointsCoupon> page = call.getArgument(0);
            page.setRecords(java.util.List.of(activity)); return page;
        });
        coupon.setPublishNum(100); coupon.setReceivedNum(97);
        assertThat(service.activities(new cn.lili.common.vo.PageVO(), false).getRecords().getFirst().getRemaining()).isEqualTo(3);
        coupon.setPublishNum(0);
        assertThat(service.activities(new cn.lili.common.vo.PageVO(), false).getRecords().getFirst().getRemaining()).isEqualTo(10);
    }

    /**
     * 模板已耗尽或已删除时，不向用户展示虚假的活动可兑余量。
     */
    @Test void missingOrExhaustedTemplateHasNoDisplayStock() {
        when(activities.selectPage(any(), any())).thenAnswer(call -> {
            com.baomidou.mybatisplus.core.metadata.IPage<PointsCoupon> page = call.getArgument(0);
            page.setRecords(java.util.List.of(activity)); return page;
        });
        assertThat(service.activities(new cn.lili.common.vo.PageVO(), false).getRecords().getFirst().getRemaining()).isZero();
        when(coupons.selectById("coupon")).thenReturn(coupon);
        coupon.setPublishNum(10); coupon.setReceivedNum(10);
        assertThat(service.activities(new cn.lili.common.vo.PageVO(), false).getRecords().getFirst().getRemaining()).isZero();
    }
}
