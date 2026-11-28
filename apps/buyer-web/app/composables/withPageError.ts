import { pageError } from '~/utils/error-view'

/**
 * 服务端渲染页的 useAsyncData 取数函数包一层：失败时抛可信的页面错误（404 / 403 / 503）。
 * 生产环境 Nuxt 会把取数里抛出的普通异常脱敏成不带原因的 500，不包这一层错误页分不清找不到与服务不可用。
 * 页面里 `if (error.value) throw error.value`。
 */
export function withPageError<T>(handler: () => Promise<T>) {
  return () => handler().catch((error: unknown) => {
    throw createError({ ...pageError(error), fatal: true })
  })
}
