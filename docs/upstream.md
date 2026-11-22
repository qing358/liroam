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
| H1 / H1′（装配） | `shop/lilishop/{manager-api,buyer-api}/pom.xml` | 各加一个依赖 `com.liroam:liroam-shell-bridge`（版本写死 `0.1.0-SNAPSHOT`）。挂接代码全在 `modules/liroam-shell-bridge`，经 Spring Boot 自动配置加载（上游 `scanBasePackages = "cn.lili"` 扫不到 `com.liroam`），不改任何上游 Java；自有表由 Flyway 用独立历史表 `lr_flyway_schema_history` 迁移。仓根 `pom.xml` 聚合上游与 `modules/`，必须从仓根构建 | 上游若改这两个 pom 的 `<dependencies>`，重新补这一项；合并后跑 `modules/liroam-shell-bridge` 的 CT-SHELL-01（`OrderPaidEvent` 构造/`getOrder()`、`payOrder` 仍 `@Transactional` 且同步发布）与 `WalletPlugin` 退款用例 |
| 买家「我的卡」路由 | `shop/lilishop-ui/buyer/src/router/index.js` | `/home` 子路由追加 `MyTravelCards`（`LiroamMyCards`）、`TravelCardDetail`（`LiroamCardDetail`），组件在自有目录 `src/pages/liroam/` | 上游改 `/home` children 时保留这两项；路由名不可与上游重名 |
| 买家会员中心菜单 | `shop/lilishop-ui/buyer/src/pages/user/menu.js` | 「订单中心」组「我的订单」后加「我的卡」一项（`path` = 路由名 `LiroamMyCards`，图标复用已注册的 `Goods`） | 上游若重排菜单数组，重新插入这一项 |
| 商品详情「使用区域说明」 | `shop/lilishop-ui/buyer/src/pages/GoodsDetail.vue` | `ShowGoods` 之后挂自有组件 `LiroamUsageRegion`（`:sku-id="goodsMsg.data.id"`）并加一行 import；SKU 切换时上游重取 `goodsMsg`，组件按 skuId 重新拉 `/buyer/goods/liroam/usage-region`，无说明不渲染 | 上游若改详情页结构，保持挂在商品主信息与「商品介绍」之间、仍能拿到当前 skuId |
| 管理端「旅行卡」菜单（数据，非代码） | 壳表 `li_menu`，由 `modules/liroam-schema/.../R__liroam_menu.sql` 写入 | 管理端菜单来自库表；固定 id 号段 `9100000000000000100–199`，`front_route` 指向 `manager/src/views/liroam/**`（上游 `lazyLoading` 用 `require.context` 自动发现，管理端无上游文件改动）。只写 `li_menu`，不写 `li_role_menu`（超管自动可见，其它角色由运营授权）；库里没有 `li_menu` 时跳过 | 上游 `update-sql` 若新增菜单 id 落入本号段（极不可能）需改号；上游改 `li_menu` 列时同步本脚本 |
| H2 / H5 / H7（不改上游文件，登记合并注意） | `modules/liroam-shell-bridge` 的 `order/TradeIntoDbListener`、`order/ShellShipping`、`goods/ShellSkuPriceWriter` | H2 同步监听 `TradeIntoDbEvent`（`OrderCreateSupport.intoDB` 在 `createTrade` 事务内发布，抛 `ServiceException` 即整笔下单回滚）：校验可售、绑定加包选卡意图、写订单行快照；旅行卡只许普通订单（`orderType=NORMAL`），核销型虚拟订单（`VIRTUAL`）直接拒单。H5 整单旅行卡全部交码后调 `OrderService.fulfillOrder`：全是 eSIM/软卡用 `NO_DELIVERY`；含实体卡且同一运单用 `WHOLE` 带运营录入的物流（`li_logistics.id` + 运单号）；实体卡多运单、缺物流或混有其它商品转人工待办。H7 售价版本激活时写 `li_goods_sku.price` 并刷新 SKU 缓存 | 上游合并后复核 `TradeIntoDbEvent` 仍在下单事务内同步发布、`Order.setTradeType` 对 CART/BUY_NOW 仍给 `NORMAL`、`fulfillOrder` 的 `NO_DELIVERY` / `WHOLE` 语义（一次发完剩余行、`WHOLE` 校验 `li_logistics`）；跑 shell-bridge 全部用例 |
| 自有接口挂载面 | `shop/lilishop/{buyer-api,manager-api}/src/main/resources/application.yml` | 文件末尾各加 `liroam.web.surface: buyer` / `manager`，决定该进程注册哪一侧自有接口（`/buyer/liroam/**` 或 `/manager/liroam/**`，见 `liroam-common` 的 `WebSurface`） | 上游改这两个 yml 时保留末尾这一段 |
| 商品详情加购方式 | `shop/lilishop-ui/buyer/src/components/goodsDetail/ShowGoods.vue` | 旅行卡（`/buyer/goods/liroam/usage-region` 有结果的 SKU）即使是 `VIRTUAL_GOODS` 也显示「加入购物车」，立即购买用 `BUY_NOW`（`utils/liroam.js` 的 `checkoutCartType`），落普通订单；上游原逻辑把 `VIRTUAL_GOODS` 一律走 `cartType=VIRTUAL`（核销型虚拟订单，付款后进「待核销」，无法由交码发货收尾，也不能和其它商品同车）。其它虚拟商品行为不变 | 上游改 `startCheckout` 或加购按钮条件时保留旅行卡分支 |
| 前端运行时配置 | `shop/lilishop-ui/{buyer,seller,manager}/public/index.html` | 开发模式在 `config.js` 之后加载 `config.local.js`，由 `scripts/dev.mjs` 按 `deploy/ports.env` 生成，API 地址不写死在上游文件。上游自家 `dev-env.mjs` 也生成 `config.local.js`，但 v5.0.6 的 `index.html` 没有加载它 | 上游若改 `config.js` 那一行，重新补这一行 |

