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
- 壳发货分流 `ShellShipping`：纯 eSIM/软卡 → 无物流发货；实体卡同一运单 → 壳 `WHOLE` 发货；多运单、缺物流、含非旅行卡商品 → 人工待办。
- 后台「旅行卡商品」（`/manager/liroam/catalog`）配卡产品含号码、套餐模板、套餐映射、SKU 绑定与闸、上下架；绑定形状由映射推导，形态须与壳商品类型一致。
- 生产基线：运营告警 `/manager/liroam/ops/alerts`（各模块 `OpsAlertSource`，阈值 `alert.*`，worker 进程每 5 分钟 WARN 日志）；版本迁移只增不改（`AdditiveMigrationsTest`）；`dev.mjs backup` / `restore-drill`。支付不改壳：本机余额支付，真沙箱待商户号（上线清单）。

## 工程方法

见 `.cursor/rules/cross-project-dev.mdc` 与知识库《跨项目开工约定》。
