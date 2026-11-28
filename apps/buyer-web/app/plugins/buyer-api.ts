import { createBuyerApi } from '~/utils/buyer-api'
import { createSessionStore, memoryStorage } from '~/utils/session'
import { detectTerminal } from '~/utils/terminal'

// 服务端渲染的页面只读公开接口，会话为空；登录态只在浏览器 localStorage
export default defineNuxtPlugin(() => {
  const config = useRuntimeConfig()
  const session = createSessionStore(import.meta.client ? window.localStorage : memoryStorage())
  const userAgent = import.meta.client ? navigator.userAgent : useRequestHeaders(['user-agent'])['user-agent']
  const terminal = detectTerminal(userAgent)
  const api = createBuyerApi({
    baseURL: config.public.buyerApiUrl,
    session,
    onAuthExpired: () => {
      useState('member').value = null
      const route = useRoute()
      if (route.path !== '/login') navigateTo({ path: '/login', query: { redirect: route.fullPath } })
    },
  })
  return { provide: { buyerApi: api, session, terminal } }
})
