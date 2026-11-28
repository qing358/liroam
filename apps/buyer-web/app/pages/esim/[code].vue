<script setup lang="ts">
// 目的地页骨架（服务端渲染 + swr 缓存）；套餐列表由 WS11 接 K3 /buyer/liroam/destinations/{code}/plans
const route = useRoute()
const code = computed(() => String(route.params.code).toUpperCase())
useHead({ title: () => `${code.value} 上网卡` })
</script>

<template>
  <UContainer class="py-10">
    <h1 class="text-2xl font-semibold text-highlighted">
      {{ code }} 上网卡
    </h1>
    <p class="mt-2 text-muted">
      套餐即将上线。
    </p>
  </UContainer>
</template>
