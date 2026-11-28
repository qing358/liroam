<script setup lang="ts">
import { articleCategories, articleHtml, articleText, categoryArticles, helpArticle, helpCategoryName, helpTopics, categoryPath } from '~/services/help'
import { articlePath, faqJsonLd } from '~/utils/seo'
import { SITE } from '~/utils/site'

// 帮助文章（设计稿 17）：服务端渲染 + swr。只展示帮助中心分类里已发布的文章，正文清洗后渲染
definePageMeta({ hideTabs: true })

const { $buyerApi } = useNuxtApp()
const route = useRoute()
const id = String(route.params.id)

const { data, error } = await useAsyncData(`help:article:${id}`, withPageError(async () => {
  const [tree, article] = await Promise.all([articleCategories($buyerApi), helpArticle($buyerApi, id)])
  const categoryName = article ? helpCategoryName(tree, article.categoryId) : null
  if (!article || !categoryName) return null
  const related = (await categoryArticles($buyerApi, article.categoryId, 6)).filter((a) => a.id !== id).slice(0, 5)
  return { tree, article, categoryName, related }
}))
if (error.value) throw error.value
if (!data.value) throw createError({ statusCode: 404, statusMessage: '这篇帮助已下线或不存在', fatal: true })

const article = computed(() => data.value!.article)
const topics = computed(() => helpTopics(data.value!.tree))
const body = computed(() => articleHtml(article.value.content))
const text = articleText(article.value.content)

usePageSeo({
  title: article.value.title,
  titleTemplate: SITE.helpTitleTemplate,
  description: articleText(article.value.content, 120),
  path: articlePath(id),
  jsonLd: faqJsonLd([{ question: article.value.title, answer: text }]),
})

const feedback = ref<'yes' | 'no' | null>(null)
</script>

<template>
  <div>
    <BackBar title="帮助" fallback="/help" />
    <UContainer class="py-4 md:py-8">
      <div class="grid gap-6 lg:grid-cols-[220px_1fr]">
        <aside class="max-lg:hidden">
          <ul class="divide-y divide-(--ui-border-muted) rounded-(--ui-radius) border border-muted bg-default text-sm">
            <li v-for="t in topics" :key="t.id">
              <NuxtLink :to="t.to" class="block px-4 py-3" :class="t.id === article.categoryId ? 'font-semibold text-primary' : ''">
                {{ t.name }}
              </NuxtLink>
            </li>
          </ul>
        </aside>

        <UCard as="article" class="min-w-0">
          <nav class="text-sm text-muted" aria-label="位置">
            <NuxtLink to="/help">帮助中心</NuxtLink> / <NuxtLink :to="categoryPath(article.categoryId)">{{ data!.categoryName }}</NuxtLink>
          </nav>
          <h1 class="mt-2 text-xl font-semibold text-highlighted md:text-2xl">
            {{ article.title }}
          </h1>
          <!-- eslint-disable-next-line vue/no-v-html -- 运营富文本，已经 articleHtml 清洗 -->
          <div class="help-article mt-4 text-toned" v-html="body" />

          <div class="mt-6 flex flex-wrap items-center justify-between gap-3 border-t border-muted pt-4">
            <span class="text-sm text-muted">这篇内容有帮助吗？</span>
            <span v-if="feedback === 'yes'" class="text-sm text-primary">感谢你的反馈</span>
            <div v-else-if="feedback === null" class="flex gap-2">
              <UButton size="sm" variant="outline" color="neutral" label="有帮助" @click="feedback = 'yes'" />
              <UButton size="sm" variant="outline" color="neutral" label="没解决" @click="feedback = 'no'" />
            </div>
          </div>
          <div v-if="feedback === 'no'" class="mt-4">
            <SupportCard />
          </div>

          <section v-if="data!.related.length" class="mt-6">
            <h2 class="mb-2 font-semibold text-highlighted">
              相关问题
            </h2>
            <HelpArticleList :articles="data!.related" />
          </section>
        </UCard>
      </div>
    </UContainer>
  </div>
</template>
