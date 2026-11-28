import type { H3Event } from 'h3'
import { createBuyerApi } from '~/utils/buyer-api'
import { createSessionStore, memoryStorage } from '~/utils/session'

/** 服务端路由用的 buyer-api 客户端：只读公开接口，不带会话；基址同页面（NUXT_PUBLIC_BUYER_API_URL） */
export const publicBuyerApi = (event: H3Event) =>
  createBuyerApi({ baseURL: useRuntimeConfig(event).public.buyerApiUrl, session: createSessionStore(memoryStorage()) })
