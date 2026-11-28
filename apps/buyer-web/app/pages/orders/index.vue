<script setup lang="ts">
// 订单骨架；WS12 接壳订单列表与 K4 卡区块
definePageMeta({ middleware: 'auth' })
useHead({ title: '订单' })
</script>

<template>
  <UContainer class="py-10">
    <h1 class="text-2xl font-semibold text-highlighted">
      订单
    </h1>
    <UEmpty class="mt-6" icon="i-lucide-receipt-text" title="暂无订单" />
  </UContainer>
</template>
