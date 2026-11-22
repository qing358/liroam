package com.liroam.fulfillment;

/**
 * 壳订单发货回写端口（挂接点 H5）：一张壳订单的全部旅行卡行完成后调用。实现在 {@code liroam-shell-bridge}（调壳 {@code fulfillOrder}）。
 * <p>
 * 由 {@code FulfillmentWorker} 在事务外调用；必须幂等（同一订单可能被重复调用）。失败直接抛异常，
 * 待回写标记留在 {@code lr_shell_delivery}，下一轮按退避重试。
 */
public interface ShellOrderDelivery {

    void markDelivered(String liOrderSn);
}
