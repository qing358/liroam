<script setup lang="ts">
import { HELP_CATEGORY, SUPPORT_LABEL, categoryArticles, deviceBrands, helpArticle, searchDevices, type DeviceSupport } from '~/services/help'
import { faqJsonLd } from '~/utils/seo'

// 设备兼容（设计稿 16）：服务端渲染 + swr。机型清单只来自运营在「设备兼容」分类发布的文章（每篇一个品牌），没有发布时只显示确认方法
definePageMeta({ hideTabs: true })

const { $buyerApi } = useNuxtApp()

const { data: brands, error } = await useAsyncData('help:devices', withPageError(async () => {
  const list = await categoryArticles($buyerApi, HELP_CATEGORY.devices)
  const full = await Promise.all(list.map((a) => helpArticle($buyerApi, a.id)))
  return deviceBrands(full.filter((a) => a !== null))
}), { default: () => [] })
if (error.value) throw error.value

const checks = [
  { title: '拨号界面输入 *#06#', text: '显示 EID 一栏，说明手机支持 eSIM' },
  { title: '确认手机没有锁网', text: '运营商合约机可能只能用该运营商的卡，可咨询购机渠道' },
]

usePageSeo({
  title: '支持 eSIM 的手机',
  path: '/help/devices',
  jsonLd: faqJsonLd([{ question: '我的手机支持 eSIM 吗', answer: checks.map((c) => `${c.title}：${c.text}。`).join('') }]),
})

const brand = ref(brands.value[0]?.brand ?? '')
const keyword = ref('')
const found = computed(() => searchDevices(brands.value, keyword.value))

const BADGE: Record<DeviceSupport, 'primary' | 'warning' | 'neutral'> = { FULL: 'primary', PARTIAL: 'warning', NONE: 'neutral' }
</script>

<template>
  <div>
    <BackBar title="设备兼容" fallback="/help" />
    <UContainer class="max-w-3xl space-y-4 py-4 md:py-8">
      <nav class="text-sm text-muted max-md:hidden" aria-label="位置">
        <NuxtLink to="/help">帮助中心</NuxtLink> / 设备兼容
      </nav>
      <h1 class="text-xl font-semibold text-highlighted md:text-2xl">
        我的手机支持 eSIM 吗
      </h1>
      <UCard>
        <h2 class="mb-3 font-semibold text-highlighted">
          最快的确认方法
        </h2>
        <ol class="space-y-3">
          <li v-for="(c, i) in checks" :key="c.title" class="flex gap-3">
            <span class="flex size-6 flex-none items-center justify-center rounded-full bg-(--lr-mist) text-xs font-semibold text-primary">{{ i + 1 }}</span>
            <div>
              <h3 class="font-medium text-highlighted">
                {{ c.title }}
              </h3>
              <p class="text-sm text-muted">
                {{ c.text }}
              </p>
            </div>
          </li>
        </ol>
      </UCard>

      <template v-if="brands.length">
        <UInput v-model="keyword" icon="i-lucide-search" size="lg" placeholder="搜索机型，如 iPhone 15、Mate 60" class="w-full" aria-label="搜索机型" />

        <template v-if="keyword.trim()">
          <ul v-if="found.length" class="divide-y divide-(--ui-border-muted) rounded-(--ui-radius) border border-muted bg-default">
            <li v-for="m in found" :key="`${m.brand}-${m.name}`" class="flex items-center gap-3 px-4 py-3 text-sm">
              <span class="flex-1">{{ m.brand }} {{ m.name }}<span v-if="m.note" class="block text-muted">{{ m.note }}</span></span>
              <UBadge :label="SUPPORT_LABEL[m.support]" :color="BADGE[m.support]" variant="soft" />
            </li>
          </ul>
          <p v-else class="py-6 text-center text-muted">
            没有找到「{{ keyword.trim() }}」
          </p>
        </template>

        <template v-else>
          <div class="flex gap-2 overflow-x-auto pb-0.5" aria-label="品牌">
            <UButton
              v-for="b in brands"
              :key="b.brand"
              size="sm"
              class="rounded-full"
              :variant="brand === b.brand ? 'soft' : 'outline'"
              :color="brand === b.brand ? 'primary' : 'neutral'"
              :aria-pressed="brand === b.brand"
              :label="b.brand"
              @click="brand = b.brand"
            />
          </div>
          <ul
            v-for="b in brands"
            v-show="brand === b.brand"
            :key="b.brand"
            class="divide-y divide-(--ui-border-muted) rounded-(--ui-radius) border border-muted bg-default"
          >
            <li v-for="m in b.models" :key="m.name" class="flex items-center gap-3 px-4 py-3 text-sm">
              <span class="flex-1">{{ m.name }}<span v-if="m.note" class="block text-muted">{{ m.note }}</span></span>
              <UBadge :label="SUPPORT_LABEL[m.support]" :color="BADGE[m.support]" variant="soft" />
            </li>
          </ul>
        </template>
      </template>

      <p class="flex gap-2.5 rounded-md bg-(--lr-mist) px-3.5 py-3 text-sm text-(--lr-primary-active)">
        <UIcon name="i-lucide-info" class="mt-0.5 size-4 flex-none" />
        <span>手机不支持 eSIM？可以选择实体卡套餐，下单后寄送到家。</span>
      </p>
    </UContainer>
  </div>
</template>
