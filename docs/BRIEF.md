# BRIEF（短基线）

> 产品意图草稿见 `docs/project.md`。选型与边界拍板后，把稳定口径收敛进本文件，并保持短小。

## 文档地图

| 文档 | 用途 |
|------|------|
| `project.md` | 立项草稿 / 功能意向 |
| `upstream.md` | 上游 Lilishop 来源、版本、改动清单、已知上游问题与升级方法 |
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

## 工程方法

见 `.cursor/rules/cross-project-dev.mdc` 与知识库《跨项目开工约定》。
