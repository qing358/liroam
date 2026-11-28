import { describe, expect, it } from 'vitest'
import { formatFen, formatPrice, formatYuan } from '../../app/utils/money'

describe('金额显示', () => {
  it('选卡页价格：整元不带小数，有角分保留两位', () => {
    expect(formatPrice(3900)).toBe('¥39')
    expect(formatPrice(9950)).toBe('¥99.50')
    expect(formatPrice(5)).toBe('¥0.05')
  })

  it('结算与订单：两位小数', () => {
    expect(formatYuan(78)).toBe('¥78.00')
    expect(formatFen(7800)).toBe('¥78.00')
  })
})
