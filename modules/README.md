# modules（自研）

多卡台账、供应商适配、渠道、佣金、定价等自研 Maven 模块放这里（P1 起），不放进 `shop/` 上游目录。

- 与商城壳只经稳定挂接点交互（如订单支付领域事件 `OrderPaidEvent`、Redis 队列 `lili:mq:queue:lili_order_topic`），挂接点改动登记在 `docs/upstream.md`。
- 供应商、渠道等可换家的外部能力：核心用领域词，厂商协议只留在适配层。
