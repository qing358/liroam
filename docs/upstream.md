# 上游登记与改动清单

liroam 的商城壳是 Lilishop（北京宏业汇成，Apache-2.0）。本文是上游来源、版本、许可证与「对上游代码的每一处改动」的唯一登记处。只在阶段边界合并上游 tag；合并后跑全量契约测试与主路径冒烟。

## 上游来源

| 仓内目录 | 上游仓 | 版本 | 引入方式 | 许可证 |
|----------|--------|------|----------|--------|
| `shop/lilishop/` | https://gitee.com/beijing_hongye_huicheng/lilishop | tag `v5.0.6`（`fb42ac9f`） | `git subtree --squash` | Apache-2.0（`shop/lilishop/LICENSE`） |
| `shop/lilishop-ui/` | https://gitee.com/beijing_hongye_huicheng/lilishop-ui | tag `v5.0.6`（`934c9d93`） | `git subtree --squash` | Apache-2.0（`shop/lilishop-ui/LICENSE`） |
| `shop/docker/`（摘取） | https://gitee.com/beijing_hongye_huicheng/docker | master `a849d8b6`（2026-11-17） | 按文件摘取 | Apache-2.0（`shop/docker/LICENSE`） |

- `lilishop-uniapp`（H5/小程序/App）暂不引入；需要移动端时同样按 tag subtree 到 `shop/lilishop-uniapp/`。
- docker 仓不整仓 subtree：仓里带 52MB 的 `docker-compose` 二进制和 ES 插件 jar，与本仓无关且会永久进历史。只摘 `LICENSE`、`init/mysql/lilishop.sql`、`config/my.cnf`、`scripts/dev-env-lib.mjs`（`scripts/dev.mjs` 复用其中 `renderPcConfig`）。
- docker 仓没有 `v5.0.6` tag；`a849d8b6` 是不晚于后端 v5.0.6（2026-11-18）的最新提交，其 `lilishop.sql` 已含后端 `DB/update-sql` 至 2026-11-17 的全部表与列（抽奖、积分券 `published` 等已逐项核对）。

## 升级上游

```bash
git subtree pull --prefix=shop/lilishop https://gitee.com/beijing_hongye_huicheng/lilishop.git <tag> --squash
git subtree pull --prefix=shop/lilishop-ui https://gitee.com/beijing_hongye_huicheng/lilishop-ui.git <tag> --squash
```

docker 摘取文件：在上游 docker 仓选不晚于后端 tag 的提交，覆盖上表四个文件并更新本表提交号。git-subtree 自动生成的 squash 提交标题已改为中文，`git-subtree-dir` / `git-subtree-split` 两行元数据保留，`subtree pull` 依赖它们。

## 对上游代码的改动清单

每行：挂接点、文件、原因、上游合并时注意什么。未列出的上游文件一律不改。

| 挂接点 | 文件 | 原因 | 合并注意 |
|--------|------|------|----------|
| 前端运行时配置 | `shop/lilishop-ui/{buyer,seller,manager}/public/index.html` | 开发模式在 `config.js` 之后加载 `config.local.js`，由 `scripts/dev.mjs` 按 `deploy/ports.env` 生成，API 地址不写死在上游文件。上游自家 `dev-env.mjs` 也生成 `config.local.js`，但 v5.0.6 的 `index.html` 没有加载它 | 上游若改 `config.js` 那一行，重新补这一行 |

## 已知上游问题（本仓绕开方式）

| 问题 | 绕开 |
|------|------|
| docker 仓整目录挂到 `/docker-entrypoint-initdb.d` 时，`999_apply_patches.sh` 按文件名排在 `lilishop.sql` 之前执行；`patches/` 在空库和新基线上都不幂等（`li_distribution` 不存在、`category_id` 列冲突），初始化中断 | `deploy/compose.yaml` 只挂完整基线 `lilishop.sql`；补丁只用于升级旧库，不摘取 |
| lilishop-ui v5.0.6 的 `manager/pnpm-lock.yaml` 与 `package.json` 不同步（锁里仍是 vuex），`--frozen-lockfile` 失败；`seller` 用 npm 安装报 ERESOLVE | 跟上游 `dev-env.mjs` 一致用 `npm install`；有上游 `yarn.lock` 的 `seller` 用 `yarn --frozen-lockfile` |
| 上游 `dev-env.mjs` 写死 3306/6379 与容器名 `mysql`/`redis`、jar 名 `*-4.3.jar`、强依赖 uniapp | 不直接运行，由 `scripts/dev.mjs` 按本仓端口登记编排 |
| 基线 `lilishop.sql` 的 `li_verification_source` 为空，三端登录滑块报「服务器繁忙」（`Random.nextInt(0)`）；`loginBypassEnabled` 只跳过服务端校验，前端登录页仍先要滑块 | `deploy/dev-seed.sql` 补 docker 仓 `561997f` 的滑块素材（本机演示数据）；`health` 检查滑块可生成 |
| 根 `pom.xml` 默认 `skipTests=true`，上游测试无人维护：v5.0.6 打开后 framework 932 个用例 4 失败 41 错误（字段改名、mock 未注入）；manager-api 的 `FullDiscountTest` 等是整上下文测试，Redisson 直连 `localhost:6379`、MySQL 指向上游局域网地址 | CI 与 README 测试命令沿用默认，只编译不跑上游测试；本机不要加 `-DskipTests=false` 跑上游测试（会写入本机其它项目的 Redis）。自研模块的测试在 `modules/` 内自管 |
| 余额支付 `WalletPlugin.callBack`：扣款 `MemberWalletServiceImpl.reduce` 是独立事务先提交，`paymentService.success` 抛非 `ServiceException`（如支付事务内同步监听器的数据库异常）时只进外层 `catch (Exception)`，不返还余额，且对外报「余额不足」。2026-11-22 本机实测：订单回滚为未付款，余额少 7 元 | 未改上游。挂在支付事务内的监听器失败须抛 `ServiceException`（或修此处补偿后登记本表）；P1 设计据此约束 |
