<script setup lang="ts">
import type { NuxtError } from '#app'
import { errorView } from '~/utils/error-view'

// 404、功能未开放、其它错误（设计稿 21）；状态与文案只来自 utils/error-view.ts，HTTP 状态码保持原样
const props = defineProps<{ error: NuxtError }>()
const view = computed(() => errorView(props.error))

useHead({ title: () => view.value.title, meta: [{ name: 'robots', content: 'noindex' }] })

</script>

<template>
  <UApp>
    <NuxtLayout>
      <UContainer class="py-10 md:py-16">
        <UCard>
          <EmptyState :icon="view.icon" :title="view.title" :text="view.text">
            <UButton v-if="view.kind === 'retry'" variant="outline" :label="view.action" @click="reloadNuxtApp({ persistState: false })" />
            <UButton v-else to="/" :label="view.action" @click="clearError()" />
          </EmptyState>
        </UCard>
      </UContainer>
    </NuxtLayout>
  </UApp>
</template>
