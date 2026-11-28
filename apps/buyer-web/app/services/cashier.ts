// 壳收银台（buyer-api /buyer/payment/cashier/**）：新站只付交易单（orderType=TRADE）
import type { BuyerApi } from '~/utils/buyer-api'
import type { PaymentOption, ShellClientType } from '~/utils/terminal'

export const ORDER_TYPE_TRADE = 'TRADE'

export interface CashierDetail {
  price: number
  title?: string
  detail?: string
  orderSns?: string
  /** 壳后台对该终端开启的支付方式（ALIPAY / WECHAT / WALLET …） */
  support: string[]
  walletValue: number
  autoCancel?: number
}

const payParam = (sn: string, clientType: ShellClientType) => ({ sn, orderType: ORDER_TYPE_TRADE, clientType })

export const cashierDetail = (api: BuyerApi, sn: string, clientType: ShellClientType) =>
  api.get<CashierDetail>('/buyer/payment/cashier/tradeDetail', { query: payParam(sn, clientType), auth: 'required' })

/** form 方式返回渠道表单 HTML，其余返回壳 result（地址、扫码串、调起参数或空） */
export const pay = (api: BuyerApi, option: PaymentOption, sn: string, clientType: ShellClientType) =>
  api.get<unknown>(`/buyer/payment/cashier/pay/${option.method}/${option.client}`, {
    query: payParam(sn, clientType),
    auth: 'required',
    text: option.action === 'form',
  })

/** 距自动取消（tradeDetail.autoCancel，毫秒时间戳）还剩多久，mm:ss；已过期或没有为 null */
export function payCountdown(autoCancel: number | undefined, now: number): string | null {
  if (!autoCancel || autoCancel <= now) return null
  const seconds = Math.floor((autoCancel - now) / 1000)
  return `${String(Math.floor(seconds / 60)).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`
}

/** tradeDetail.orderSns 是「O1, O2」形式的串 */
export const orderSnList = (orderSns: string | undefined) => (orderSns ?? '').split(',').map((s) => s.trim()).filter(Boolean)

export const paid = (api: BuyerApi, sn: string, clientType: ShellClientType) =>
  api.get<boolean>('/buyer/payment/cashier/result', { query: payParam(sn, clientType), auth: 'required' })
