// buyer-api 客户端：壳统一返回 ResultMessage {success, code, message, result}，这里拆包、带会话头、过期自动续期。
import { isTokenExpired, type SessionStore, type SessionTokens } from './session'

/** 壳「未登录 / 令牌失效」：HTTP 401 / 403，或 ResultMessage.code 20004（USER_NOT_LOGIN） */
const AUTH_CODES = new Set([401, 403, 20004])

export const AUTH_EXPIRED = 'AUTH_EXPIRED'

export class ApiError extends Error {
  constructor(message: string, readonly code: number | string, readonly status?: number) {
    super(message)
    this.name = 'ApiError'
  }
}

type Primitive = string | number | boolean
export type Params = Record<string, Primitive | Primitive[] | null | undefined>

export interface RequestOptions {
  /** 查询串；数组按壳的习惯重复键名 */
  query?: Params
  /** 表单体（壳多数写接口按 @RequestParam 收参） */
  form?: Params
  /** JSON 体（壳 @RequestBody 接口，如 create/trade） */
  json?: unknown
  /** required 必须登录；optional 有有效令牌就带上 */
  auth?: 'required' | 'optional' | 'none'
  headers?: Record<string, string>
  /** 返回原始文本（支付宝手机网站支付直接回表单 HTML） */
  text?: boolean
}

export interface BuyerApiDeps {
  baseURL: string
  session: SessionStore
  fetch?: typeof globalThis.fetch
  /** 续期失败、会话已清空后调用（页面据此去登录） */
  onAuthExpired?: () => void
}

export interface BuyerApi {
  request<T>(method: 'GET' | 'POST' | 'PUT' | 'DELETE', path: string, options?: RequestOptions): Promise<T>
  get<T>(path: string, options?: RequestOptions): Promise<T>
  post<T>(path: string, options?: RequestOptions): Promise<T>
  put<T>(path: string, options?: RequestOptions): Promise<T>
  del<T>(path: string, options?: RequestOptions): Promise<T>
  url(path: string, query?: Params): string
}

function encode(params: Params): URLSearchParams {
  const out = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) {
    if (v === null || v === undefined) continue
    for (const item of Array.isArray(v) ? v : [v]) out.append(k, String(item))
  }
  return out
}

interface ResultMessage<T> {
  success: boolean
  code: number
  message?: string
  result: T
}

export function createBuyerApi({ baseURL, session, fetch = globalThis.fetch, onAuthExpired }: BuyerApiDeps): BuyerApi {
  let refreshing: Promise<SessionTokens | null> | null = null

  const url = (path: string, query?: Params) => {
    const qs = query ? encode(query).toString() : ''
    return `${baseURL.replace(/\/$/, '')}${path}${qs ? `?${qs}` : ''}`
  }

  // 并发请求共用一次续期；续期接口本身不带 accessToken
  function refresh(): Promise<SessionTokens | null> {
    if (refreshing) return refreshing
    const current = session.tokens()
    if (!current) return Promise.resolve(null)
    refreshing = fetch(url(`/buyer/passport/member/refresh/${encodeURIComponent(current.refreshToken)}`), { headers: { uuid: session.uuid() } })
      .then((res) => res.json() as Promise<ResultMessage<SessionTokens>>)
      .then((body) => {
        if (!body.success || !body.result?.accessToken || !body.result?.refreshToken) return null
        session.save({ accessToken: body.result.accessToken, refreshToken: body.result.refreshToken })
        return session.tokens()
      })
      .catch(() => null)
      .finally(() => { refreshing = null })
    return refreshing
  }

  function expire(): never {
    session.clear()
    onAuthExpired?.()
    throw new ApiError('登录已过期，请重新登录', AUTH_EXPIRED)
  }

  async function validToken(auth: RequestOptions['auth']): Promise<string | undefined> {
    if (auth === 'none' || !auth) return undefined
    const tokens = session.tokens()
    if (tokens && !isTokenExpired(tokens.accessToken)) return tokens.accessToken
    if (auth === 'optional') {
      if (tokens) session.clear()
      return undefined
    }
    const renewed = await refresh()
    return renewed?.accessToken ?? expire()
  }

  async function send<T>(method: string, path: string, options: RequestOptions, retried: boolean): Promise<T> {
    const headers: Record<string, string> = { uuid: session.uuid(), ...options.headers }
    const token = await validToken(options.auth)
    if (token) headers.accessToken = token
    let body: BodyInit | undefined
    if (options.json !== undefined) {
      headers['Content-Type'] = 'application/json'
      body = JSON.stringify(options.json)
    }
    else if (options.form) {
      headers['Content-Type'] = 'application/x-www-form-urlencoded'
      body = encode(options.form).toString()
    }

    let res: Response
    try {
      res = await fetch(url(path, options.query), { method, headers, body })
    }
    catch {
      throw new ApiError('网络连接失败，请稍后再试', 'NETWORK')
    }

    const contentType = res.headers.get('content-type') ?? ''
    if (options.text && res.ok && !contentType.includes('json')) return (await res.text()) as T

    const parsed = await res.json().catch(() => null) as ResultMessage<T> | null
    const code = parsed?.code ?? res.status
    if (options.auth === 'required' && (AUTH_CODES.has(res.status) || AUTH_CODES.has(code))) {
      if (!retried && await refresh()) return send<T>(method, path, options, true)
      expire()
    }
    if (!parsed) throw new ApiError(res.status === 404 ? '请求的资源不存在' : '服务暂时不可用，请稍后再试', res.status, res.status)
    if (!parsed.success) throw new ApiError(parsed.message || '操作失败，请稍后再试', code, res.status)
    return parsed.result
  }

  const request = <T>(method: 'GET' | 'POST' | 'PUT' | 'DELETE', path: string, options: RequestOptions = {}) => send<T>(method, path, options, false)
  return {
    request,
    get: (path, options) => request('GET', path, options),
    post: (path, options) => request('POST', path, options),
    put: (path, options) => request('PUT', path, options),
    del: (path, options) => request('DELETE', path, options),
    url,
  }
}
