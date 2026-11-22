# 上线检查清单

首次上生产（P4 门禁）前逐项核对，结论与核对人写在行尾。来源：P1/P2 裁决（Project `docs/domain-design.md` §16–17）、P4 生产基线（delivery-master-plan §3.6）。

| # | 项 | 怎么核 |
|---|----|--------|
| 1 | 覆盖 `JASYPT_ENCRYPTOR_PASSWORD`（上游默认 `lili`） | 生产密钥管理里有该变量；现无 `ENC(` 密文依赖默认口令（README 密钥表） |
| 2 | 生产库账号能建触发器 | DBA 确认 `log_bin_trust_function_creators=1`（开 binlog 时建触发器必需）或账号有 `TRIGGER`+`SUPER`；空库跑一遍 Flyway，`lr_flyway_schema_history` 全部 success |
| 3 | 生产 profile 不装模拟实现 | 不激活 `dev`/`test`；启动日志无 `mock-shape-*`、`MockFxRateSource`（装上即启动失败，`ProductionSafetyGate`） |
| 4 | 每个启用供应商都有真适配器、过契约套件 | `SupplierAdapterContractTest` 对真适配器全绿；`lr_supplier.adapter_key` 不含 `mock-` |
| 5 | 汇率来源为真来源 | 后台「定价与汇率」`fxSource` 不是 mock；同步任务有成功记录 |
| 6 | 退款规则为客户确认值 | `lr_refund_rule` 不是 dev 默认规则（`liroam/dev-seed/refund-rules.sql` 只给 dev/test） |
| 7 | 演示数据未进生产库 | 生产库无 `SUP_A`/`SUP_B` 供应商、无 `li_goods.id` 落在 `9100000000000000001–9100000000000009999` |
| 8 | 引用壳列宽 | `ShellReferenceLengthTest` 绿（壳 ID / 单号生成长度 ≤ 64） |
| 9 | 自有接口挂载面 | buyer-api 只注册 `/buyer/liroam/**`，manager-api 只注册 `/manager/liroam/**`（`liroam.web.surface`） |
| 10 | worker 只在一个进程开 | 仅 manager-api 设 `LIROAM_WORKER_ENABLED=true` |
| 11 | 支付走真商户号沙箱 | 微信/支付宝商户号与证书进生产密钥管理；沙箱各付一单 → 回调 → `lr_fulfillment_line` 落行 → 交付 → 壳发货；回调失败告警由壳支付日志 + 补偿扫描兜底（本机只验证过余额支付） |
| 12 | 运营告警接入值班 | 后台「运营告警」为空或已知；日志平台按 WARN 行关键字 `运维告警[` 配告警；阈值 `alert.*`（默认：重试 ≥3 次、限流 20 次/时、人工待办 20 条或 24 小时、轮询滞后 30 分、回写失败 3 次、调价建议积压 24 小时）按业务量复核 |
| 13 | 备份与恢复演练 | 生产：全量备份 + binlog 增量，保留期写明；上线前在隔离库做一次恢复演练（口径同 `node scripts/dev.mjs restore-drill`：逐表行数、触发器数一致），记录用时 |
| 14 | 回滚预案 | 版本迁移只增不改（`AdditiveMigrationsTest` 绿），上一版应用可直接跑在新库上；回滚 = 换回上一版镜像，不回滚库；删列/改型须拆两次发布并单独评审 |
| 15 | 含号码卡开关 | `sell.numbered_sku_enabled` 维持关闭，除非已确认号码卡的合规与供应商实名流程 |
| 16 | 实体卡物流公司 | 壳「物流公司」里启用实际合作的快递（实体卡录物流从这里选）；店铺发货地址已配 |
