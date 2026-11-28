<script setup lang="ts">
// 帮助中心客服卡片（设计稿 15）：在线时间、在线客服链接、电话均为运营配置（nuxt.config runtimeConfig.public.support*），只出已配置的项
const config = useRuntimeConfig().public
const hours = String(config.supportHours ?? '')
const phone = String(config.supportPhone ?? '')
const chatUrl = String(config.supportChatUrl ?? '')
const shown = Boolean(hours || phone || chatUrl)
</script>

<template>
  <UCard v-if="shown" id="contact">
    <div class="flex flex-wrap items-center justify-between gap-4">
      <div>
        <h3 class="font-semibold text-highlighted">
          没找到答案？
        </h3>
        <p v-if="hours" class="text-sm text-muted">
          客服在线时间 {{ hours }}
        </p>
      </div>
      <div class="flex gap-2">
        <UButton v-if="chatUrl" :to="chatUrl" target="_blank" icon="i-lucide-message-circle" label="在线客服" />
        <UButton v-if="phone" :to="`tel:${phone}`" variant="outline" icon="i-lucide-phone" label="电话客服" />
      </div>
    </div>
  </UCard>
</template>
