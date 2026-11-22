package com.liroam.shell.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;

import com.liroam.fulfillment.FulfillmentRejected;

import cn.lili.common.enums.ResultCode;
import cn.lili.common.exception.ServiceException;
import cn.lili.modules.order.cart.entity.dto.TradeDTO;
import cn.lili.modules.order.order.entity.vo.OrderVO;
import cn.lili.modules.order.order.event.TradeIntoDbEvent;

/**
 * H2：{@code OrderCreateSupport.intoDB} 在 {@code TradeServiceImpl.createTrade} 事务内同步发布 {@link TradeIntoDbEvent}；
 * 这里抛 {@link ServiceException} 即整笔交易回滚、下单失败，提示原样回到前端。
 */
public class TradeIntoDbListener {

    private static final Logger log = LoggerFactory.getLogger(TradeIntoDbListener.class);

    private final TradeOrderPreparation preparation;

    public TradeIntoDbListener(TradeOrderPreparation preparation) {
        this.preparation = preparation;
    }

    @EventListener
    public void onTradeIntoDb(TradeIntoDbEvent event) {
        TradeDTO trade = event.getTradeDTO();
        if (trade == null || trade.getOrderVO() == null) {
            return;
        }
        for (OrderVO order : trade.getOrderVO()) {
            try {
                preparation.prepare(order.getSn(), trade.getMemberId(), order.getOrderType());
            } catch (ServiceException e) {
                throw e;
            } catch (FulfillmentRejected e) {
                log.info("订单[{}]旅行卡下单被拒：{}", order.getSn(), e.getMessage());
                throw new ServiceException(ResultCode.ERROR, e.reason().userMessage());
            } catch (RuntimeException e) {
                log.error("订单[{}]旅行卡下单校验失败", order.getSn(), e);
                throw new ServiceException(ResultCode.ERROR, "旅行卡下单失败，请稍后重试");
            }
        }
    }
}
