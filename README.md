# 轻游（liroam）

电商 + 多卡资产/用量台。商城壳为 Lilishop（`shop/`，上游登记见 [docs/upstream.md](docs/upstream.md)），多卡台账、供应商适配、渠道、佣金、定价为自研模块（`modules/`）。基线见 [docs/BRIEF.md](docs/BRIEF.md)。

## 目录

| 目录 | 内容 |
|------|------|
| `shop/lilishop/` | Lilishop 后端（subtree，Java 21 / Spring Boot 4） |
| `shop/lilishop-ui/` | 管理端 / 商家端 / 买家 PC / IM 前端（subtree） |
| `shop/docker/` | 上游 docker 仓摘取：初始化 SQL、`my.cnf`、前端配置渲染库 |
| `modules/` | 自研 Maven 模块（P1 起） |
| `deploy/` | 本机专用中间件 compose 与端口登记 `ports.env` |
| `scripts/` | 本机一键编排 `dev.mjs` |

## 本机一键启停

前置：Docker（compose v2）、Node 18+、Maven、JDK 21。JDK 21 不改本机默认 JDK：仓内 `.sdkmanrc` 指定 `java=21-openjdk`（sdkman 本地候选，指向 apt 包 `openjdk-21-jdk-headless`），脚本自动使用；手工命令前在仓根执行 `sdk env`。也可用 `LIROAM_JAVA_HOME` 指定。

```bash
node scripts/dev.mjs up          # 起中间件 → 首次构建后端/安装前端依赖 → 起 2 个后端 + 3 个前端，全部就绪才返回
node scripts/dev.mjs up --build  # 后端代码有改动时强制重新构建
node scripts/dev.mjs up --ui=manager,buyer  # 按需只起列出的前端（manager / seller / buyer）；--ui=none 只起后端
node scripts/dev.mjs health      # 健康检查（前端只查本次 up 起过的），全部通过退出码 0，否则 1
node scripts/dev.mjs status      # 地址与进程
node scripts/dev.mjs down        # 停进程与容器，保留数据卷
node scripts/dev.mjs backup      # 本机库逻辑全量备份到 temp/backups/lilishop-<UTC时间>.sql.gz（含触发器）
node scripts/dev.mjs restore-drill [文件]  # 恢复演练：默认取最新备份，恢复到临时库 lilishop_restore_drill，逐表行数 + 触发器数比对后删除临时库
node scripts/dev.mjs restore <文件> --yes  # 数据回滚：后端须先停；先自动备份当前库，再用该备份整库重建 lilishop 并逐表核对
node scripts/dev.mjs stop-backends         # 只停两个后端（等进程退出），前端与容器不动
node scripts/dev.mjs up --ui=none --backend-from=<checkout>  # 应用回滚：用另一份已构建 checkout（上一版）的 jar 起后端
node scripts/dev.mjs rotate-data-key       # 数据密钥轮换演练：本机 .env 追加下一版本 LIROAM_DATA_KEY_Vn 并停后端，再 up 起来后到「运维概览」重加密
node scripts/dev.mjs retire-data-key <n>   # 下线旧版本：经后台接口确认该版本已无数据且非当前版本，才从本机 .env 删除并停后端
```

首次 `up` 会在仓根生成 `.env`（本机随机口令，不入库，权限 600）。重建空库：`docker compose --env-file deploy/ports.env --env-file .env -f deploy/compose.yaml down -v` 后再 `up`。日志在 `.run/logs/`。

## 端口（唯一登记：`deploy/ports.env`）

| 服务 | 地址 |
|------|------|
| 平台管理端 | http://localhost:39003 |
| 商家端 | http://localhost:39002 |
| 买家 PC 端 | http://localhost:39000 |
| manager-api（含商家端接口、Redis 队列消费者） | http://localhost:39887 |
| buyer-api | http://localhost:39888 |
| MySQL 8.0.30（库 `lilishop`） | 127.0.0.1:39306 |
| Redis 7.0.4 | 127.0.0.1:39379 |

