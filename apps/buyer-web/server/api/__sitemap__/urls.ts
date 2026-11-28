import { defineSitemapEventHandler } from '#sitemap/server'
import { destinationPlans, listDestinations, type Plan } from '~/services/destinations'
import { articleCategories, articleCategoryIds, categoryArticles } from '~/services/help'
import { sitemapEntries } from '~/utils/seo'

// 站点地图的动态页（@nuxtjs/sitemap 数据源）：K3 在售目的地与套餐、帮助中心话题与文章；结果由模块按 cacheMaxAgeSeconds 缓存
export default defineSitemapEventHandler(async (event) => {
  const api = publicBuyerApi(event)
  const [destinations, tree] = await Promise.all([listDestinations(api), articleCategories(api)])

  const plans: Record<string, Plan[]> = {}
  for (let i = 0; i < destinations.length; i += 8) {
    await Promise.all(destinations.slice(i, i + 8).map(async (d) => {
      plans[d.code] = (await destinationPlans(api, d.code).catch(() => null))?.plans ?? []
    }))
  }
  const articles = (await Promise.all(articleCategoryIds(tree).map((id) => categoryArticles(api, id, 100)))).flat()

  return sitemapEntries({ destinations, plans, help: { tree, articles } })
})
