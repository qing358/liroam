import { describe, expect, it } from 'vitest'
import { createSessionStore, isTokenExpired, memoryStorage, SESSION_KEYS } from '../../app/utils/session'
import { jwt } from './jwt'

describe('会话', () => {
  it('令牌成对保存、成对读取；只有一半视为未登录；clear 后为空，uuid 保留', () => {
    const storage = memoryStorage()
    const session = createSessionStore(storage, () => 'u-1')
    expect(session.tokens()).toBeNull()
    session.save({ accessToken: 'a', refreshToken: 'r' })
    expect(session.tokens()).toEqual({ accessToken: 'a', refreshToken: 'r' })
    expect(storage.getItem(SESSION_KEYS.accessToken)).toBe('a')

    storage.removeItem(SESSION_KEYS.refreshToken)
    expect(session.tokens()).toBeNull()

    const id = session.uuid()
    session.save({ accessToken: 'a', refreshToken: 'r' })
    session.clear()
    expect(session.tokens()).toBeNull()
    expect(session.uuid()).toBe(id)
  })

  it('uuid 首次生成后保持不变', () => {
    let n = 0
    const session = createSessionStore(memoryStorage(), () => `u-${++n}`)
    expect(session.uuid()).toBe('u-1')
    expect(session.uuid()).toBe('u-1')
  })

  it('JWT 过期判断：exp 未到为有效，已到或无法解析为过期', () => {
    const now = 1_800_000_000_000
    expect(isTokenExpired(jwt(now / 1000 + 60), now)).toBe(false)
    expect(isTokenExpired(jwt(now / 1000), now)).toBe(true)
    expect(isTokenExpired('not-a-jwt', now)).toBe(true)
    expect(isTokenExpired('a.!!!.c', now)).toBe(true)
  })
})
