// 目的地与套餐（计划 K3，匿名可读）：接口与页面展示口径的唯一出处；金额为分，不含任何供应商字段
import type { BuyerApi } from '~/utils/buyer-api'
import { formatPrice } from '~/utils/money'

export type DestinationKind = 'COUNTRY' | 'REGION_GROUP' | 'GLOBAL'
export type PlanForm = 'ESIM' | 'PHYSICAL' | 'SOFT'

export interface Destination {
  code: string
  nameZh: string
  nameEn: string | null
  aliases: string[]
  kind: DestinationKind
  /** 资费表「区域」列（亚洲、欧洲、美洲、大洋洲、中东、非洲） */
  continent: string | null
  fromPriceFen: number
  planCount: number
  hot: boolean
  /** 区域组成员数（K3 可选字段，WS10 补齐前可能缺省） */
  regionCount?: number
}

export interface Plan {
  skuId: string
  goodsId: string
  title: string
  days: number
  /** 不限流量为 null */
  dataBytes: number | null
  unlimited: boolean
  form: PlanForm
  voice: boolean
  sms: boolean
  kycRequired: boolean
  hasNumber: boolean
  priceFen: number
  networks: string[]
  usageRegionNote: string
}

export interface DestinationPlans {
  destination: Destination
  plans: Plan[]
}

/** 使用区域说明（/buyer/goods/liroam/usage-region）；fullText 是下单快照存的原文 */
export interface UsageRegion {
  headline: string
  regions: { code: string, name: string, continent: string | null, kycRequired: boolean, note: string | null }[]
  fullText: string
}

export const listDestinations = (api: BuyerApi, q?: string) =>
  api.get<Destination[]>('/buyer/liroam/destinations', { query: { q: q?.trim() || undefined } })

export const destinationPlans = (api: BuyerApi, code: string) =>
  api.get<DestinationPlans>(`/buyer/liroam/destinations/${encodeURIComponent(code)}/plans`)

export const usageRegion = (api: BuyerApi, skuId: string) =>
  api.get<UsageRegion | null>('/buyer/goods/liroam/usage-region', { query: { skuId } })

// ---- 首页与全部目的地 ----

export const hotDestinations = (list: Destination[]) => list.filter((d) => d.kind === 'COUNTRY' && d.hot)

export const multiDestinations = (list: Destination[]) => list.filter((d) => d.kind !== 'COUNTRY')

export const destinationFrom = (d: Destination) => `${formatPrice(d.fromPriceFen)} 起`

/** 目的地块副文：多国写覆盖数（有成员数时），其余写起价与款数 */
export function destinationLine(d: Destination): string {
  const from = destinationFrom(d)
  if (d.kind !== 'COUNTRY' && d.regionCount) return `覆盖 ${d.regionCount} 个国家和地区 · ${from}`
  return `${from} · ${d.planCount} 款`
}

/** 目的地圆标文字：国家用两字母代码，多国与全球取代码前两位 */
export const destinationBadge = (d: Pick<Destination, 'code'>) => d.code.slice(0, 2).toUpperCase()

const CONTINENT_ORDER = ['亚洲', '欧洲', '美洲', '大洋洲', '中东与非洲', '其它'] as const
const CONTINENT_LABEL: Record<string, string> = { 中东: '中东与非洲', 非洲: '中东与非洲' }

export const continentLabel = (continent: string | null) =>
  !continent ? '其它' : CONTINENT_LABEL[continent] ?? (CONTINENT_ORDER.includes(continent as never) ? continent : '其它')

/** 按洲分组，只出有目的地的洲，顺序固定 */
export function continentGroups(list: Destination[]): { label: string, items: Destination[] }[] {
  return CONTINENT_ORDER
    .map((label) => ({ label, items: list.filter((d) => continentLabel(d.continent) === label) }))
    .filter((g) => g.items.length > 0)
}

/** 搜索结果里多国与全球的副文：命中国家时写「含{第一个命中的国家}」 */
export function coverHint(results: Destination[]): string | null {
  const country = results.find((d) => d.kind === 'COUNTRY')
  return country ? `含${country.nameZh}` : null
}

// ---- 目的地页与套餐 ----

const GB = 1024 ** 3
const MB = 1024 ** 2
const trim = (n: number) => String(Number(n.toFixed(2)))

