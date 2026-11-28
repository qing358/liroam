import { describe, expect, it } from 'vitest'
import { deliveryProgress, type CardBrief, type OrderBrief } from '../../app/services/delivery'

const esimOrder = (over: Partial<OrderBrief> = {}): OrderBrief => ({
  sn: 'O1',
  orderType: 'VIRTUAL',
  orderStatus: 'TAKE',
  deliverStatus: 'UNDELIVERED',
  paymentTime: '2026-11-28 20:31:00',
  items: [{ goodsName: '日本 eSIM 7 天 5GB', num: 2 }],
  ...over,
})
const card = (id: number, status: string): CardBrief => ({ id, productName: '日本 eSIM 7 天 5GB', status })
const states = (p: ReturnType<typeof deliveryProgress>) => p.steps.map((s) => `${s.label}:${s.state}`)

describe('支付结果：只按订单状态（K4 合入前）', () => {
  it('eSIM：已支付 → 开卡中（不写张数） → 可以安装', () => {
    const p = deliveryProgress(esimOrder(), null)
    expect(p.physical).toBe(false)
    expect(states(p)).toEqual(['已支付:done', '开卡中:now', '可以安装:todo'])
    expect(p.steps[0]!.detail).toBe('11月28日 20:31')
    expect(p.steps[1]!.detail).toBe('通常几分钟内完成')
    expect(p.steps[2]!.detail).toBe('完成后在「我的卡」扫码安装')
    expect(p.cards).toEqual([])
    expect(p.done).toBe(false)
  })

  it('实体卡：未发货时待发货进行中，已发货后完成', () => {
    const waiting = deliveryProgress(esimOrder({ orderType: 'NORMAL', orderStatus: 'UNDELIVERED' }), null)
    expect(waiting.physical).toBe(true)
    expect(states(waiting)).toEqual(['已支付:done', '待发货:now', '已发货:todo'])
    expect(waiting.done).toBe(false)
    const shipped = deliveryProgress(esimOrder({ orderType: 'NORMAL', orderStatus: 'DELIVERED', deliverStatus: 'DELIVERED' }), null)
    expect(states(shipped)).toEqual(['已支付:done', '待发货:done', '已发货:done'])
    expect(shipped.done).toBe(true)
  })
})

describe('支付结果：按卡分张数（K4 orderSn 过滤）', () => {
  it('2 张卡已完成 1 张：逐张列出，未建卡的补为开卡中', () => {
    const p = deliveryProgress(esimOrder(), [card(11, 'DELIVERED')])
    expect(states(p)).toEqual(['已支付:done', '开卡中:now', '可以安装:todo'])
    expect(p.steps[1]!.detail).toBe('2 张卡，已完成 1 张')
    expect(p.cards).toEqual([
      { index: 1, name: '日本 eSIM 7 天 5GB', cardId: 11, ready: true },
      { index: 2, name: '日本 eSIM 7 天 5GB', ready: false },
    ])
    expect(p.done).toBe(false)
  })

  it('开通中的卡算未完成；全部可安装后时间线走完', () => {
    expect(deliveryProgress(esimOrder(), [card(11, 'CREATED'), card(12, 'PROVISIONED')]).steps[1]!.detail).toBe('2 张卡，已完成 0 张')
    const p = deliveryProgress(esimOrder(), [card(11, 'DELIVERED'), card(12, 'IN_USE')])
    expect(states(p)).toEqual(['已支付:done', '开卡中:done', '可以安装:done'])
    expect(p.done).toBe(true)
  })

  it('实体卡不按卡分张数，仍按发货状态', () => {
    const p = deliveryProgress(esimOrder({ orderType: 'NORMAL', orderStatus: 'UNDELIVERED' }), [card(11, 'PROVISIONED')])
    expect(states(p)).toEqual(['已支付:done', '待发货:now', '已发货:todo'])
    expect(p.cards).toEqual([])
  })
})