中间件只绑 127.0.0.1，compose 项目名 `liroam`；不复用、不停本机其它项目的 3306 / 6379 等服务。改端口只改 `ports.env`。前端写入的 API 地址用 `localhost`：Windows 侧浏览器访问 WSL2 时只有 `localhost` 会转发到 Java 的监听。

## 演示数据

- 上游基线 SQL（`shop/docker/init/mysql/lilishop.sql`，只在空数据卷首次启动时导入）：已删上游样例数据（店铺、会员、商品、分类、品牌、店铺装修、示例文章等，口径见 `docs/upstream.md` 改动清单），只留运行必需数据；平台管理端 `admin` / `123456`。没有任何店铺与会员，商家端账号由 `demo` 建（见下）。
- `deploy/dev-seed.sql`（每次 `up` 幂等执行，只针对本机 liroam 库，不进任何正式迁移）：补登录滑块素材（上游基线为空，否则三端登录页滑块无法生成；用本仓 `deploy/dev-assets/verification/`，由 `up` 起的 manager-api 以 `http://localhost:<manager-api 端口>/static/**` 对外，后台「验证码资源」与后端生成滑块都按 http 读取；地址为空或为本地文件地址时才改写）；部署地址与联合登录 / 支付宝回调仍为空时填本机买家 PC 与 buyer-api 地址（取自 `deploy/ports.env`，由 `dev.mjs` 作为会话变量注入）；打开上游的本机验收开关 `loginBypassEnabled`，服务端跳过 LOGIN 滑块校验，便于接口脚本直接登录。
- 旅行卡演示目录：`up` 之后执行 `node scripts/dev.mjs demo`（幂等，可重复）。写 `deploy/dev-demo.sql`：演示自营店「演示自营店」与店主（商家端登录 `liroam_demo_seller` / `111111`）、三级演示分类，两家模拟供应商 `SUP_A`（`mock-shape-a`，池卡 + 导入/人工交码）与 `SUP_B`（`mock-shape-b`，下单即建、接口交码）、套餐模板、9 个壳商品及旅行卡 SKU 绑定（A：日本/中国内地各一张新卡 + 日本加油包；B：日本新卡 + 加油包、日本语音短信卡、香港实名后交付卡、日本实体卡〔实物商品、包邮，后台卡台账「录入物流」交付〕、日本纯软卡）；再经后台接口为两家各导入并启用 `docs/media` 里的资费表、给 B 的卡产品 `45412_C`「开卖软卡」（之后第二遍才绑上软卡 SKU）、把物流轨迹来源切到模拟快递 `mock`（录物流后按寄出时长出揽收 → 运输 → 派送 → 签收）、同步模拟汇率、逐个手工定价（写回壳售价）、给 A 导 20 张池卡。各卡型的商品页入口在命令输出末尾。最后写 `deploy/dev-demo-channel.sql`：模拟渠道 `JD`（`mock-jd`）与 `XHS`（`mock-xhs`，小红书形状），均启用、拉单间隔 1m、买家可看用量，映射 `JD-SKU-1001` / `XHS-SKU-1001` → A 的日本纯流量 SKU；在后台「渠道 › 渠道订单」选渠道后展开「模拟渠道」造单 → 立即拉单（或等 worker）即出卡、回传，买家用下单手机号绑定的账号在「我的卡 › 认领渠道订单」认领。模拟适配器只在 dev/test 装配。最后写 `deploy/dev-demo-commission.sql`：演示买家 `liroam_p0_buyer`（需先自建，如后台「会员列表」添加）设为已审核分销员（邀请人取库里另一个已审核分销员，没有则只有一级）并锁客到自己名下，再经后台接口建演示佣金策略 `DEFAULT`（实付 10%，一级 80% / 二级 20%，交码即可提现、提现需审批）；买家下单交码后在「会员中心 › 我的佣金」申请提现，后台「佣金 › 佣金与提现」审批并登记打款。
- 买家端：在买家 PC 端「立即注册」自建账号。基线 `SMS_SETTING.isTestModel=true`，短信不真发，验证码固定 `111111`（上游演示约定）。

