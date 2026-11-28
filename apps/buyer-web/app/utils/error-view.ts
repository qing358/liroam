// 错误页（设计稿 21）的状态与文案唯一出处；app/error.vue 只负责渲染，页面取数失败经 composables/withPageError.ts 转成这里的页面错误
import { ApiError } from './buyer-api'

/** K1 关掉的功能：壳 HTTP 200、success:false、code 91001（ApiResult.FEATURE_DISABLED，文案「该功能暂未开放」） */
export const FEATURE_DISABLED_CODE = 91001

export interface ErrorView {
  kind: 'notFound' | 'closed' | 'retry'
  icon: string
  title: string
  text: string
  action: string
}

export interface PageError {
  statusCode: 403 | 404 | 503
  statusMessage: string
  data?: { code: number }
}

export function errorView(error: { statusCode?: number, message?: string, data?: unknown }): ErrorView {
  if (error.statusCode === 404) return { kind: 'notFound', icon: 'i-lucide-info', title: '页面不存在', text: '链接可能已失效', action: '回到首页' }
  if ((error.data as { code?: unknown } | undefined)?.code === FEATURE_DISABLED_CODE) {
    return { kind: 'closed', icon: 'i-lucide-lock', title: '该功能暂未开放', text: '可以先看看其它服务', action: '回到首页' }
  }
  return { kind: 'retry', icon: 'i-lucide-globe', title: '网络不太稳定', text: '请检查网络后重试', action: '重试' }
}

/** 服务端渲染页取数失败：壳回业务失败（找不到、已下架）为 404；K1 关闭为 403；连不上 buyer-api、其 5xx 或其它异常为 503（可重试） */
export function pageError(error: unknown): PageError {
  if (error instanceof ApiError) {
    if (error.code === FEATURE_DISABLED_CODE) return { statusCode: 403, statusMessage: error.message, data: { code: FEATURE_DISABLED_CODE } }
    if (error.code !== 'NETWORK' && (error.status ?? 0) < 500) return { statusCode: 404, statusMessage: error.message }
  }
  return { statusCode: 503, statusMessage: '服务暂时不可用，请稍后再试' }
}
