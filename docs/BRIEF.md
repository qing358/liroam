# BRIEF（短基线）

> 产品意图草稿见 `docs/project.md`。选型与边界拍板后，把稳定口径收敛进本文件，并保持短小。

## 文档地图

| 文档 | 用途 |
|------|------|
| `project.md` | 立项草稿 / 功能意向 |
| `upstream.md` | 上游 Lilishop 来源、版本、改动清单、已知上游问题与升级方法 |
| `launch-checklist.md` | 首次上生产前逐项核对（P4 门禁） |
| `../README.md` | 目录、本机一键启停、端口、演示数据、密钥变量名、测试命令 |
| `../AGENTS.md` | Agent 强制约束 |
| Cursor Project `preferences.md` | 跨项目开发偏好 |

## 工程基线（P0 已落地）

- 单仓：`shop/` 为 Lilishop v5.0.6（后端 + 前端 subtree，docker 仓摘取文件），`modules/` 为自研 Maven 模块（P1 起）。
- 栈：Java 21 / Spring Boot 4 / MyBatis-Plus；MySQL 8.0.30、Redis 7.0.4（本机专用 compose，端口唯一登记 `deploy/ports.env`，39xxx 段）。
- 验证：`node scripts/dev.mjs health`；测试见 README「测试」，CI 为 `.github/workflows/ci.yml`。

## 领域骨架（P1 已落地）

- 仓根 `pom.xml` 聚合 `shop/lilishop` 与 `modules/`；模块清单与存储口径见 `modules/README.md`。
- 履约触发：支付事务内同步监听 `OrderPaidEvent` 只幂等落 `lr_fulfillment_line`（失败一律 `ServiceException`，保证余额支付退款）＋ manager-api 每分钟补偿扫描；不用壳 Redis 队列。
- 卡资产只经 `FulfillmentEntry.accept` 写入；SKU 上架锁、卡属主只写一次、卡不换供应商由 DB 触发器兜底。
- 供应商只经 `SupplierAdapter` + 能力声明接入，过 `SupplierAdapterContractTest`；生产 profile 装到 `@Simulated` 实现即启动失败。架构规则在 `modules/liroam-architecture-tests`。

## Demo 竖切（P2 已落地，mock 上游）

- 模块：`liroam-supplier-mock`（两种形状模拟供应商，只 dev/test）、`liroam-pricing`（资费导入、使用区域、汇率、成本/售价版本、调价建议、订单行快照）、`liroam-fulfillment` worker（拆卡、指令、限流、池卡、交码、轮询、卡投影）、`liroam-refund`（退款最小闭环）。
- 壳挂接：H2 下单校验与快照、H5 交码后无物流发货、H7 售价写回，登记在 `upstream.md`。旅行卡只走普通订单（`NORMAL`）：前端加购用 `BUY_NOW`/购物车，后端拒绝核销型虚拟订单。
- 演示：`node scripts/dev.mjs demo` 建两家供应商目录与 SKU（README「演示数据」）；上线前必做项见 `launch-checklist.md`。

## 真供应商（P3 已落地）

- `liroam-supplier-quadcell`：按 Open API v1.3.3 实现 `SupplierAdapter`（池卡开订阅 / 查档 / 加包 / 停复机 / 终止；无回调、无幂等键、无交码接口，交码走导入或人工）。只有配了 `LIROAM_SUPPLIER_QUADCELL_APIKEY` 才装配；供应商把 `lr_supplier.adapter_key` 设为 `quadcell` 即切换，履约链路不变。
- 验证：契约套件 + 按文档报文回放的 HTTP 桩（无密钥可跑）；真沙箱测试有密钥才跑。接入参数与待供应商确认项见 README「供应商接入」。

## 主路径全卡型（P4 已落地，mock 上游）

- 卡型：纯流量 / 语音短信（投影出分钟、条数，无能力为空）/ 实名（`KycGateMode` NONE·PROMPT·BLOCK_DELIVERY；买家自报或后台核验后自动交付）/ 实体卡（后台录物流 = SHIPMENT 交码，同一 `CredentialDelivered`）。含号码 SKU 受全局开关 `sell.numbered_sku_enabled` 控制（默认关）。
- 壳发货分流 `ShellShipping`：纯 eSIM/软卡 → 无物流发货；实体卡同一运单 → 壳 `WHOLE` 发货；多运单按开关 `shipping.multi_waybill_mode`（默认 MANUAL 转人工，SPLIT 按运单分包发货）；缺物流、含非旅行卡商品 → 人工待办。
- 后台「旅行卡商品」（`/manager/liroam/catalog`）配卡产品含号码、套餐模板、套餐映射、SKU 绑定与闸、上下架；绑定形状由映射推导，形态须与壳商品类型一致。
- 生产基线：运营告警 `/manager/liroam/ops/alerts`（各模块 `OpsAlertSource`，阈值 `alert.*`，worker 进程每 5 分钟 WARN 日志）；版本迁移只增不改（`AdditiveMigrationsTest`）；`dev.mjs backup` / `restore-drill`。支付不改壳：本机余额支付，真沙箱待商户号（上线清单）。

