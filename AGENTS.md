# Agent 约定

本仓对编码 Agent 的强制约束。更完整的口径见团队知识库《跨项目开工约定》；冲突时以**本文件 + 仓库代码**为准。跨项目偏好摘要亦在 Cursor Project 的 `preferences.md` / `principles/`。

## 复用与官方路径
- 先搜本仓，再用标准库 / 框架自带 / 成熟库；最后才写薄胶水。
- 跟当前版官方脚手架与扩展点走；偏离写一句理由。
- 不编造 API；查不到写【待核实】。核许可证后再引入。

## SSOT
- 同一业务规则、开关、标签、排序、投影、路径/目录、登录态键只维护一处。
- 先搜再挂；禁止平行复制。改规则改唯一源头并更新调用方。
- 多份口径先收敛再加功能。≥2 处真实复用或改价已高再抽；不为单一内部调用方先做框架。

## 已知扩展轴
- 可换家的外部能力：即使只接 A，也先留边界；核心用领域词，厂商细节留在适配层。
- 不预支空想未来；也不把设计焊死在唯一厂商上。

## 工程品味
- 命名对齐领域词；跟随本仓风格；抽象要赚回复杂度；直白优于炫技。
- 不留死代码、半截抽象、散落的厂商分支。

## 交付与验证
- 计划一次给全，约定范围一次做完（代码、测试、旧文档同步）。
- 行为变更：红 → 绿；分支列全；交出当次命令与输出。主路径绿了不算完。
- 改旧文档；不写密钥。

## 协作默认
- 默认不开 PR：worktree 做完检查后合主分支；影响本机服务则重编重启。
- 未经用户明确要求：不 commit / push / 发版 / 写生产。
- 生产默认只读诊断；线上不原地改代码。

## 文档
- 默认先读本文件与短基线（`docs/BRIEF.md` 或 `docs/project.md`）；历史长文按需再开。

## 本项目
- 语言 / 框架：Java 21 / Spring Boot 4（Lilishop v5.0.6，`shop/`）；自研模块进 `modules/`；本机编排 Node（`scripts/`）；新买家站 Nuxt 4 + Nuxt UI 4（`apps/buyer-web/`，Node ^22.22.3 或 ^24.15）。
- 验证命令：`node --test 'scripts/**/*.test.mjs'`；仓根 `mvn -B -pl shop/lilishop/manager-api,shop/lilishop/buyer-api,modules/liroam-architecture-tests,modules/liroam-smoke-tests -am verify`（需 JDK 21；自研集成测试连本项目 MySQL 39306，口令取 `.env` 或 `LIROAM_MYSQL_ROOT_PASSWORD`；本机 mvn 与 `up --build` 等重活前缀 `node scripts/heavy.mjs`，按内存动态放行（最多 8 个并发），见 README「测试」）；新买家站在 `apps/buyer-web` 下 `npm run lint && npm run typecheck && npm test && npm run build && npm run test:ssr`（`build` 前缀 `node ../../scripts/heavy.mjs`；`test:ssr` 起构建产物与假 buyer-api 断言选卡页 HTML）；本机联调 `node scripts/dev.mjs health`。
- 品牌（常驻）：凡涉及品牌、主题色、公司名，一律用「轻游 / Liroam」「想象智能（济南）有限公司」与 `logo/README.md` 的标识和色板（前端主题令牌在 `shop/lilishop-ui/brand/`），不再沿用 Lilishop 的名称、logo、公司与主题红 / 蓝；新页面颜色取令牌或 Element 主题变量，不写死色值。`node --test scripts/brand.test.mjs` 拦上游品牌名与主色值。
- 其它红线：不碰本机其它项目的 3306 / 6379 等服务，端口只在 `deploy/ports.env` 登记；改上游代码须记入 `docs/upstream.md` 改动清单；演示数据只走 `deploy/dev-seed.sql`（`up`）与 `deploy/dev-demo*.sql`（`dev.mjs demo`），不进正式迁移。
