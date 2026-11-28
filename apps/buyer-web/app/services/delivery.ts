// 支付结果页的交付进度：壳订单状态 +（K4 合入后）按订单号取的卡投影；接口出口在 services/orders.ts、services/cards.ts
import type { CardView } from '~/services/cards'
import type { OrderDetail } from '~/services/orders'

export interface OrderBrief {
  sn: string
  /** 壳 OrderTypeEnum：NORMAL 实物（实体卡）、VIRTUAL 虚拟（eSIM / 软卡） */
  orderType: string
  orderStatus: string
  deliverStatus?: string
  paymentTime?: string
  items: { goodsName: string, num: number }[]
}

export type CardBrief = Pick<CardView, 'id' | 'productName' | 'status'>

export type StepState = 'done' | 'now' | 'todo'

export interface DeliveryStep {
  label: string
  detail?: string
  state: StepState
}

export interface DeliveryCard {
  index: number
  name: string
  cardId?: number
  ready: boolean
}

export interface DeliveryProgress {
  orderSn: string
  physical: boolean
  steps: DeliveryStep[]
  /** 逐张进度；只按订单状态显示时为空 */
  cards: DeliveryCard[]
  done: boolean
}

/** 已拿到激活信息、可以安装的卡状态（CardAssetStatus） */
const READY = new Set(['DELIVERED', 'IN_USE', 'SUSPENDED', 'EXPIRED'])
const SHIPPED = new Set(['DELIVERED', 'COMPLETED'])

export const toOrderBrief = ({ order, orderItems }: OrderDetail): OrderBrief => ({
  sn: order.sn,
  orderType: order.orderType,
  orderStatus: order.orderStatus,
  deliverStatus: order.deliverStatus,
  paymentTime: order.paymentTime,
  items: orderItems.map((i) => ({ goodsName: i.goodsName, num: i.num })),
})

/** 壳时间「2026-11-28 20:31:00」→「11月28日 20:31」 */
function paidAt(time: string | undefined): string | undefined {
  const m = time?.match(/^\d{4}-(\d{2})-(\d{2})[ T](\d{2}:\d{2})/)
  return m ? `${Number(m[1])}月${Number(m[2])}日 ${m[3]}` : undefined
}

const step = (label: string, state: StepState, detail?: string): DeliveryStep => ({ label, state, ...(detail ? { detail } : {}) })

/** cards 为 null：K4 未就绪，只按订单状态显示、不分张数 */
export function deliveryProgress(order: OrderBrief, cards: CardBrief[] | null): DeliveryProgress {
  const paid = step('已支付', 'done', paidAt(order.paymentTime))

  if (order.orderType === 'NORMAL') {
    const shipped = SHIPPED.has(order.orderStatus) || order.deliverStatus === 'DELIVERED'
    return {
      orderSn: order.sn,
      physical: true,
      steps: [paid, step('待发货', shipped ? 'done' : 'now'), step('已发货', shipped ? 'done' : 'todo')],
      cards: [],
      done: shipped,
    }
  }

  const install = (state: StepState) => step('可以安装', state, '完成后在「我的卡」扫码安装')
  if (!cards) {
    return { orderSn: order.sn, physical: false, steps: [paid, step('开卡中', 'now', '通常几分钟内完成'), install('todo')], cards: [], done: false }
  }

  const total = Math.max(order.items.reduce((n, i) => n + i.num, 0), cards.length)
  const name = order.items[0]?.goodsName ?? ''
  const rows: DeliveryCard[] = Array.from({ length: total }, (_, i) => {
    const c = cards[i]
    return c
      ? { index: i + 1, name: c.productName, cardId: c.id, ready: READY.has(c.status) }
      : { index: i + 1, name, ready: false }
  })
  const ready = rows.filter((r) => r.ready).length
  const done = total > 0 && ready === total
  return {
    orderSn: order.sn,
    physical: false,
    steps: [paid, step('开卡中', done ? 'done' : 'now', `${total} 张卡，已完成 ${ready} 张`), install(done ? 'done' : 'todo')],
    cards: rows,
    done,
  }
}
