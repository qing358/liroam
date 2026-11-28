// SEO 公共出口的纯函数部分：站点口径、绝对地址、结构化数据（schema.org）与站点地图条目。
// 页面只调 composables/usePageSeo.ts；站点对外地址只来自站点配置（NUXT_SITE_URL，nuxt-site-config）。
import type { Destination, Plan } from '~/services/destinations'
import { helpTopics, type ArticleCategory, type ArticleSummary } from '~/services/help'
import { SITE } from './site'

export function absoluteUrl(siteUrl: string, path: string): string {
  return `${siteUrl.replace(/\/+$/, '')}${path.startsWith('/') ? path : `/${path}`}`
}

const yuan = (fen: number) => (fen / 100).toFixed(2)

const CONTEXT = 'https://schema.org'
const IN_STOCK = 'https://schema.org/InStock'

interface ProductInput {
  name: string
  description: string
  url: string
  image: string
}

const product = ({ name, description, image }: Omit<ProductInput, 'url'>) => ({
  '@context': CONTEXT,
  '@type': 'Product',
  name,
  description,
  image,
  brand: { '@type': 'Brand', name: SITE.name },
})

/** 套餐页：一个 SKU 一个 Offer，价格只取壳 SKU 价（K3 priceFen） */
export function planJsonLd(input: ProductInput & { plan: Pick<Plan, 'skuId' | 'priceFen'> }) {
  const { name, description, image, url, plan } = input
  return {
    ...product({ name, description, image }),
    sku: plan.skuId,
    offers: {
      '@type': 'Offer',
      price: yuan(plan.priceFen),
      priceCurrency: 'CNY',
      availability: IN_STOCK,
      url,
      seller: { '@type': 'Organization', name: SITE.company },
    },
  }
}

/** 目的地页：该目的地全部在售套餐汇成一个 AggregateOffer */
export function destinationJsonLd(input: ProductInput & { plans: Pick<Plan, 'priceFen'>[] }) {
  const prices = input.plans.map((p) => p.priceFen)
  return {
    ...product(input),
    offers: {
      '@type': 'AggregateOffer',
      lowPrice: yuan(Math.min(...prices)),
      highPrice: yuan(Math.max(...prices)),
      offerCount: prices.length,
      priceCurrency: 'CNY',
      availability: IN_STOCK,
    },
  }
}

export function faqJsonLd(items: { question: string, answer: string }[]) {
  return {
    '@context': CONTEXT,
    '@type': 'FAQPage',
    mainEntity: items.map((i) => ({ '@type': 'Question', name: i.question, acceptedAnswer: { '@type': 'Answer', text: i.answer } })),
  }
}

/** 写进 <script type="application/ld+json"> 的文本：转义 <，正文里的 </script> 不会截断标签 */
export const jsonLdScript = (data: unknown) => JSON.stringify(data).replace(/</g, '\\u003c')

export const destinationPath = (code: string) => `/esim/${code.toLowerCase()}`
export const planPath = (code: string, skuId: string) => `${destinationPath(code)}/${skuId}`
export const articlePath = (id: string) => `/help/${id}`

export interface SitemapEntry {
  loc: string
}

/** 站点地图里的动态页：全部在售目的地与其套餐、帮助话题分类（设备兼容是静态页、常见问题在帮助首页）与帮助文章 */
export function sitemapEntries(input: {
  destinations: Pick<Destination, 'code'>[]
  plans: Record<string, Pick<Plan, 'skuId'>[]>
  help: { tree: ArticleCategory[], articles: Pick<ArticleSummary, 'id'>[] }
}): SitemapEntry[] {
  const { destinations, plans, help } = input
  return [
    ...destinations.flatMap((d) => [destinationPath(d.code), ...(plans[d.code] ?? []).map((p) => planPath(d.code, p.skuId))]),
    ...helpTopics(help.tree).map((t) => t.to).filter((to) => to.startsWith('/help?')),
    ...help.articles.map((a) => articlePath(a.id)),
  ].map((loc) => ({ loc }))
}
