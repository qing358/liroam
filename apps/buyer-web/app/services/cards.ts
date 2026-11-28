// 自研「我的卡」接口（buyer-api /buyer/liroam/cards/**）：卡投影与文案由后端 CardViewProjector 定，前端只展示
import type { BuyerApi } from '~/utils/buyer-api'

export interface CardView {
  id: number
  productName: string
  /** CardAssetStatus */
  status: string
  orderSn?: string
}

/** 订单的卡区块（K4）：同一卡投影按订单号过滤；再按 orderSn 收一遍，后端过滤未上线时也不会混进别的订单的卡 */
export const cardsOfOrder = (api: BuyerApi, orderSn: string) =>
  api.get<CardView[]>('/buyer/liroam/cards', { query: { orderSn }, auth: 'required' })
    .then((cards) => cards.filter((c) => c.orderSn === orderSn))
