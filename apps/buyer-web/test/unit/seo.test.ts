import { describe, expect, it } from 'vitest'
import { SITE } from '~/utils/site'
import {
  absoluteUrl,
  destinationJsonLd,
  faqJsonLd,
  jsonLdScript,
  planJsonLd,
  sitemapEntries,
} from '~/utils/seo'
import { destinations, japanPlans } from '../fixtures/k3'
import { categoryTree, summaries } from '../fixtures/help'

const site = 'https://www.liroam.example'

describe('绝对地址', () => {
  it('站点地址末尾斜杠与路径拼接不重复，查询串保留', () => {
    expect(absoluteUrl(`${site}/`, '/esim/jp')).toBe(`${site}/esim/jp`)
    expect(absoluteUrl(site, '/')).toBe(`${site}/`)
    expect(absoluteUrl(site, '/help?category=1')).toBe(`${site}/help?category=1`)
  })
})

describe('结构化数据', () => {
  const plan = japanPlans.plans[1]!

  it('套餐页：Product + Offer，人民币两位小数、有货、卖家是公司，不含供应商字段', () => {
    const ld = planJsonLd({ name: '日本 eSIM 7 天 5GB', description: '说明', url: `${site}/esim/jp/1002`, image: `${site}/brand/og.png`, plan })
    expect(ld).toEqual({
      '@context': 'https://schema.org',
      '@type': 'Product',
      name: '日本 eSIM 7 天 5GB',
      description: '说明',
      sku: '1002',
      image: `${site}/brand/og.png`,
      brand: { '@type': 'Brand', name: '轻游' },
      offers: {
        '@type': 'Offer',
        price: '39.00',
        priceCurrency: 'CNY',
        availability: 'https://schema.org/InStock',
        url: `${site}/esim/jp/1002`,
        seller: { '@type': 'Organization', name: SITE.company },
      },
    })
  })

  it('目的地页：Product + AggregateOffer，最低最高价与款数', () => {
    const ld = destinationJsonLd({ name: '日本 eSIM 与流量卡', description: '说明', url: `${site}/esim/jp`, image: `${site}/brand/og.png`, plans: japanPlans.plans })
    expect(ld.offers).toEqual({
      '@type': 'AggregateOffer',
      lowPrice: '19.00',
      highPrice: '99.50',
      offerCount: 4,
      priceCurrency: 'CNY',
      availability: 'https://schema.org/InStock',
    })
    expect(ld['@type']).toBe('Product')
  })

  it('帮助页：FAQPage，每条问答一个 Question', () => {
    expect(faqJsonLd([{ question: '问一', answer: '答一' }])).toEqual({
      '@context': 'https://schema.org',
      '@type': 'FAQPage',
      mainEntity: [{ '@type': 'Question', name: '问一', acceptedAnswer: { '@type': 'Answer', text: '答一' } }],
    })
  })

  it('写进 script 时转义 <，正文里的 </script> 不会提前结束标签', () => {
    const text = jsonLdScript(faqJsonLd([{ question: '</script><b>', answer: 'a' }]))
    expect(text).not.toContain('</script>')
    expect(JSON.parse(text).mainEntity[0].name).toBe('</script><b>')
  })
})

describe('站点地图条目', () => {
  it('在售目的地、各套餐、帮助话题分类与文章；设备兼容与常见问题分类不单列', () => {
    const entries = sitemapEntries({
      destinations,
      plans: { JP: japanPlans.plans },
      help: { tree: categoryTree, articles: summaries().filter((a) => !a.title.startsWith('演示') && a.articleCategoryName !== '平台信息') },
    })
    const locs = entries.map((e) => e.loc)
    expect(locs).toContain('/esim/jp')
    expect(locs).toContain('/esim/global')
    expect(locs).toContain('/esim/jp/1004')
    expect(locs).toContain('/help?category=9100000000000003002')
    expect(locs).toContain('/help/7001')
    expect(locs).not.toContain('/help?category=9100000000000003001')
    expect(locs).not.toContain('/help?category=9100000000000003009')
    expect(locs.filter((l) => l.startsWith('/esim/')).length).toBe(destinations.length + japanPlans.plans.length)
  })
})
