// 壳订单接口（buyer-api /buyer/order/order/**）
import type { BuyerApi } from '~/utils/buyer-api'

export interface OrderDetail {
  order: {
    sn: string
    /** 壳 OrderTypeEnum：NORMAL 实物（实体卡）、VIRTUAL 虚拟（eSIM / 软卡） */
    orderType: string
    orderStatus: string
    deliverStatus?: string
    paymentTime?: string
  }
  orderItems: { sn: string, goodsName: string, num: number }[]
}

export const orderDetail = (api: BuyerApi, sn: string) =>
  api.get<OrderDetail>(`/buyer/order/order/${encodeURIComponent(sn)}`, { auth: 'required' })
