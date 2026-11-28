// 壳交易接口：新站只走「立即购买」（BUY_NOW），不展示购物车（集成方案 §2.4）
import type { BuyerApi } from '~/utils/buyer-api'
import type { ShellClientType } from '~/utils/terminal'

export const BUY_NOW = 'BUY_NOW'

export interface CheckoutSku {
  goodsSku: { id: string, goodsId: string, goodsName: string, simpleSpecs?: string, thumbnail?: string, price: number, goodsType?: string }
  num: number
  subTotal?: number
  errorMessage?: string
  invalid?: boolean
  checked?: boolean
}

export interface CheckoutAddress {
  id: string
  name: string
  mobile: string
  consigneeAddressPath: string
  detail: string
}

export interface Checkout {
  skuList: CheckoutSku[]
  priceDetailDTO: { goodsPrice: number, freightPrice: number, flowPrice: number }
  memberAddress?: CheckoutAddress | null
}

export interface Trade {
  sn: string
}

/** 实体卡等需要寄送的商品；壳 GoodsTypeEnum 里只有 VIRTUAL_GOODS / E_COUPON 不发货 */
export function needsShipping(checkout: Checkout): boolean {
  return checkout.skuList.some((s) => s.goodsSku.goodsType === 'PHYSICAL_GOODS')
}

export const buyNow = (api: BuyerApi, skuId: string, num: number) =>
  api.post<unknown>('/buyer/trade/carts', { query: { skuId, num, cartType: BUY_NOW }, auth: 'required' })

export const checkout = (api: BuyerApi) =>
  api.get<Checkout>('/buyer/trade/carts/checked', { query: { way: BUY_NOW }, auth: 'required' })

export const selectShippingAddress = (api: BuyerApi, shippingAddressId: string) =>
  api.get<unknown>('/buyer/trade/carts/shippingAddress', { query: { shippingAddressId, way: BUY_NOW }, auth: 'required' })

export const createTrade = (api: BuyerApi, client: ShellClientType) =>
  api.post<Trade>('/buyer/trade/carts/create/trade', { json: { client, way: BUY_NOW }, auth: 'required' })
