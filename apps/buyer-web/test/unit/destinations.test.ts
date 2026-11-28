import { describe, expect, it } from 'vitest'
import {
  continentGroups,
  coverHint,
  daySegments,
  destinationLine,
  filterPlans,
  hotDestinations,
  multiDestinations,
  planData,
  planTags,
  planDetailBadges,
} from '../../app/services/destinations'
import { destinations, japanPlans } from '../fixtures/k3'

const plans = japanPlans.plans
const byCode = (code: string) => destinations.find((d) => d.code === code)!

describe('首页分区', () => {
  it('热门目的地只取单个国家和地区且 hot，保持接口顺序', () => {
    expect(hotDestinations(destinations).map((d) => d.code)).toEqual(['JP', 'KR'])
  })

  it('多国与全球：区域组与全球，接口顺序', () => {
    expect(multiDestinations(destinations).map((d) => d.code)).toEqual(['EU33', 'AS14', 'GLOBAL'])
  })

  it('多国块副文：有成员数写覆盖数，没有退为款数', () => {
    expect(destinationLine({ ...byCode('EU33'), regionCount: 33 })).toBe('覆盖 33 个国家和地区 · ¥39 起')
    expect(destinationLine(byCode('EU33'))).toBe('¥39 起 · 3 款')
    expect(destinationLine(byCode('JP'))).toBe('¥19 起 · 4 款')
  })
})

describe('全部目的地', () => {
  it('按洲分组，中东与非洲合一组，无洲的归其它；只出有数据的洲，按固定顺序', () => {
    const countries = destinations.filter((d) => d.kind === 'COUNTRY')
    expect(continentGroups([...countries, { ...byCode('JP'), code: 'XX', continent: null }]).map((g) => [g.label, g.items.map((d) => d.code)])).toEqual([
      ['亚洲', ['JP', 'KR']],
      ['欧洲', ['GB']],
      ['中东与非洲', ['EG', 'AE']],
      ['其它', ['XX']],
    ])
  })

  it('搜索命中国家时，多国与全球结果写「含」第一个命中的国家', () => {
    const results = [byCode('JP'), byCode('AS14'), byCode('GLOBAL')]
    expect(coverHint(results)).toBe('含日本')
    expect(coverHint([byCode('AS14')])).toBeNull()
  })
})

describe('目的地页套餐', () => {
  it('流量：GB、小于 1GB 写 MB、不限流量', () => {
    expect(planData(plans[0]!)).toEqual({ value: '1', unit: 'GB' })
    expect(planData(plans[2]!)).toEqual({ value: '不限', unit: '流量' })
    expect(planData({ ...plans[0]!, dataBytes: 512 * 1024 ** 2 })).toEqual({ value: '512', unit: 'MB' })
    expect(planData({ ...plans[0]!, dataBytes: 1.5 * 1024 ** 3 })).toEqual({ value: '1.5', unit: 'GB' })
  })

  it('卡片标签：形态、含通话短信、需实名', () => {
    expect(planTags(plans[1]!)).toEqual(['eSIM'])
    expect(planTags(plans[3]!)).toEqual(['实体卡', '含通话短信', '需实名'])
    expect(planTags({ ...plans[1]!, form: 'SOFT' })).toEqual(['软卡'])
  })

  it('详情徽标：形态、仅数据 / 含通话短信、免实名 / 需实名', () => {
    expect(planDetailBadges(plans[1]!)).toEqual(['eSIM', '仅数据', '免实名'])
    expect(planDetailBadges(plans[3]!)).toEqual(['实体卡', '含通话短信', '需实名'])
  })

  it('天数分段按在售天数生成：不多于 5 种时逐个列出', () => {
    expect(daySegments(plans).map((s) => s.label)).toEqual(['全部天数', '3 天', '7 天', '30 天'])
  })

  it('天数种类多于 5 种时按区间合并，只出有套餐的区间', () => {
    const many = [1, 2, 5, 7, 10, 15, 30, 90].map((days, i) => ({ ...plans[0]!, skuId: String(i), days }))
    expect(daySegments(many).map((s) => s.label)).toEqual(['全部天数', '1–3 天', '4–7 天', '8–15 天', '16–30 天', '30 天以上'])
  })

  it('筛选：天数分段与各能力条件同时生效', () => {
    const seven = daySegments(plans).find((s) => s.label === '7 天')!
    const ids = (f: Parameters<typeof filterPlans>[1]) => filterPlans(plans, f).map((p) => p.skuId)
    expect(ids({ segment: seven.key, chips: [] })).toEqual(['1002', '1003'])
    expect(ids({ chips: ['ESIM'] })).toEqual(['1001', '1002', '1003'])
    expect(ids({ chips: ['PHYSICAL'] })).toEqual(['1004'])
    expect(ids({ chips: ['ESIM', 'PHYSICAL'] })).toEqual(['1001', '1002', '1003', '1004'])
    expect(ids({ chips: ['VOICE'] })).toEqual(['1004'])
    expect(ids({ chips: ['NO_KYC'] })).toEqual(['1001', '1002', '1003'])
    expect(ids({ segment: seven.key, chips: ['UNLIMITED'] })).toEqual(['1003'])
  })
})
