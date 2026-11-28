// 轻游新买家站。约定见 docs/BRIEF.md「新买家站」与计划 K7：
// 目的地页服务端渲染并短时缓存；结算、支付、我的卡、订单、账户只在浏览器渲染（依赖本地会话）。
export default defineNuxtConfig({
  modules: ['@nuxt/ui', '@nuxt/eslint', '@nuxt/test-utils/module'],

  devtools: { enabled: false },

  app: {
    head: {
      htmlAttrs: { lang: 'zh-CN' },
      title: '轻游 Liroam',
      titleTemplate: '%s · 轻游',
      meta: [
        { name: 'viewport', content: 'width=device-width, initial-scale=1, viewport-fit=cover' },
        { name: 'description', content: '轻游 Liroam：出境旅行通信卡，按目的地选 eSIM 与实体卡，下单即可安装，用量与到期随时可查。' },
      ],
      link: [{ rel: 'icon', href: '/favicon.ico' }],
    },
  },

  // ~ 指 app/；品牌令牌与上游四端共用同一份 theme.css
  css: ['~/../../../shop/lilishop-ui/brand/theme.css', '~/assets/css/main.css'],

  // 只有浅色主题：关闭 Nuxt UI 的明暗切换
  ui: { colorMode: false },

  // 图标只用本地 @iconify-json/lucide，不请求远程图标服务
  icon: { serverBundle: 'local', clientBundle: { scan: true }, provider: 'none', fallbackToApi: false },

  // 不加载任何远程字体，走 main.css 的系统字体栈
  fonts: { providers: { google: false, googleicons: false, bunny: false, fontshare: false, fontsource: false, adobe: false } },

  runtimeConfig: {
    public: {
      /** buyer-api 基址；本机由 scripts/dev.mjs 按 deploy/ports.env 注入 NUXT_PUBLIC_BUYER_API_URL */
      buyerApiUrl: 'http://localhost:39888',
      /** 微信内 JSAPI 支付（需公众号网页授权），材料到位前关闭：NUXT_PUBLIC_WECHAT_JSAPI_ENABLED */
      wechatJsapiEnabled: false,
    },
  },

  routeRules: {
    '/': { swr: 60 },
    '/esim/**': { swr: 300 },
    '/help/**': { swr: 300 },
    '/login': { ssr: false },
    '/checkout/**': { ssr: false },
    '/pay/**': { ssr: false },
    '/payDone': { ssr: false },
    '/cards/**': { ssr: false },
    '/orders/**': { ssr: false },
    '/account/**': { ssr: false },
  },

  compatibilityDate: '2025-07-15',

  telemetry: false,
})