## 密钥与环境变量（只写变量名）

| 变量 | 用途 | 本机来源 | 生产 |
|------|------|----------|------|
| `LIROAM_MYSQL_ROOT_PASSWORD` | MySQL root 口令；后端经 `SPRING_DATASOURCE_PASSWORD` 使用 | `.env` 自动生成 | 密钥管理注入，按环境独立 |
| `LIROAM_REDIS_PASSWORD` | Redis 口令；后端经 `SPRING_DATA_REDIS_PASSWORD` 使用 | `.env` 自动生成 | 同上 |
| `LILI_JWT_SECRET` | 上游 JWT 签名密钥（上游 yml 有默认值，必须覆盖） | `.env` 自动生成 | 同上 |
| `LIROAM_DATA_KEY_V1` | 敏感字段（ICCID、交码内容等）AES-256-GCM 密钥，32 字节 base64；轮换时新增 `_V2`（最大版本加密，旧版本仍可解），「运维概览」重加密完后旧版本可下线（`docs/ops-runbook.md`） | `.env` 自动生成 | 密钥管理注入；缺失或格式不对后端启动失败 |
| `LIROAM_DATA_HASH_KEY` | 敏感字段查重用 HMAC-SHA256 密钥，32 字节 base64；不随加密密钥轮换（换了会使唯一索引失效） | `.env` 自动生成 | 同上 |
| `LIROAM_WORKER_ENABLED` | 自研定时任务（支付补偿扫描等）开关 | `scripts/dev.mjs` 只给 manager-api 设 `true` | 只在一个进程组开 |
| `JASYPT_ENCRYPTOR_PASSWORD` | 上游 jasypt 解密口令（上游 yml 默认 `lili`） | 未覆盖，用上游默认 | 建议覆盖。2026-11-22 核实：上游配置、SQL、docker 摘取文件中没有任何 `ENC(` 值，`JasyptUtil` 只在其自带 `main` 里用，现无依赖默认口令的密文 |
| 支付、短信、OSS、快递等三方密钥 | 上游存于库表 `li_setting`，由管理端配置 | 未配置 | 按环境在管理端录入，不进仓库 |

| `LIROAM_SUPPLIER_QUADCELL_APIKEY` | Quadcell Open API 的 `X-api-key`；不设则不装配该适配器（启用了 `adapter_key=quadcell` 的供应商会让启动失败） | 未配置 | 密钥管理注入 |
| `LIROAM_SUPPLIER_QUADCELL_BASEURL` | 接口根地址，默认官方 `https://mvne-api.quadcell.com:8443/open-api/1.0`；拿到沙箱地址时覆盖 | 默认值 | 按环境 |
| `LIROAM_SUPPLIER_QUADCELL_DEFAULTMODE` | 开订阅必填的 `mode`，SKU 模板 payload 未写时用它；两处都没有则开卡被拒（`BAD_PAYLOAD`） | 未配置 | 供应商确认后配置 |
| `LIROAM_SUPPLIER_QUADCELL_PROXYHOST` / `_PROXYPORT` | 出口代理（供应商按 IP 白名单放行时，经固定 IP 出口） | 未配置 | 按网络方案 |

京东 / 小红书渠道（真适配器未接，现只有 `mock-jd` / `mock-xhs`）、汇率来源的密钥变量名在对应模块接入时补到本表。

## 供应商接入（Quadcell）

