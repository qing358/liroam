// 买家会话的唯一定义处（K7）：本地存储键、令牌读写与过期判断。页面与 API 客户端都只经这里读写登录态。

export const SESSION_KEYS = {
  accessToken: 'liroam.buyer.accessToken',
  refreshToken: 'liroam.buyer.refreshToken',
  uuid: 'liroam.buyer.uuid',
} as const

export interface KeyValueStorage {
  getItem(key: string): string | null
  setItem(key: string, value: string): void
  removeItem(key: string): void
}

export interface SessionTokens {
  accessToken: string
  refreshToken: string
}

export interface SessionStore {
  tokens(): SessionTokens | null
  save(tokens: SessionTokens): void
  clear(): void
  /** 壳按 uuid 关联滑块、短信验证码与登录，同一浏览器保持不变 */
  uuid(): string
}

/** 服务端渲染与测试用：不持久化 */
export function memoryStorage(): KeyValueStorage {
  const map = new Map<string, string>()
  return {
    getItem: (k) => map.get(k) ?? null,
    setItem: (k, v) => void map.set(k, v),
    removeItem: (k) => void map.delete(k),
  }
}

export function createSessionStore(storage: KeyValueStorage, newUuid: () => string = () => crypto.randomUUID()): SessionStore {
  return {
    tokens() {
      const accessToken = storage.getItem(SESSION_KEYS.accessToken)
      const refreshToken = storage.getItem(SESSION_KEYS.refreshToken)
      return accessToken && refreshToken ? { accessToken, refreshToken } : null
    },
    save({ accessToken, refreshToken }) {
      storage.setItem(SESSION_KEYS.accessToken, accessToken)
      storage.setItem(SESSION_KEYS.refreshToken, refreshToken)
    },
    clear() {
      storage.removeItem(SESSION_KEYS.accessToken)
      storage.removeItem(SESSION_KEYS.refreshToken)
    },
    uuid() {
      let id = storage.getItem(SESSION_KEYS.uuid)
      if (!id) {
        id = newUuid()
        storage.setItem(SESSION_KEYS.uuid, id)
      }
      return id
    },
  }
}

/** JWT 的 exp（秒）已到或无法解析即视为过期 */
export function isTokenExpired(jwt: string, nowMs: number = Date.now()): boolean {
  try {
    const part = jwt.split('.')[1]
    if (!part) return true
    const json = new TextDecoder().decode(Uint8Array.from(atob(part.replace(/-/g, '+').replace(/_/g, '/')), (c) => c.charCodeAt(0)))
    const exp = Number(JSON.parse(json).exp)
    return !Number.isFinite(exp) || exp * 1000 <= nowMs
  }
  catch {
    return true
  }
}
