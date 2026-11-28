# 轻游新买家站（apps/buyer-web）

Nuxt 4 + Nuxt UI 4，移动优先、目的地页服务端渲染。选型与许可证见 `docs/BRIEF.md`「新买家站」；设计稿、路由与组件对应见 `design/README.md`。

## 运行

```bash
node scripts/dev.mjs up --ui=buyer-web          # 仓根执行：按 deploy/ports.env 在 39010 起开发服务器，API 指向本机 buyer-api 39888
```

worktree 里单独看页面时，端口取 `LIROAM_WORKTREE_UI_PORTS` 段，后端连集成负责人那套，不另起：

```bash
cd apps/buyer-web && npm ci
NUXT_PUBLIC_BUYER_API_URL=http://localhost:39888 npx nuxt dev --port 39101
```

## 检查（CI 同样五步）

```bash
npm run lint && npm run typecheck && npm test && npm run build && npm run test:ssr
```

`test:ssr` 起 `.output` 构建产物与假 buyer-api（K3 夹具 `test/fixtures/k3.ts`），端口取 39100-39199，断言首页、全部目的地、目的地页、套餐详情的内容在 HTML 源码里。

## 约定（计划 K7）

| 事项 | 唯一位置 |
|------|----------|
| buyer-api 基址、微信内 JSAPI 支付开关、支付结果逐张开卡进度开关（`NUXT_PUBLIC_CARDS_BY_ORDER`，K4 合入前关） | `nuxt.config.ts` 的 `runtimeConfig.public`，运行时用 `NUXT_PUBLIC_BUYER_API_URL` / `NUXT_PUBLIC_WECHAT_JSAPI_ENABLED` 覆盖 |
| 会话键（accessToken / refreshToken / uuid） | `app/utils/session.ts` |
| 终端类型、壳 clientType、每种支付方式的调起方式 | `app/utils/terminal.ts` |
| 请求、拆包、续期 | `app/utils/buyer-api.ts`；接口按领域放 `app/services/` |
| 渲染方式 | `nuxt.config.ts` 的 `routeRules`：首页、`/esim/**`、`/help/**` SSR + swr；登录、结算、支付、我的卡、订单、账户纯客户端 |
| 颜色 | 只用 `shop/lilishop-ui/brand/theme.css` 的令牌（`app/assets/css/main.css` 映射到 Nuxt UI 变量）；品牌闸 `node --test scripts/brand.test.mjs` 拦写死色值与 Tailwind 色板类 |

需登录的页面在 `definePageMeta` 里声明 `middleware: 'auth'`。立即购买入口：`/checkout?skuId=<壳 SKU id>&num=<数量>[&code=<目的地代码>]`（带 `code` 时从 K3 取套餐展示与实名须知）。下单与详情页设 `definePageMeta({ hideTabs: true })` 并用 `BackBar`，手机端不出底部标签栏。