1. 配好 `LIROAM_SUPPLIER_QUADCELL_APIKEY`（及必要的 base-url / 代理），出口 IP 进供应商白名单。
2. 后台把该供应商的 `adapter_key` 设为 `quadcell`；资费/套餐的供应商编码填 `packCode`。
3. SKU 套餐模板 payload 可写：开卡 `svcCode`、`mode`、`vDay`、`actwPack`，套餐 `pcrfCode`、`pcrfReset`、`autoAct`、`priority`（加包只认套餐字段）；未知字段或类型不对在本地拒绝，不调上游。
4. 池卡：导入供应商给的 Idle 卡；开订阅后上游卡状态为 Provisioned，交码走导入或人工（上游无交码接口）。
5. 其余可调项（`liroam.supplier.quadcell.*`）：`time-zone`（默认 `Asia/Hong_Kong`）、`bytes-per-mb`（默认 1048576，待供应商确认）、`connect-timeout` / `read-timeout`、`throttle-retry-after`。

真沙箱测试（`QuadcellLiveTest`）只在设了 `LIROAM_SUPPLIER_QUADCELL_APIKEY` 时跑：只读需 `LIROAM_QUADCELL_LIVE_ICCID`；写路径另需 `LIROAM_QUADCELL_LIVE_WRITES=true`、`LIROAM_QUADCELL_LIVE_IDLE_ICCID`、`LIROAM_QUADCELL_LIVE_PACK_CODE`，可选 `_TOPUP_PACK_CODE`、`_PAYLOAD`（`k=v;k=v`）、`_TERMINATE=true`（会把卡退回 Idle 并终止订阅，注意费用）。

```bash
mvn -B -o -pl modules/liroam-supplier-quadcell -am test -Dtest='QuadcellLiveTest' -Dsurefire.failIfNoSpecifiedTests=false
```

## 测试

```bash
node --test 'scripts/**/*.test.mjs'    # 编排脚本单测
(cd shop/lilishop-ui/buyer && node --test tests/*.test.cjs && npm run build)    # 买家端单测与构建（manager 同理）
# 仓根 reactor（JDK 21）：上游只编译（上游测试见下文 -Pupstream-tests）；自研模块单测、契约套件、集成测试、架构检查、主路径冒烟全跑
mvn -B -pl shop/lilishop/manager-api,shop/lilishop/buyer-api,modules/liroam-architecture-tests,modules/liroam-smoke-tests -am verify
```

自研集成测试只连 `deploy/ports.env` 登记的本项目 MySQL（`127.0.0.1:39306`，拒绝 3306），口令取环境变量 `LIROAM_MYSQL_ROOT_PASSWORD`，没有则读仓根 `.env`（所以先 `node scripts/dev.mjs up` 起中间件）；每个测试类重建自己的库 `liroam_it_<名称>`，不碰 `lilishop` 库。在 worktree 里跑时把主检出 `.env` 的口令导出为该变量。

上游模块测试默认 verify 不跑，`-DskipTests=false` 也打不开（上游根 pom 用 `lilishop.skipTests`）。上游模块测试（含放在上游模块里的自有测试，如 `DemoSiteDefaultTest`）由 profile 单独跑，CI 每次都跑；profile 已排除 v5.0.6 自带坏的 12 个测试类与直连本机 `localhost:6379` 的整上下文测试（清单见 `docs/upstream.md`「已知上游问题」），不连任何中间件：

```bash
mvn -B -pl shop/lilishop/framework,shop/lilishop/manager-api,shop/lilishop/buyer-api,shop/lilishop/im-api -am -Pupstream-tests -DskipTests test
```

`-DskipTests` 只跳过自研模块；不要再加 `-Dtest`，它会覆盖 profile 的排除清单。

运营告警看后台「旅行卡 › 运维 › 运营告警」（`/manager/liroam/ops/alerts`），容量 / 积压 / 数据密钥看「运维概览」；worker 进程每 5 分钟把当前告警逐条写 WARN 日志（`运维告警[级别][编码] ...`）。逐码处置见 [docs/ops-runbook.md](docs/ops-runbook.md)。

CI 同样两步，后端 job 先跑仓根 verify（带 MySQL 服务容器，映射到 39306）、再跑上面的 `-Pupstream-tests`，见 `.github/workflows/ci.yml`。
