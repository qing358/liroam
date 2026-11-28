// 壳收货地址与地区字典（实体卡寄送用）
import type { BuyerApi } from '~/utils/buyer-api'

export interface Address {
  id: string
  name: string
  mobile: string
  consigneeAddressPath: string
  consigneeAddressIdPath: string
  detail: string
  alias?: string
  isDefault: boolean
}

export interface AddressInput {
  name: string
  mobile: string
  /** 省市区 id / 名称，按壳约定逗号拼接 */
  consigneeAddressIdPath: string
  consigneeAddressPath: string
  detail: string
  isDefault: boolean
}

export interface Region {
  id: string
  name: string
}

export const listAddresses = (api: BuyerApi) =>
  api.get<{ records: Address[] }>('/buyer/member/address', { query: { pageNumber: 1, pageSize: 50 }, auth: 'required' })
    .then((page) => page.records ?? [])

export const createAddress = (api: BuyerApi, input: AddressInput) =>
  api.post<Address>('/buyer/member/address', { form: { ...input }, auth: 'required' })

export const deleteAddress = (api: BuyerApi, id: string) =>
  api.del<unknown>(`/buyer/member/address/delById/${encodeURIComponent(id)}`, { auth: 'required' })

/** parentId=0 为省级 */
export const regionChildren = (api: BuyerApi, parentId: string) =>
  api.get<Region[]>(`/buyer/common/region/item/${encodeURIComponent(parentId)}`, { auth: 'required' })
