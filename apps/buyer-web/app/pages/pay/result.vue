<script setup lang="ts">
// 支付结果：本站收银台轮询到已付款后进入（交码进度由 WS11 接 /buyer/liroam/cards）；支付宝电脑网站支付付款后回跳 BASE_SETTING.staticPageAddress + /payDone（壳约定路径）
definePageMeta({ alias: ['/payDone'] })
useHead({ title: '支付完成' })
</script>

<template>
  <UContainer class="py-16 max-w-md text-center space-y-4">
    <UIcon name="i-lucide-circle-check" class="size-14 text-success" />
    <h1 class="text-xl font-semibold text-highlighted">
      支付成功
    </h1>
    <p class="text-muted">
      卡片正在准备中，完成后可在我的卡里查看安装方式。
    </p>
    <div class="flex justify-center gap-2">
      <UButton to="/cards" label="查看我的卡" />
      <UButton to="/" variant="outline" color="neutral" label="继续选购" />
    </div>
  </UContainer>
</template>
