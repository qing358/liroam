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

export const paid = (api: BuyerApi, sn: string, clientType: ShellClientType) =>
  api.get<boolean>('/buyer/payment/cashier/result', { query: payParam(sn, clientType), auth: 'required' })
