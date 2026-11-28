import { describe, expect, it, vi } from 'vitest'
import { AUTH_EXPIRED, createBuyerApi, type ApiError } from '../../app/utils/buyer-api'
import { createSessionStore, memoryStorage } from '../../app/utils/session'
import { jwt } from './jwt'

const BASE = 'http://localhost:39888'
const valid = () => jwt(Math.floor(Date.now() / 1000) + 3600)
const expired = () => jwt(Math.floor(Date.now() / 1000) - 10)

interface Call { url: string, method: string, headers: Record<string, string>, body?: string }

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })
}

function setup(routes: (call: Call) => Response, tokens?: { accessToken: string, refreshToken: string }) {
  const session = createSessionStore(memoryStorage(), () => 'uuid-1')
  if (tokens) session.save(tokens)
  const calls: Call[] = []
  const fetch = vi.fn(async (url: string, init: RequestInit = {}) => {
    const call = { url, method: init.method ?? 'GET', headers: (init.headers ?? {}) as Record<string, string>, body: init.body as string | undefined }
    calls.push(call)
    return routes(call)
  })
  const onAuthExpired = vi.fn()
  const api = createBuyerApi({ baseURL: BASE, session, fetch: fetch as unknown as typeof globalThis.fetch, onAuthExpired })
  return { api, session, calls, onAuthExpired }
}

