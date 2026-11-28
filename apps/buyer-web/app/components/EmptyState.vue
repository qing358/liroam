<script setup lang="ts">
// 空态与异常卡片（设计稿 21）：圆标图标、标题、说明，操作按钮放默认插槽
defineProps<{ icon: string, title: string, text?: string }>()
</script>

<template>
  <div class="mx-auto flex max-w-md flex-col items-center gap-2 px-4 py-10 text-center">
    <span class="mb-1 flex size-14 items-center justify-center rounded-full bg-(--lr-mist)">
      <UIcon :name="icon" class="size-6 text-primary" />
    </span>
    <h1 class="text-lg font-semibold text-highlighted">
      {{ title }}
    </h1>
    <p v-if="text" class="text-sm text-muted">
      {{ text }}
    </p>
    <div class="mt-3 flex flex-wrap justify-center gap-2">
      <slot />
    </div>
  </div>
</template>
