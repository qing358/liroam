<script setup lang="ts">
// 手机下单与详情页的顶部返回栏（这些页在 definePageMeta 里设 hideTabs，底部换成页面自己的操作条）
const props = defineProps<{ title: string, fallback: string }>()
const router = useRouter()

function back() {
  if (import.meta.client && window.history.state?.back) router.back()
  else navigateTo(props.fallback)
}
</script>

<template>
  <div class="sticky top-0 z-40 flex h-12 items-center gap-2 border-b border-muted bg-default px-2 md:hidden">
    <UButton icon="i-lucide-chevron-left" variant="ghost" color="neutral" aria-label="返回" @click="back" />
    <h1 class="flex-1 truncate pr-10 text-center font-semibold text-highlighted">
      {{ title }}
    </h1>
  </div>
</template>