describe('buyer-api 客户端', () => {
  it('成功时拆出 result；每个请求带 uuid；数组查询参数重复键名', async () => {
    const { api, calls } = setup(() => json({ success: true, code: 200, result: { ok: 1 } }))
    await expect(api.get('/buyer/x', { query: { a: [1, 2], b: 'c', skip: undefined } })).resolves.toEqual({ ok: 1 })
    expect(calls[0]!.url).toBe(`${BASE}/buyer/x?a=1&a=2&b=c`)
    expect(calls[0]!.headers.uuid).toBe('uuid-1')
    expect(calls[0]!.headers.accessToken).toBeUndefined()
  })

  it('表单体按 x-www-form-urlencoded，JSON 体按 application/json', async () => {
    const { api, calls } = setup(() => json({ success: true, code: 200, result: null }))
    await api.post('/f', { form: { mobile: '13800000000', code: '111111' } })
    await api.post('/j', { json: { way: 'BUY_NOW' } })
    expect(calls[0]!.headers['Content-Type']).toBe('application/x-www-form-urlencoded')
    expect(calls[0]!.body).toBe('mobile=13800000000&code=111111')
    expect(calls[1]!.headers['Content-Type']).toBe('application/json')
    expect(calls[1]!.body).toBe('{"way":"BUY_NOW"}')
  })

  it('业务失败抛出壳的 message 与 code', async () => {
    const { api } = setup(() => json({ success: false, code: 21001, message: '库存不足' }))
    await expect(api.get('/x')).rejects.toMatchObject({ name: 'ApiError', message: '库存不足', code: 21001 })
  })

  it('网络失败与非 JSON 错误页给出可读错误', async () => {
    const down = setup(() => { throw new TypeError('fetch failed') })
    await expect(down.api.get('/x')).rejects.toMatchObject({ code: 'NETWORK' })
    const notFound = setup(() => new Response('<html>', { status: 404, headers: { 'content-type': 'text/html' } }))
    await expect(notFound.api.get('/x')).rejects.toMatchObject({ message: '请求的资源不存在', status: 404 })
  })

  it('需登录接口带有效 accessToken；可选登录接口令牌过期时匿名请求并清掉本地会话', async () => {
    const token = valid()
    const signedIn = setup(() => json({ success: true, code: 200, result: 1 }), { accessToken: token, refreshToken: 'r' })
    await signedIn.api.get('/me', { auth: 'required' })
    expect(signedIn.calls[0]!.headers.accessToken).toBe(token)

    const stale = setup(() => json({ success: true, code: 200, result: 1 }), { accessToken: expired(), refreshToken: 'r' })
    await stale.api.get('/goods', { auth: 'optional' })
    expect(stale.calls).toHaveLength(1)
    expect(stale.calls[0]!.headers.accessToken).toBeUndefined()
    expect(stale.session.tokens()).toBeNull()
  })

  it('令牌已过期时先用 refreshToken 续期再发请求，新令牌入会话', async () => {
    const fresh = valid()
    const { api, calls, session } = setup((c) => c.url.includes('/refresh/')
      ? json({ success: true, code: 200, result: { accessToken: fresh, refreshToken: 'r2' } })
      : json({ success: true, code: 200, result: 'ok' }), { accessToken: expired(), refreshToken: 'r1' })
    await expect(api.get('/me', { auth: 'required' })).resolves.toBe('ok')
    expect(calls.map((c) => c.url)).toEqual([`${BASE}/buyer/passport/member/refresh/r1`, `${BASE}/me`])
    expect(calls[1]!.headers.accessToken).toBe(fresh)
    expect(session.tokens()).toEqual({ accessToken: fresh, refreshToken: 'r2' })
  })

  it('壳回未登录（20004 或 HTTP 401）时续期一次并重试', async () => {
    const fresh = valid()
    let first = true
    const { api, calls } = setup((c) => {
      if (c.url.includes('/refresh/')) return json({ success: true, code: 200, result: { accessToken: fresh, refreshToken: 'r2' } })
      if (first) {
        first = false
        return json({ success: false, code: 20004, message: '用户未登录' })
      }
      return json({ success: true, code: 200, result: 'ok' })
    }, { accessToken: valid(), refreshToken: 'r1' })
    await expect(api.get('/me', { auth: 'required' })).resolves.toBe('ok')
    expect(calls.filter((c) => c.url.endsWith('/me'))).toHaveLength(2)

    const http401 = setup((c) => c.url.includes('/refresh/')
      ? json({ success: false, code: 20004 })
      : new Response('', { status: 401 }), { accessToken: valid(), refreshToken: 'r1' })
    await expect(http401.api.get('/me', { auth: 'required' })).rejects.toMatchObject({ code: AUTH_EXPIRED })
  })

  it('续期失败：清空会话、通知去登录、抛 AUTH_EXPIRED；没有会话的需登录接口不发请求', async () => {
    const { api, session, onAuthExpired, calls } = setup(() => json({ success: false, code: 20004 }), { accessToken: expired(), refreshToken: 'r1' })
    const err = await api.get('/me', { auth: 'required' }).catch((e: ApiError) => e)
    expect(err).toMatchObject({ code: AUTH_EXPIRED })
    expect(session.tokens()).toBeNull()
    expect(onAuthExpired).toHaveBeenCalledOnce()
    expect(calls.every((c) => c.url.includes('/refresh/'))).toBe(true)

    const anonymous = setup(() => json({ success: true, code: 200, result: 1 }))
    await expect(anonymous.api.get('/me', { auth: 'required' })).rejects.toMatchObject({ code: AUTH_EXPIRED })
    expect(anonymous.calls).toHaveLength(0)
  })

  it('并发请求共用一次续期', async () => {
    const fresh = valid()
    const { api, calls } = setup((c) => c.url.includes('/refresh/')
      ? json({ success: true, code: 200, result: { accessToken: fresh, refreshToken: 'r2' } })
      : json({ success: true, code: 200, result: 1 }), { accessToken: expired(), refreshToken: 'r1' })
    await Promise.all([api.get('/a', { auth: 'required' }), api.get('/b', { auth: 'required' })])
    expect(calls.filter((c) => c.url.includes('/refresh/'))).toHaveLength(1)
  })

  it('text 方式返回渠道表单 HTML 原文（支付宝手机网站支付）；壳回 JSON 错误时照常抛错', async () => {
    const html = '<form action="https://gateway"></form><script>document.forms[0].submit()</script>'
    const ok = setup(() => new Response(html, { headers: { 'content-type': 'text/html;charset=utf-8' } }), { accessToken: valid(), refreshToken: 'r' })
    await expect(ok.api.get('/pay/ALIPAY/H5', { auth: 'required', text: true })).resolves.toBe(html)
    const fail = setup(() => json({ success: false, code: 32000, message: '支付宝支付错误' }), { accessToken: valid(), refreshToken: 'r' })
    await expect(fail.api.get('/pay/ALIPAY/H5', { auth: 'required', text: true })).rejects.toMatchObject({ message: '支付宝支付错误' })
  })
})
