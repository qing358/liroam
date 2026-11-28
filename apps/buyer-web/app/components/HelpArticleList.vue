<script setup lang="ts">
import type { ArticleSummary } from '~/services/help'
import { articlePath } from '~/utils/seo'

// 帮助文章链接列表（常见问题、分类、搜索结果、相关问题共用）
defineProps<{ articles: Pick<ArticleSummary, 'id' | 'title'>[] }>()
</script>

<template>
  <ul class="divide-y divide-(--ui-border-muted) rounded-(--ui-radius) border border-muted bg-default">
    <li v-for="a in articles" :key="a.id">
      <NuxtLink :to="articlePath(a.id)" class="flex items-center justify-between gap-3 px-4 py-3 text-sm">
        {{ a.title }}<UIcon name="i-lucide-chevron-right" class="size-4 flex-none text-dimmed" />
      </NuxtLink>
    </li>
  </ul>
</template>
