import { describe, expect, it } from 'vitest'
import { FEATURE_DISABLED_CODE, errorView, pageError } from '~/utils/error-view'
import { ApiError } from '~/utils/buyer-api'

describe('错误页文案（设计稿 21）', () => {
  it('404：页面不存在，回到首页', () => {
    expect(errorView({ statusCode: 404 })).toEqual({ kind: 'notFound', icon: 'i-lucide-info', title: '页面不存在', text: '链接可能已失效', action: '回到首页' })
  })

  it('功能关闭（K1 错误码 91001）：该功能暂未开放，回到首页', () => {
    expect(FEATURE_DISABLED_CODE).toBe(91001)
    expect(errorView({ statusCode: 403, data: { code: 91001 } })).toMatchObject({ kind: 'closed', icon: 'i-lucide-lock', title: '该功能暂未开放', text: '可以先看看其它服务', action: '回到首页' })
  })

  it('只有文案像、没带 K1 错误码的不算功能关闭', () => {
    expect(errorView({ statusCode: 500, message: '该功能暂未开放' }).kind).toBe('retry')
  })

  it('其它错误：网络不太稳定，重试', () => {
    for (const statusCode of [500, 502, 503]) {
      expect(errorView({ statusCode, message: 'boom' })).toMatchObject({ kind: 'retry', icon: 'i-lucide-globe', title: '网络不太稳定', text: '请检查网络后重试', action: '重试' })
    }
  })
})

describe('服务端渲染页取数失败转成的页面错误', () => {
  it('壳回业务失败（找不到、已下架）是 404，带壳的提示', () => {
    expect(pageError(new ApiError('这个目的地暂时没有在售套餐', 400, 200))).toEqual({ statusCode: 404, statusMessage: '这个目的地暂时没有在售套餐' })
  })

  it('连不上 buyer-api 或其 5xx 是 503，不当成页面不存在', () => {
    expect(pageError(new ApiError('网络连接失败，请稍后再试', 'NETWORK')).statusCode).toBe(503)
    expect(pageError(new ApiError('服务暂时不可用，请稍后再试', 502, 502)).statusCode).toBe(503)
  })

  it('K1 关闭的功能（HTTP 200、success:false、code 91001）是 403，错误码随错误带到错误页', () => {
    expect(pageError(new ApiError('该功能暂未开放', 91001, 200))).toEqual({ statusCode: 403, statusMessage: '该功能暂未开放', data: { code: 91001 } })
  })

  it('不是 buyer-api 的异常（代码缺陷）按 503 可重试，不吞成 404', () => {
    expect(pageError(new TypeError('x is undefined')).statusCode).toBe(503)
  })
})
