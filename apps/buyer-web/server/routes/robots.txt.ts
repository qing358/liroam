import { absoluteUrl } from '~/utils/seo'

// robots.txt：不进站点地图的页面（nuxt.config routeRules 里 sitemap: false 的账户与下单页）一并拦住爬虫，其余放行；站点地图用站点对外地址
export default defineEventHandler((event) => {
  const rules = (useRuntimeConfig(event).nitro?.routeRules ?? {}) as Record<string, { sitemap?: unknown }>
  const disallow = Object.entries(rules)
    .filter(([, rule]) => rule.sitemap === false)
    .map(([path]) => path.replace(/\/\*\*$/, ''))
  setResponseHeader(event, 'content-type', 'text/plain; charset=utf-8')
  return [
    'User-agent: *',
    ...disallow.map((path) => `Disallow: ${path}`),
    '',
    `Sitemap: ${absoluteUrl(getSiteConfig(event).url, '/sitemap.xml')}`,
    '',
  ].join('\n')
})
