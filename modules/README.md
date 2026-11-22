# modules（自研）

多卡台账、供应商适配、渠道、佣金、定价等自研 Maven 模块，不放进 `shop/` 上游目录。设计口径见 domain-design（§0 不变式、§4 表、§5 挂接点、§6 SPI、§7 状态机、§14 契约测试）。

| 模块 | 内容 | 依赖 |
|------|------|------|
| `liroam-common` | 敏感字段 AES-256-GCM + HMAC（`SensitiveCipher`，密钥 `LIROAM_DATA_KEY_V1..`/`LIROAM_DATA_HASH_KEY`）、`@Simulated` 生产闸、状态机、UTC 列绑定；test-jar：`ItDatabase`（只连本项目 MySQL） | — |
| `liroam-schema` | Flyway 自有表 `db/migration/liroam`（`lr_` 前缀，历史表 `lr_flyway_schema_history`）与口径体检 | common |
| `liroam-supplier-spi` | 供应商适配契约、四态结果、能力声明；test-jar：`SupplierAdapterContractTest`（CT-SUP-01~08） | 纯 Java |
| `liroam-catalog` | SKU 绑定、供应商目录、适配器注册；启用供应商缺适配器即启动失败 | common、spi、schema |
| `liroam-fulfillment` | 唯一履约入口 `FulfillmentEntry.accept`、`FulfillmentRules`、卡/套餐/指令/履约行状态机 | catalog |
| `liroam-shell-bridge` | 唯一允许依赖 `cn.lili` 的模块：H1 支付监听、H1′ 补偿扫描 | fulfillment |
| `liroam-architecture-tests` | ArchUnit 规则与厂商名源码扫描（新增模块须加为它的依赖） | 全部 |

约定：

- 与商城壳只经 `liroam-shell-bridge` 挂接，改动登记在 `docs/upstream.md`。
- 供应商、渠道等可换家的外部能力：核心用领域代号（`SUP_A`…），厂商名只出现在 `liroam-supplier-<厂商>` 这类适配器模块里；模拟实现放 `..supplier.mock..` 并标 `@Simulated`，生产 profile（`liroam.safety.production-profiles`，默认 `prod,staging`）装配到即启动失败。
- 新供应商接入 = 适配器 + 套餐映射配置 + 继承 `SupplierAdapterContractTest` 的子类通过。
- 自有表金额 `BIGINT` 最小单位、币种由列名后缀表示（`_fen` 人民币分、`_hkd_minor` 港币分；跨币种表带 `currency` 列）；单价 `DECIMAL(18,6)`、汇率 `DECIMAL(18,10)`；时间 `DATETIME(3)` 存 UTC（读写经 `UtcColumns`），上游原值另存 `_raw` 列。
