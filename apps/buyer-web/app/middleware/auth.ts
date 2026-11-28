// 需登录的页面在 definePageMeta({ middleware: 'auth' }) 声明；这些页面只在浏览器渲染（nuxt.config routeRules）
export default defineNuxtRouteMiddleware((to) => {
  if (import.meta.server) return
  if (!useNuxtApp().$session.tokens()) return navigateTo({ path: '/login', query: { redirect: to.fullPath } })
})