## 渠道（P5 已落地，mock 渠道）

- `liroam-channel`：渠道只经 `ChannelAdapter`（拉单 / 回传 / 日对账单）接入，`lr_channel.adapter_key` 选实现；渠道单走唯一履约入口（CHANNEL 行，不建壳订单），交码后回传渠道，`CHANNEL_DAILY` 日对账；买家凭订单号 + 绑定手机号认领到「我的卡」。后台「渠道」菜单（`li_menu` id 170–172）。
- `liroam-channel-jd`：现只有模拟适配器 `mock-jd`（dev/test）；`node scripts/dev.mjs demo` 建渠道 `JD` 与一条 SKU 映射。真开放平台适配器待资质、AppKey/Secret 与 SKU 映射表。渠道退款 / 取消（§9.4）未做；买家不能在本站对渠道卡整卡申请退款（「我的卡」不出按钮、接口拒绝），在本站买的加包照常可退。

## 运营闭环（P5 已落地，mock 渠道 / 假支付对账单）

- `liroam-commission`：佣金策略版本化（`PAID_RATE` 实付抽成 / `FLOOR_DIFF` 底价差价 / `NONE`；SKU > 代理等级 > 全局；两级分成、T+N 结算、提现是否审批），比例只在后台配置。自建台账 `lr_commission_entry`（不用壳分销商品），分销员与订单归属只读壳 `li_distribution` / `li_order.distribution_id`（H9）。交码后计提，到期转可提现；退款按比例冲回（已结算可为负，后续抵扣）；提现 = 申请 → 审批 → 线下打款登记凭证。买家「我的佣金」、后台「佣金」菜单（`li_menu` 160–162）。
- `liroam-ops`：卡到期站内提醒（阈值 `notify.expiry_remind_before`，买家「我的卡」顶部提示；短信端口 `MemberSms` 未接，开关默认关）；日对账共用 `lr_recon_run` / `lr_recon_diff`（支付日对账 = 上传支付渠道 CSV 对壳已支付订单，供应商日对账 = 上传账单对成功开卡/加包成本，渠道日对账见下节），差异可标已处理/忽略；按日经营报表；后台「报表与对账」菜单（`li_menu` 180–182）。真实对账单格式【待核实】。

## 卡型与履约扩展（P6 已落地，mock 上游 / mock 快递）

- 语音短信用量：SPI `PlanUsage` 带语音秒数、短信条数总量与已用；套餐「用完」= 所有有额度的维度都用尽；买家「我的卡」与后台台账出「已用 / 总量」。
- 实体卡物流轨迹：端口 `CourierTracking`（按 `logistics.tracking_source` 选来源，默认 `shell` = 壳物流插件快递鸟/快递100；`mock` 只 dev/test）；买家卡详情与后台台账看轨迹，查不到给「凭运单号到快递官网查」兜底。后台台账「发货设置」改多运单开关与轨迹来源。
- 纯软卡：资费表「纯软件卡 Profile」卡费解析为 SOFT；卡产品默认不卖软卡，运营在「旅行卡商品」按卡产品「开卖软卡」（资费表无软卡卡费不能开、有在售软卡 SKU 不能关）；资费表注明不支持软卡的区域拒绝建软卡 SKU；买家端交码弹窗出软卡写卡指引。
- 调价：后台「调价」页 = 调价报表（售价 / 最近成本 / 毛利率 / 最低毛利 / 待确认建议 + 当前汇率）、建议批量确认 / 驳回（逐条独立，部分失败列原因）、按涨跌幅或目标毛利率批量改价（先预览，执行同口径重算，任一 SKU 低于最低毛利整批拒绝）；有待确认建议即出告警 `REPRICE_PENDING`。调价开关默认仍 `MANUAL_CONFIRM`。

## 工程方法

见 `.cursor/rules/cross-project-dev.mdc` 与知识库《跨项目开工约定》。
