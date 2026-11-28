<script setup lang="ts">
// 我的卡骨架；WS12 接 /buyer/liroam/cards
definePageMeta({ middleware: 'auth' })
useHead({ title: '我的卡' })
</script>

<template>
  <UContainer class="py-10">
    <h1 class="text-2xl font-semibold text-highlighted">
      我的卡
    </h1>
    <UEmpty class="mt-6" icon="i-lucide-sim-card" title="还没有卡" description="选个目的地，买一张试试">
      <template #actions>
        <UButton to="/" label="去选目的地" />
      </template>
    </UEmpty>
  </UContainer>
</template>
