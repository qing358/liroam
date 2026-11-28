<script setup lang="ts">
import {
  HELP_CATEGORY,
  articleCategories,
  articleText,
  categoryArticles,
  helpArticle,
  helpCategoryName,
  helpTopics,
  searchHelp,
} from '~/services/help'
import { faqJsonLd } from '~/utils/seo'
import { SITE } from '~/utils/site'

// 帮助中心（设计稿 15）：服务端渲染 + swr。三种视图都在查询串里：首页（话题与常见问题）、?category= 分类文章、?q= 按标题搜索
definePageMeta({ key: (route) => route.fullPath })

const { $buyerApi } = useNuxtApp()
const route = useRoute()
const q = String(route.query.q ?? '').trim()
const category = String(route.query.category ?? '')

const { data, error } = await useAsyncData(`help:${q}:${category}`, withPageError(async () => {
  const tree = await articleCategories($buyerApi)
  if (q) return { tree, view: 'search' as const, articles: await searchHelp($buyerApi, tree, q), faq: [] }
  if (category) {
    if (!helpCategoryName(tree, category)) return null
    return { tree, view: 'category' as const, articles: await categoryArticles($buyerApi, category), faq: [] }
  }
  const articles = await categoryArticles($buyerApi, HELP_CATEGORY.faq, 10)
  const full = await Promise.all(articles.map((a) => helpArticle($buyerApi, a.id)))
  const faq = full.flatMap((a) => (a ? [{ question: a.title, answer: articleText(a.content) }] : []))
  return { tree, view: 'home' as const, articles, faq }
}))
if (error.value) throw error.value
if (!data.value) throw createError({ statusCode: 404, statusMessage: '这个帮助分类不存在', fatal: true })

const topics = computed(() => helpTopics(data.value!.tree))
const categoryName = computed(() => helpCategoryName(data.value!.tree, category))

if (data.value.view === 'category') {
  usePageSeo({ title: categoryName.value!, titleTemplate: SITE.helpTitleTemplate, path: `/help?category=${category}` })
}
else {
  usePageSeo({ title: '帮助中心', path: '/help', jsonLd: data.value.faq.length ? faqJsonLd(data.value.faq) : null })
}

const keyword = ref(q)
function search() {
  const value = keyword.value.trim()
  navigateTo({ path: '/help', query: value ? { q: value } : {} })
}
</script>

<template>
  <div>
    <section class="bg-(--lr-ink) py-8 text-(--lr-mist) md:py-14">
      <UContainer class="max-w-3xl">
        <h1 class="text-[26px] font-semibold text-inverted md:text-4xl">
          需要什么帮助？
        </h1>
        <p class="mb-5 mt-2 opacity-80">
          安装、用量、加包、退款，都可以在这里找到答案
        </p>
        <form action="/help" method="get" role="search" @submit.prevent="search">
          <UInput
            v-model="keyword"
            name="q"
            size="xl"
            icon="i-lucide-search"
            placeholder="搜索问题，如 安装、没有信号、加包"
            class="w-full"
            :ui="{ base: 'rounded-full' }"
            aria-label="搜索问题"
          />
        </form>
      </UContainer>
    </section>

    <UContainer class="space-y-10 py-8">
      <template v-if="data!.view === 'home'">
        <div v-if="topics.length" class="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          <NuxtLink
            v-for="t in topics"
            :key="t.id"
            :to="t.to"
            class="flex items-center gap-3.5 rounded-(--ui-radius) border border-muted bg-default p-4 hover:border-(--ui-border-accented)"
          >
            <span class="flex size-10 flex-none items-center justify-center rounded-full bg-(--lr-mist)"><UIcon :name="t.icon" class="size-5 text-primary" /></span>
            <span class="min-w-0">
              <b class="block font-medium text-highlighted">{{ t.name }}</b>
              <span v-if="t.hint" class="text-sm text-muted">{{ t.hint }}</span>
            </span>
          </NuxtLink>
        </div>

        <section v-if="data!.articles.length">
          <h2 class="mb-3 text-lg font-semibold text-highlighted">
            常见问题
          </h2>
          <HelpArticleList :articles="data!.articles" />
        </section>
      </template>

      <section v-else-if="data!.view === 'category'">
        <nav class="mb-3 text-sm text-muted" aria-label="位置">
          <NuxtLink to="/help">帮助中心</NuxtLink> / {{ categoryName }}
        </nav>
        <h2 class="mb-3 text-lg font-semibold text-highlighted">
          {{ categoryName }}
        </h2>
        <HelpArticleList v-if="data!.articles.length" :articles="data!.articles" />
        <p v-else class="py-6 text-center text-muted">
          这个分类还没有文章
        </p>
      </section>

      <section v-else>
        <HelpArticleList v-if="data!.articles.length" :articles="data!.articles" />
        <div v-else class="py-10 text-center">
          <p class="font-medium text-highlighted">
            没有找到「{{ q }}」
          </p>
          <p class="mt-1 text-sm text-muted">
            换个关键词试试
          </p>
        </div>
      </section>

      <SupportCard />
    </UContainer>
  </div>
</template>
