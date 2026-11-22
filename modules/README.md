# modules（自研）

多卡台账、供应商适配、渠道、佣金、定价等自研 Maven 模块，不放进 `shop/` 上游目录。设计口径见 domain-design（§0 不变式、§4 表、§5 挂接点、§6 SPI、§7 状态机、§14 契约测试）。

| 模块 | 内容 | 依赖 |
|------|------|------|
| `liroam-common` | 敏感字段 AES-256-GCM + HMAC（`SensitiveCipher`，密钥 `LIROAM_DATA_KEY_V1..`/`LIROAM_DATA_HASH_KEY`）、`@Simulated` 生产闸、状态机、UTC 列绑定、运营告警汇总（`OpsAlertSource` → `/manager/liroam/ops/alerts`，worker 进程定时 WARN 日志）；test-jar：`ItDatabase`（只连本项目 MySQL） | — |
| `liroam-schema` | Flyway 自有表 `db/migration/liroam`（`lr_` 前缀，历史表 `lr_flyway_schema_history`）与口径体检、只增不改检查（`AdditiveMigrationsTest`）；`R__liroam_menu.sql` 往壳 `li_menu` 写管理端「旅行卡」菜单（登记见 `docs/upstream.md`） | common |
| `liroam-supplier-spi` | 供应商适配契约、四态结果、能力声明；test-jar：`SupplierAdapterContractTest`（CT-SUP-01~08） | 纯 Java |
| `liroam-catalog` | SKU 绑定（形态 `CardForm`、实名闸 `KycGateMode`、含号码闸）、供应商目录、适配器注册；启用供应商缺适配器即启动失败 | common、spi、schema |
| `liroam-supplier-mock` | 两家模拟供应商 `mock-shape-a`（池模式、MB）/ `mock-shape-b`（下单即建、字节、回调），仅 dev/test profile；`MockFaults` 故障注入（`failNext` + `liroam.mock.supplier.rules`）、`MockUpstreamConsole` 推卡状态 | spi、common |
| `liroam-supplier-quadcell` | 真供应商适配器（Open API v1.3.3，JDK `HttpClient`）：池卡开订阅、查档（MB→字节、+08:00 时间）、加包、停复机/终止、按卡状态查档；错误码表 → 四态（写操作 5xx 记未知走查档，不盲重试）；有 `LIROAM_SUPPLIER_QUADCELL_APIKEY` 才装配，Bean 名 `quadcell`；test-jar 只发布按文档报文回放的 HTTP 桩 `QuadcellStubUpstream` | spi |
| `liroam-fulfillment` | 唯一履约入口 `FulfillmentEntry.accept` 与 `FulfillmentRules`；拆卡 worker、指令执行（限流/查档/转人工）、卡池、交码三通道（实体卡录物流为 SHIPMENT 交码）、实名（`CardKyc` 自报/核验）、轮询镜像（流量/语音/短信用量）、卡投影与查询（流量/语音/短信、实名、物流、软卡指引）、物流轨迹端口 `CourierTracking`（`logistics.tracking_source`；模拟快递在 `..fulfillment.courier.mock..`，只 dev/test）、`CardActions`、加包意图、履约告警；买家 `/buyer/liroam/cards`、后台 `/manager/liroam/cards`；节拍需 `liroam.worker.enabled=true` | catalog |
| `liroam-pricing` | 资费表导入（xlsx → 区域/资费行/卡产品）、使用区域文案、汇率同步、成本与售价版本、调价建议、调价报表 `PriceReport` 与批量调价 `BatchRepricing`、订单行快照、后台「旅行卡商品」`CatalogAdmin`（`/manager/liroam/catalog`；壳 SKU 查询走端口 `ShellSkuDirectory`）、汇率过期/调价待确认/调价积压告警；卡产品「开卖软卡」；售价回写走端口 `ShellPriceWriter`（H7，实现在 shell-bridge）；模拟汇率源在 `..pricing.mock..` | catalog |
| `liroam-refund` | 退款最小闭环（§9）：`RefundRules`（版本化，无规则即需审核；dev 默认 `liroam/dev-seed/refund-rules.sql`）、`RefundCases`（冻结 → 审批 → 卡动作 → 完成）、`CommissionReversal` 端口（默认只记审计）；买家 `POST /buyer/liroam/cards/{id}/refund`、后台 `/manager/liroam/refunds`、`/manager/liroam/refund-rules`；卡写入只经 `CardActions` | fulfillment |
| `liroam-commission` | 分销佣金（§10）：`CommissionPolicies`（版本化策略与底价；模式即 `CommissionStrategy` Bean，可加）、`CommissionAccruals`（交码 / 完成后计提，按 `lr_commission_accrual` 幂等）、`CommissionLedger`（台账 `lr_commission_entry`、余额投影 `lr_agent_balance`、T+N 到期结算、实现退款模块的 `CommissionReversal` 按比例冲回）、`Withdrawals`（收款信息密文、审批、打款登记）、负余额与提现积压告警；分销员目录走端口 `AgentDirectory`（实现在 shell-bridge）；买家 `/buyer/liroam/agent/**`、后台 `/manager/liroam/commission/**`；节拍需 `liroam.worker.enabled=true` | fulfillment、refund |
| `liroam-ops` | 运营：到期提醒（`lr_notification`，可选短信端口 `MemberSms`）、日对账 `ReconRuns`（支付 `PaymentReconciliation` 读端口 `ShellPayments`；供应商 `SupplierReconciliation` 对 `lr_supplier_command` 成功指令的成本版本）、经营报表 `OpsReports`、对账缺失/差异告警；买家 `/buyer/liroam/notifications`、后台 `/manager/liroam/{reports,recon}/**` | fulfillment |
| `liroam-channel` | 外部卖场渠道（§8）：`ChannelAdapter` SPI（拉单 / 回传 / 日对账单）与注册（启用渠道缺适配器即启动失败）；幂等拉单（游标 `channel.pull_cursor` 在 `lr_setting`，回看重叠 10 分钟）→ SKU 映射 → `FulfillmentEntry.accept`（CHANNEL 行 + 订单行快照，不建壳订单）；未映射 / 拒单转人工（`ManualTasks`）可重放；`CredentialDelivered` 后回传，指数退避重试，超次数转人工；`CHANNEL_DAILY` 日对账（缺单 / 多单 / 金额差 → 差异 + `RECON_DIFF` 待办 + 订单对账标记）；买家按订单号 + 绑定手机号认领（卡属主只写一次，经 `CardActions`；手机号经端口 `MemberPhones`）；后台 `/manager/liroam/channels/**`、买家 `POST /buyer/liroam/channel-orders/claim`；节拍需 `liroam.worker.enabled=true` | fulfillment、pricing |
| `liroam-channel-jd` | 渠道适配器模块（厂商名只在此）；现只有模拟实现 `..channel.adapter.jd.mock`（`mock-jd`，`@Simulated`，只 dev/test profile）与 dev 控制台 `/manager/liroam/dev/channel-mock/**`（造单、改/删对账单、回传故障注入）；真开放平台适配器待资质与密钥 | channel |
| `liroam-shell-bridge` | 唯一允许依赖 `cn.lili` 的模块：H1 支付监听、H1′ 补偿扫描、H2 下单校验、H5 发货分流 `ShellShipping`（多运单 MANUAL/SPLIT）、壳物流插件轨迹适配 `ShellCourierTracking`、H7 售价写回、`ShellGoodsSkus`、会员手机号 `ShellMemberPhones`（渠道认领）、分销员目录 `ShellAgentDirectory`（H9 只读）、已支付订单 `ShellPaidOrders`（支付日对账） | fulfillment、channel、commission、ops |
| `liroam-architecture-tests` | ArchUnit 规则与厂商名源码扫描（新增模块须加为它的依赖） | 全部 |
| `liroam-smoke-tests` | 只有测试：P2 主路径冒烟（domain-design §15 V3–V7、V10），真 `mock-shape-a`/`mock-shape-b` + 各模块自动配置（test profile）走唯一履约入口 → worker 出卡 → 交码 → 壳发货回写端口（记录型假实现）→ 卡投影 → 加包；P3 另有 `QuadcellPathSmokeTest`（真 Quadcell 适配器 + HTTP 桩，写后超时查档、MySQL 令牌桶、429 退避、实名卡核验后交付）；P4 `CardTypesSmokeTest`（纯流量 / 语音短信 / 实名 / 实体卡各一单）；进 CI 命令。不依赖壳 | fulfillment、supplier-mock、supplier-quadcell（均 test） |

约定：

- 与商城壳只经 `liroam-shell-bridge` 挂接，改动登记在 `docs/upstream.md`。
- 供应商、渠道等可换家的外部能力：核心用领域代号（`SUP_A`…），厂商名只出现在 `liroam-supplier-<厂商>` 这类适配器模块里；模拟实现放 `..supplier.mock..` 并标 `@Simulated`，生产 profile（`liroam.safety.production-profiles`，默认 `prod,staging`）装配到即启动失败。
- 新供应商接入 = 适配器 + 套餐映射配置 + 继承 `SupplierAdapterContractTest` 的子类通过。
- 自有表金额 `BIGINT` 最小单位、币种由列名后缀表示（`_fen` 人民币分、`_hkd_minor` 港币分；跨币种表带 `currency` 列）；单价 `DECIMAL(18,6)`、汇率 `DECIMAL(18,10)`；时间 `DATETIME(3)` 存 UTC（读写经 `UtcColumns`），上游原值另存 `_raw` 列。
