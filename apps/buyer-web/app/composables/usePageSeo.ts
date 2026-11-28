import { absoluteUrl, jsonLdScript } from '~/utils/seo'
import { SITE } from '~/utils/site'

/** 全站默认（app.vue 调一次）：品牌描述与分享卡片，页面的 usePageSeo 同键覆盖 */
export function useSiteSeoDefaults() {
  const site = useSiteConfig()
  useSeoMeta({
    description: SITE.description,
    ogSiteName: SITE.name,
    ogLocale: 'zh_CN',
    ogType: 'website',
    ogImage: () => absoluteUrl(site.url, SITE.shareImage),
    twitterCard: 'summary_large_image',
  })
}

export interface PageSeo {
  /** 页面标题（不含站点后缀，后缀由 titleTemplate 加） */
  title: MaybeRefOrGetter<string>
  description?: MaybeRefOrGetter<string>
  /** canonical 路径；缺省为当前路径（不带查询串） */
  path?: MaybeRefOrGetter<string>
  /** 标题后缀模板，缺省为 SITE.titleTemplate（nuxt.config 同一份） */
  titleTemplate?: string
  /** schema.org 结构化数据（utils/seo.ts 的构造函数） */
  jsonLd?: MaybeRefOrGetter<object | null | undefined>
}

/** 可索引页面的 SEO 唯一出口：title / description / canonical / Open Graph / 结构化数据 */
export function usePageSeo(input: PageSeo) {
  const site = useSiteConfig()
  const route = useRoute()
  const url = computed(() => absoluteUrl(site.url, toValue(input.path) ?? route.path))
  const template = input.titleTemplate ?? SITE.titleTemplate
  const description = () => toValue(input.description) ?? SITE.description

  useSeoMeta({
    title: input.title,
    description,
    ogTitle: () => template.replace('%s', toValue(input.title)),
    ogDescription: description,
    ogUrl: url,
  })
  useHead({
    ...(input.titleTemplate ? { titleTemplate: input.titleTemplate } : {}),
    link: [{ key: 'canonical', rel: 'canonical', href: url }],
    script: computed(() => {
      const data = toValue(input.jsonLd)
      return data ? [{ key: 'ld-json', type: 'application/ld+json', innerHTML: jsonLdScript(data) }] : []
    }),
  })
}