## 已知上游问题（本仓绕开方式）

| 问题 | 绕开 |
|------|------|
| docker 仓整目录挂到 `/docker-entrypoint-initdb.d` 时，`999_apply_patches.sh` 按文件名排在 `lilishop.sql` 之前执行；`patches/` 在空库和新基线上都不幂等（`li_distribution` 不存在、`category_id` 列冲突），初始化中断 | `deploy/compose.yaml` 只挂完整基线 `lilishop.sql`；补丁只用于升级旧库，不摘取 |
| lilishop-ui v5.0.6 的 `manager/pnpm-lock.yaml` 与 `package.json` 不同步（锁里仍是 vuex），`--frozen-lockfile` 失败；`seller` 用 npm 安装报 ERESOLVE | 跟上游 `dev-env.mjs` 一致用 `npm install`；有上游 `yarn.lock` 的 `seller` 用 `yarn --frozen-lockfile` |
| 上游 `dev-env.mjs` 写死 3306/6379 与容器名 `mysql`/`redis`、jar 名 `*-4.3.jar`、强依赖 uniapp | 不直接运行，由 `scripts/dev.mjs` 按本仓端口登记编排 |
| 基线 `lilishop.sql` 的 `li_verification_source` 为空，三端登录滑块报「服务器繁忙」（`Random.nextInt(0)`）；`loginBypassEnabled` 只跳过服务端校验，前端登录页仍先要滑块 | `deploy/dev-seed.sql` 补 docker 仓 `561997f` 的滑块素材（本机演示数据）；`health` 检查滑块可生成 |
| 根 `pom.xml` 默认 `skipTests=true`，上游测试无人维护：v5.0.6 打开后 framework 932 个用例 4 失败 41 错误（字段改名、mock 未注入）；manager-api 的 `FullDiscountTest` 等是整上下文测试，Redisson 直连 `localhost:6379`、MySQL 指向上游局域网地址 | CI 与 README 测试命令沿用默认，只编译不跑上游测试；本机不要加 `-DskipTests=false` 跑上游测试（会写入本机其它项目的 Redis）。自研模块不继承该属性，其测试随仓根 `verify` 必跑，只连 `deploy/ports.env` 登记的本项目 MySQL（拒绝 3306） |
| 余额支付 `WalletPlugin.callBack`：扣款 `MemberWalletServiceImpl.reduce` 是独立事务先提交，`paymentService.success` 抛非 `ServiceException`（如支付事务内同步监听器的数据库异常）时只进外层 `catch (Exception)`，不返还余额，且对外报「余额不足」。2026-11-22 本机实测：订单回滚为未付款，余额少 7 元 | 未改上游。`OrderPaidFulfillmentListener` 把一切失败包成 `ServiceException`；`WalletRefundOnFulfillmentFailureTest` 用真 `WalletPlugin` 证明收单失败时余额退回，并保留一条「非 ServiceException 不退款」的基线用例，上游修复后该用例会红，届时更新本行 |
