import { SITE } from './app/utils/site'

// 轻游新买家站。约定见 docs/BRIEF.md「新买家站」与计划 K7：
// 目的地页服务端渲染并短时缓存；结算、支付、我的卡、订单、账户只在浏览器渲染（依赖本地会话）。
export default defineNuxtConfig({
  modules: ['@nuxt/ui', '@nuxt/eslint', '@nuxt/test-utils/module', '@nuxtjs/sitemap'],

  devtools: { enabled: false },

  app: {
    head: {
      htmlAttrs: { lang: 'zh-CN' },
      title: '轻游 Liroam',
      titleTemplate: SITE.titleTemplate,
      meta: [{ name: 'viewport', content: 'width=device-width, initial-scale=1, viewport-fit=cover' }],
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

  // 站点对外地址：canonical、分享卡片、站点地图、robots 的绝对地址都从这里来；部署时用 NUXT_SITE_URL 设置，未设置时按请求 Host
  site: { name: SITE.name },

  // 动态页（目的地、套餐、帮助文章）由 server/api/__sitemap__/urls.ts 列出；账户与下单页见下方 routeRules 的 sitemap: false
  sitemap: { sources: ['/api/__sitemap__/urls'] },

  runtimeConfig: {
    public: {
      /** buyer-api 基址；本机由 scripts/dev.mjs 按 deploy/ports.env 注入 NUXT_PUBLIC_BUYER_API_URL */
      buyerApiUrl: 'http://localhost:39888',
      /** 微信内 JSAPI 支付（需公众号网页授权），材料到位前关闭：NUXT_PUBLIC_WECHAT_JSAPI_ENABLED */
      wechatJsapiEnabled: false,
      /** 支付结果页逐张显示开卡进度，依赖 K4（/buyer/liroam/cards?orderSn=）；K4 合入前关闭，只按订单状态显示：NUXT_PUBLIC_CARDS_BY_ORDER */
      cardsByOrder: false,
      /** 帮助中心客服方式（运营配置，空则不显示该项）：NUXT_PUBLIC_SUPPORT_HOURS / NUXT_PUBLIC_SUPPORT_PHONE / NUXT_PUBLIC_SUPPORT_CHAT_URL */
      supportHours: '',
      supportPhone: '',
      supportChatUrl: '',
    },
  },

  routeRules: {
    '/': { swr: 60 },
    '/esim': { swr: 300 },
    '/esim/**': { swr: 300 },
    '/help': { swr: 300 },
    '/help/**': { swr: 300 },
    '/login': { ssr: false, sitemap: false },
    '/checkout/**': { ssr: false, sitemap: false },
    '/pay/**': { ssr: false, sitemap: false },
    '/payDone': { ssr: false, sitemap: false },
    '/cards/**': { ssr: false, sitemap: false },
    '/orders/**': { ssr: false, sitemap: false },
    '/account/**': { ssr: false, sitemap: false },
    '/robots.txt': { swr: 3600 },
  },

  compatibilityDate: '2025-07-15',

  telemetry: false,
})
