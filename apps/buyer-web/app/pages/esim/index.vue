<script setup lang="ts">
// 目的地搜索结果骨架；WS11 接 K3 /buyer/liroam/destinations?q=
const route = useRoute()
const q = computed(() => String(route.query.q ?? ''))
useHead({ title: '选目的地' })
</script>

<template>
  <UContainer class="py-10">
    <h1 class="text-2xl font-semibold text-highlighted">
      {{ q ? `搜索：${q}` : '全部目的地' }}
    </h1>
    <p class="mt-2 text-muted">
      目的地列表即将上线。
    </p>
  </UContainer>
</template>
