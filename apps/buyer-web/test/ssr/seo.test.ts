// SEO 公共出口：title / description / canonical / 分享卡片 / 结构化数据在页面源码里；站点地图与 robots 用站点对外地址（NUXT_SITE_URL）
import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import { SITE_URL, headValue, jsonLd, startSsrServer, type SsrServer } from './server'

let ssr: SsrServer
beforeAll(async () => { ssr = await startSsrServer() }, 30_000)
afterAll(() => ssr?.stop())

const html = (path: string) => ssr.html(path)
const DESCRIPTION = '轻游 Liroam 出境旅行通信卡：按目的地选购 eSIM 与实体卡，付款后在线发码，随时查询用量、续费加包。'

describe('页面 meta', () => {
  it('首页：品牌描述、canonical 与分享卡片', async () => {
    const { text } = await html('/')
    expect(headValue(text, 'meta', 'description')).toBe(DESCRIPTION)
    expect(headValue(text, 'link', 'canonical')).toBe(`${SITE_URL}/`)
    expect(headValue(text, 'meta', 'og:url')).toBe(`${SITE_URL}/`)
    expect(headValue(text, 'meta', 'og:site_name')).toBe('轻游')
    expect(headValue(text, 'meta', 'og:image')).toBe(`${SITE_URL}/brand/liroam-share.png`)
    expect(headValue(text, 'meta', 'twitter:card')).toBe('summary_large_image')
  })

  it('目的地页：canonical 统一小写代码，分享标题同页面标题', async () => {
    const { text } = await html('/esim/JP')
    expect(headValue(text, 'link', 'canonical')).toBe(`${SITE_URL}/esim/jp`)
    expect(headValue(text, 'meta', 'og:title')).toBe('日本 eSIM 与流量卡 · 轻游')
    expect(headValue(text, 'meta', 'og:description')).toBe(DESCRIPTION)
  })

  it('搜索结果的 canonical 指向全部目的地', async () => {
    expect(headValue((await html('/esim?q=riben')).text, 'link', 'canonical')).toBe(`${SITE_URL}/esim`)
  })

  it('目的地页：Product + AggregateOffer（人民币，最低到最高价）', async () => {
    const product = jsonLd((await html('/esim/jp')).text, 'Product')
    expect(product.name).toBe('日本 eSIM 与流量卡')
    expect(product.offers).toMatchObject({ '@type': 'AggregateOffer', lowPrice: '19.00', highPrice: '99.50', offerCount: 4, priceCurrency: 'CNY' })
  })

  it('套餐页：Product + Offer，地址是套餐页 canonical', async () => {
    const { text } = await html('/esim/jp/1002')
    expect(headValue(text, 'link', 'canonical')).toBe(`${SITE_URL}/esim/jp/1002`)
    const product = jsonLd(text, 'Product')
    expect(product).toMatchObject({ name: '日本 eSIM 7 天 5GB', sku: '1002' })
    expect(product.offers).toMatchObject({ '@type': 'Offer', price: '39.00', priceCurrency: 'CNY', url: `${SITE_URL}/esim/jp/1002` })
  })
})

describe('站点地图与 robots', () => {
  it('sitemap.xml：静态页、全部在售目的地与套餐、帮助话题与文章；不含账户与下单页', async () => {
    const { status, text, type } = await html('/sitemap.xml')
    expect(status).toBe(200)
    expect(type).toContain('xml')
    const locs = [...text.matchAll(/<loc>([^<]+)<\/loc>/g)].map((m) => m[1]!.replace(/&amp;/g, '&'))
    for (const path of ['/', '/esim', '/help', '/help/devices', '/esim/jp', '/esim/eu33', '/esim/global', '/esim/jp/1002', '/help/7001', '/help/7101', '/help?category=9100000000000003002']) {
      expect(locs, path).toContain(`${SITE_URL}${path}`)
    }
    for (const path of ['/login', '/checkout', '/cards', '/orders', '/account', '/pay', '/payDone', '/help/7003', '/help/7201']) {
      expect(locs.some((l) => l === `${SITE_URL}${path}` || l.startsWith(`${SITE_URL}${path}/`)), path).toBe(false)
    }
  })

  it('robots.txt：放行公开页，拦账户与下单页，指向站点地图', async () => {
    const { status, text } = await html('/robots.txt')
    expect(status).toBe(200)
    expect(text).toContain('User-agent: *')
    for (const path of ['/login', '/checkout', '/pay', '/payDone', '/cards', '/orders', '/account']) expect(text).toContain(`Disallow: ${path}`)
    expect(text).toContain(`Sitemap: ${SITE_URL}/sitemap.xml`)
  })
})
