# 上线检查清单

首次上生产（P4 门禁）前逐项核对，结论与核对人写在行尾。来源：P1/P2 裁决（Project `docs/domain-design.md` §16–17）。

| # | 项 | 怎么核 |
|---|----|--------|
| 1 | 覆盖 `JASYPT_ENCRYPTOR_PASSWORD`（上游默认 `lili`） | 生产密钥管理里有该变量；现无 `ENC(` 密文依赖默认口令（README 密钥表） |
| 2 | 生产库账号能建触发器 | DBA 确认 `log_bin_trust_function_creators=1` 或账号有 `TRIGGER`+`SUPER`；空库跑一遍 Flyway，`lr_flyway_schema_history` 全部 success |
| 3 | 生产 profile 不装模拟实现 | 不激活 `dev`/`test`；启动日志无 `mock-shape-*`、`MockFxRateSource`（装上即启动失败，`ProductionSafetyGate`） |
| 4 | 每个启用供应商都有真适配器、过契约套件 | `SupplierAdapterContractTest` 对真适配器全绿；`lr_supplier.adapter_key` 不含 `mock-` |
| 5 | 汇率来源为真来源 | 后台「定价与汇率」`fxSource` 不是 mock；同步任务有成功记录 |
| 6 | 退款规则为客户确认值 | `lr_refund_rule` 不是 dev 默认规则（`liroam/dev-seed/refund-rules.sql` 只给 dev/test） |
| 7 | 演示数据未进生产库 | 生产库无 `SUP_A`/`SUP_B` 供应商、无 `li_goods.id` 落在 `9100000000000000001–9100000000000009999` |
| 8 | 引用壳列宽 | `ShellReferenceLengthTest` 绿（壳 ID / 单号生成长度 ≤ 64） |
| 9 | 自有接口挂载面 | buyer-api 只注册 `/buyer/liroam/**`，manager-api 只注册 `/manager/liroam/**`（`liroam.web.surface`） |
| 10 | worker 只在一个进程开 | 仅 manager-api 设 `LIROAM_WORKER_ENABLED=true` |
