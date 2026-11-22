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
node scripts/dev.mjs health      # 健康检查，全部通过退出码 0，否则 1
node scripts/dev.mjs status      # 地址与进程
node scripts/dev.mjs down        # 停进程与容器，保留数据卷
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

- 上游基线 SQL（`shop/docker/init/mysql/lilishop.sql`，只在空数据卷首次启动时导入）：平台管理端 `admin` / `123456`；商家端 `13011111111` / `111111`（店铺「家家乐」）。
- `deploy/dev-seed.sql`（每次 `up` 幂等执行，只针对本机 liroam 库，不进任何正式迁移）：补登录滑块素材（上游基线为空，否则三端登录页滑块无法生成）；打开上游的本机验收开关 `loginBypassEnabled`，服务端跳过 LOGIN 滑块校验，便于接口脚本直接登录。
- 买家端：在买家 PC 端「立即注册」自建账号。基线 `SMS_SETTING.isTestModel=true`，短信不真发，验证码固定 `111111`（上游演示约定）。

## 密钥与环境变量（只写变量名）

| 变量 | 用途 | 本机来源 | 生产 |
|------|------|----------|------|
| `LIROAM_MYSQL_ROOT_PASSWORD` | MySQL root 口令；后端经 `SPRING_DATASOURCE_PASSWORD` 使用 | `.env` 自动生成 | 密钥管理注入，按环境独立 |
| `LIROAM_REDIS_PASSWORD` | Redis 口令；后端经 `SPRING_DATA_REDIS_PASSWORD` 使用 | `.env` 自动生成 | 同上 |
| `LILI_JWT_SECRET` | 上游 JWT 签名密钥（上游 yml 有默认值，必须覆盖） | `.env` 自动生成 | 同上 |
| `JASYPT_ENCRYPTOR_PASSWORD` | 上游 jasypt 解密口令（上游 yml 默认 `lili`） | 未覆盖，用上游默认 | 生产必须覆盖【待核实：上游配置中是否有 ENC() 值依赖默认口令】 |
| 支付、短信、OSS、快递等三方密钥 | 上游存于库表 `li_setting`，由管理端配置 | 未配置 | 按环境在管理端录入，不进仓库 |

供应商（Quadcell 等）、京东渠道、汇率来源的密钥变量名在对应模块接入时补到本表。

## 测试

```bash
node --test 'scripts/**/*.test.mjs'                                  # 编排脚本单测
(cd shop/lilishop && mvn -B -pl manager-api,buyer-api -am verify)    # 后端构建（JDK 21），含测试代码编译
```

上游根 pom 默认跳过测试，不要加 `-DskipTests=false` 跑上游测试：有过时失败，且整上下文测试会直连本机 `localhost:6379`（见 `docs/upstream.md`）。

CI 同样两步，见 `.github/workflows/ci.yml`。
