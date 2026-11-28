// K3 目的地聚合接口的测试替身（计划 §4 K3 字段原样）；单测与 SSR 测试的假 buyer-api 共用
import type { Destination, DestinationPlans, Plan } from '../../app/services/destinations'

const GB = 1024 ** 3

export const destinations: Destination[] = [
  { code: 'JP', nameZh: '日本', nameEn: 'Japan', aliases: ['riben'], kind: 'COUNTRY', continent: '亚洲', fromPriceFen: 1900, planCount: 4, hot: true },
  { code: 'KR', nameZh: '韩国', nameEn: 'Korea', aliases: [], kind: 'COUNTRY', continent: '亚洲', fromPriceFen: 1700, planCount: 2, hot: true },
  { code: 'GB', nameZh: '英国', nameEn: 'United Kingdom', aliases: [], kind: 'COUNTRY', continent: '欧洲', fromPriceFen: 2500, planCount: 1, hot: false },
  { code: 'EG', nameZh: '埃及', nameEn: 'Egypt', aliases: [], kind: 'COUNTRY', continent: '非洲', fromPriceFen: 3000, planCount: 1, hot: false },
  { code: 'AE', nameZh: '阿联酋', nameEn: 'United Arab Emirates', aliases: [], kind: 'COUNTRY', continent: '中东', fromPriceFen: 3500, planCount: 1, hot: false },
  { code: 'EU33', nameZh: '欧洲多国', nameEn: 'Europe', aliases: [], kind: 'REGION_GROUP', continent: '欧洲', fromPriceFen: 3900, planCount: 3, hot: true },
  { code: 'AS14', nameZh: '亚洲多国', nameEn: 'Asia', aliases: [], kind: 'REGION_GROUP', continent: '亚洲', fromPriceFen: 2900, planCount: 2, hot: false, regionCount: 14 },
  { code: 'GLOBAL', nameZh: '全球通用', nameEn: 'Global', aliases: [], kind: 'GLOBAL', continent: null, fromPriceFen: 5900, planCount: 1, hot: false },
]

const plan = (p: Partial<Plan> & Pick<Plan, 'skuId' | 'days' | 'priceFen'>): Plan => ({
  goodsId: `g-${p.skuId}`,
  title: `日本 eSIM ${p.days} 天`,
  dataBytes: 5 * GB,
  unlimited: false,
  form: 'ESIM',
  voice: false,
  sms: false,
  kycRequired: false,
  hasNumber: false,
  networks: ['4G', '5G'],
  usageRegionNote: '适用于日本（共 1 个国家/地区）',
  ...p,
})

export const japanPlans: DestinationPlans = {
  destination: destinations[0]!,
  plans: [
    plan({ skuId: '1001', title: '日本 eSIM 3 天 1GB', days: 3, dataBytes: GB, priceFen: 1900 }),
    plan({ skuId: '1002', title: '日本 eSIM 7 天 5GB', days: 7, priceFen: 3900 }),
    plan({ skuId: '1003', title: '日本 eSIM 7 天不限流量', days: 7, dataBytes: null, unlimited: true, priceFen: 6900 }),
    plan({ skuId: '1004', title: '日本实体卡 30 天 20GB', days: 30, dataBytes: 20 * GB, form: 'PHYSICAL', voice: true, sms: true, kycRequired: true, hasNumber: true, priceFen: 9950 }),
  ],
}

export const usageRegion = {
  headline: '适用于日本（共 1 个国家/地区）',
  regions: [{ code: 'JP', name: '日本', continent: '亚洲', kycRequired: false, note: null }],
  fullText: '可在日本全境使用，包括北海道、冲绳等离岛。',
}
