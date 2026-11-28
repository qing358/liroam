import { describe, expect, it } from 'vitest'
import { orderSnList, payCountdown } from '../../app/services/cashier'

describe('收银台', () => {
  it('支付倒计时 mm:ss；没有截止时间或已过期为空', () => {
    const now = 1_000_000
    expect(payCountdown(now + (29 * 60 + 41) * 1000 + 300, now)).toBe('29:41')
    expect(payCountdown(now + 5000, now)).toBe('00:05')
    expect(payCountdown(now - 1, now)).toBeNull()
    expect(payCountdown(undefined, now)).toBeNull()
  })

  it('壳交易单的订单号串按逗号拆开', () => {
    expect(orderSnList('O1, O2')).toEqual(['O1', 'O2'])
    expect(orderSnList('O1')).toEqual(['O1'])
    expect(orderSnList(undefined)).toEqual([])
  })
})