export function planData(plan: Plan): { value: string, unit: string } {
  if (plan.unlimited || plan.dataBytes === null) return { value: '不限', unit: '流量' }
  if (plan.dataBytes < GB) return { value: trim(plan.dataBytes / MB), unit: 'MB' }
  return { value: trim(plan.dataBytes / GB), unit: 'GB' }
}

export const planDataText = (plan: Plan) => {
  const { value, unit } = planData(plan)
  return plan.unlimited ? '不限流量' : `${value}${unit}`
}

export const FORM_LABEL: Record<PlanForm, string> = { ESIM: 'eSIM', PHYSICAL: '实体卡', SOFT: '软卡' }

const talks = (plan: Plan) => plan.voice || plan.sms

export function planTags(plan: Plan): string[] {
  return [FORM_LABEL[plan.form], ...(talks(plan) ? ['含通话短信'] : []), ...(plan.kycRequired ? ['需实名'] : [])]
}

export function planDetailBadges(plan: Plan): string[] {
  return [FORM_LABEL[plan.form], talks(plan) ? '含通话短信' : '仅数据', plan.kycRequired ? '需实名' : '免实名']
}

export interface DaySegment {
  key: string
  label: string
  min: number
  max: number
}

const ALL_DAYS: DaySegment = { key: 'all', label: '全部天数', min: 0, max: Infinity }
const DAY_RANGES: [number, number][] = [[1, 3], [4, 7], [8, 15], [16, 30], [31, Infinity]]

/** 天数分段按在售天数生成：不多于 5 种逐个列出，否则按区间合并 */
export function daySegments(plans: Plan[]): DaySegment[] {
  const days = [...new Set(plans.map((p) => p.days))].sort((a, b) => a - b)
  const segments = days.length <= 5
    ? days.map((d) => ({ key: `${d}`, label: `${d} 天`, min: d, max: d }))
    : DAY_RANGES
        .filter(([min, max]) => days.some((d) => d >= min && d <= max))
        .map(([min, max]) => ({ key: `${min}-${max}`, label: max === Infinity ? `${min - 1} 天以上` : `${min}–${max} 天`, min, max }))
  return [ALL_DAYS, ...segments]
}

export type PlanChip = 'ESIM' | 'PHYSICAL' | 'VOICE' | 'NO_KYC' | 'UNLIMITED'

export const PLAN_CHIPS: { key: PlanChip, label: string }[] = [
  { key: 'ESIM', label: 'eSIM' },
  { key: 'PHYSICAL', label: '实体卡' },
  { key: 'VOICE', label: '含通话短信' },
  { key: 'NO_KYC', label: '免实名' },
  { key: 'UNLIMITED', label: '不限流量' },
]

/** 形态两项之间是「或」，其余条件之间是「且」 */
export function filterPlans(plans: Plan[], filter: { segment?: string, chips: PlanChip[] }): Plan[] {
  const segment = filter.segment ? daySegments(plans).find((s) => s.key === filter.segment) : undefined
  const forms = filter.chips.filter((c): c is 'ESIM' | 'PHYSICAL' => c === 'ESIM' || c === 'PHYSICAL')
  return plans.filter((p) =>
    (!segment || (p.days >= segment.min && p.days <= segment.max))
    && (!forms.length || forms.includes(p.form as 'ESIM' | 'PHYSICAL'))
    && (!filter.chips.includes('VOICE') || talks(p))
    && (!filter.chips.includes('NO_KYC') || !p.kycRequired)
    && (!filter.chips.includes('UNLIMITED') || p.unlimited))
}

/** 目的地页标题与描述（title / description 口径见 brand-copy「新买家站」） */
export function destinationTitle(d: Destination) {
  return `${d.nameZh} eSIM 与流量卡`
}

export function destinationSubtitle(d: Destination, networks: string[]) {
  const scope = d.kind === 'COUNTRY' ? `${d.nameZh}全境可用` : `${d.nameZh}可用`
  return [scope, ...(networks.length ? [networks.join(' / ')] : []), `${d.planCount} 款套餐 ${formatPrice(d.fromPriceFen)} 起`].join(' · ')
}

/** 目的地下全部套餐的网络（去重，保持首次出现顺序） */
export const destinationNetworks = (plans: Plan[]) => [...new Set(plans.flatMap((p) => p.networks))]
