package cn.lili.modules.appointment.support;

import cn.lili.modules.appointment.entity.dos.AppointmentOrderExt;
import cn.lili.modules.appointment.entity.dos.AppointmentWorkOrder;
import cn.lili.modules.appointment.entity.enums.AppointmentWorkOrderStatusEnum;
import cn.lili.modules.appointment.mapper.AppointmentOrderExtMapper;
import cn.lili.modules.appointment.mapper.AppointmentVerificationCodeMapper;
import cn.lili.modules.appointment.mapper.AppointmentWorkOrderMapper;
import cn.lili.modules.order.order.entity.dos.Order;
import cn.lili.modules.order.order.entity.dos.OrderItem;
import cn.lili.modules.order.order.entity.enums.OrderStatusEnum;
import cn.lili.modules.order.order.entity.enums.OrderTypeEnum;
import cn.lili.modules.order.order.mapper.OrderMapper;
import cn.lili.modules.order.order.service.OrderItemService;
import cn.lili.modules.order.order.support.OrderLifecycleSupport;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 预约售后支持组件构造器注入回归测试，验证部分退款保留履约、全额退款关单并回补预约资源。
 * 存储和协作组件使用模拟对象，不连接数据库或调用支付渠道。
 *
 * @author Bulbasaur
 * @since 2026-11-18
 */
class AppointmentAfterSaleSupportRefundTest {

    private AppointmentAfterSaleSupport support;
    private AppointmentWorkOrderMapper workOrders;
    private AppointmentOrderExtMapper extensions;
    private OrderMapper orders;
    private OrderItemService orderItems;
    private AppointmentSlotStockSupport slots;
    private AppointmentVerificationSupport verification;
    private Order order;
    private AppointmentOrderExt extension;

    /** 初始化真实条件更新所需的实体元数据，不创建数据库连接。 */
    @BeforeAll
    static void initMetadata() {
        var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "appointment-refund");
        TableInfoHelper.initTableInfo(assistant, Order.class);
        TableInfoHelper.initTableInfo(assistant, OrderItem.class);
        TableInfoHelper.initTableInfo(assistant, AppointmentWorkOrder.class);
    }

    /** 通过构造器装配协作组件，准备实付100元、已退20元且占用两个名额的预约订单。 */
    @BeforeEach
    void setUp() {
        workOrders = mock(AppointmentWorkOrderMapper.class);
        extensions = mock(AppointmentOrderExtMapper.class);
        orders = mock(OrderMapper.class);
        orderItems = mock(OrderItemService.class);
        slots = mock(AppointmentSlotStockSupport.class);
        verification = mock(AppointmentVerificationSupport.class);
        support = new AppointmentAfterSaleSupport(workOrders, mock(AppointmentVerificationCodeMapper.class),
                extensions, orders, mock(OrderLifecycleSupport.class), orderItems,
                mock(AppointmentNoticeSupport.class), slots, verification, mock(AppointmentConfigSupport.class));
        order = new Order();
        order.setSn("order");
        order.setOrderType(OrderTypeEnum.APPOINTMENT.name());
        order.setOrderStatus(OrderStatusEnum.PAID.name());
        order.setFlowPrice(100D);
        extension = new AppointmentOrderExt();
        extension.setRefundedAmount(20D);
        extension.setSlotStockId("slot");
        extension.setQuantity(2);
        when(orders.selectForUpdateBySn("order")).thenReturn(order);
        when(extensions.selectOne(any(Wrapper.class))).thenReturn(extension);
    }

    /** 验证部分退款只累计已退金额，不取消订单、回补名额或作废核销码。 */
    @Test
    void partialRefundPreservesFulfillment() {
        support.onRefundSuccess("order", 30D);

        assertThat(extension.getRefundedAmount()).isEqualTo(50D);
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatusEnum.PAID.name());
        verify(extensions).updateById(extension);
        verifyNoInteractions(slots, verification, workOrders, orderItems);
    }

    /** 验证累计退款达到实付金额时关单，回补完整名额并将工单标记为已退款。 */
    @Test
    void fullRefundClosesOrderAndRestoresResources() {
        support.onRefundSuccess("order", 80D);

        assertThat(extension.getRefundedAmount()).isEqualTo(100D);
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatusEnum.CANCELLED.name());
        verify(extensions).updateById(extension);
        verify(orders).updateById(order);
        verify(slots).restoreSlotStock("slot", 2);
        verify(verification).voidVerificationCodes("order");
        ArgumentCaptor<LambdaUpdateWrapper<AppointmentWorkOrder>> status = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(workOrders).update(isNull(), status.capture());
        status.getValue().getSqlSet();
        assertThat(status.getValue().getParamNameValuePairs().values())
                .contains(AppointmentWorkOrderStatusEnum.REFUNDED.name());
        verify(orderItems).update(any(Wrapper.class));
    }
}
