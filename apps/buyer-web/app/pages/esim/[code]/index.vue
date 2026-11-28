<script setup lang="ts">
import {
  PLAN_CHIPS,
  daySegments,
  destinationNetworks,
  destinationPlans,
  destinationSubtitle,
  destinationTitle,
  filterPlans,
  type PlanChip,
} from '~/services/destinations'

// 目的地页：服务端渲染 + swr，套餐卡片与价格在页面源码里（关 JS 可见）；天数与筛选在浏览器里切换
definePageMeta({ hideTabs: true })

const { $buyerApi } = useNuxtApp()
const route = useRoute()
const code = computed(() => String(route.params.code))

const { data, error } = await useAsyncData(() => `plans:${code.value.toUpperCase()}`, () => destinationPlans($buyerApi, code.value))
if (error.value || !data.value) {
  throw createError({ statusCode: 404, statusMessage: error.value?.message || '这个目的地暂时没有在售套餐', fatal: true })
}

const destination = computed(() => data.value!.destination)
const plans = computed(() => data.value!.plans)
const networks = computed(() => destinationNetworks(plans.value))
const segments = computed(() => daySegments(plans.value))

const segment = ref('all')
const chips = ref<PlanChip[]>([])
const shown = computed(() => filterPlans(plans.value, { segment: segment.value, chips: chips.value }))
function toggle(chip: PlanChip) {
  chips.value = chips.value.includes(chip) ? chips.value.filter((c) => c !== chip) : [...chips.value, chip]
}

const title = computed(() => destinationTitle(destination.value))
useSeoMeta({
  title,
  description: '轻游 Liroam 出境旅行通信卡：按目的地选购 eSIM 与实体卡，付款后在线发码，随时查询用量、续费加包。',
})

const faq = ['什么时候安装最合适？', '流量用完了怎么办？', '可以和国内手机号同时使用吗？']
</script>

<template>
  <div>
    <BackBar :title="destination.nameZh" fallback="/esim" />
    <UContainer class="py-4 md:py-8">
      <nav class="mb-3 text-sm text-muted max-md:hidden" aria-label="位置">
        <NuxtLink to="/">首页</NuxtLink> / <NuxtLink to="/esim">全部目的地</NuxtLink> / {{ destination.nameZh }}
      </nav>
      <div class="flex items-center gap-3.5">
        <DestinationCode :code="destination.code" :kind="destination.kind" size="lg" />
        <div class="min-w-0 flex-1">
          <h1 class="text-xl font-semibold text-highlighted md:text-2xl">
            {{ title }}
          </h1>
          <p class="text-sm text-toned">
            {{ destinationSubtitle(destination, networks) }}
          </p>
        </div>
      </div>

      <div class="mt-5 grid gap-6 lg:grid-cols-[1fr_320px]">
        <div class="min-w-0 space-y-4">
          <div v-if="segments.length > 2" class="inline-flex max-w-full gap-0.5 overflow-x-auto rounded-full bg-muted p-1" role="tablist" aria-label="天数">
            <button
              v-for="s in segments"
              :key="s.key"
              type="button"
              role="tab"
              :aria-selected="segment === s.key"
              class="whitespace-nowrap rounded-full px-3.5 py-1.5 text-sm"
              :class="segment === s.key ? 'bg-default font-semibold text-highlighted shadow-xs' : 'text-toned'"
              @click="segment = s.key"
            >
              {{ s.label }}
            </button>
          </div>
          <div class="flex gap-2 overflow-x-auto pb-0.5" aria-label="筛选">
            <UButton
              v-for="c in PLAN_CHIPS"
              :key="c.key"
              size="sm"
              class="rounded-full"
              :variant="chips.includes(c.key) ? 'soft' : 'outline'"
              :color="chips.includes(c.key) ? 'primary' : 'neutral'"
              :aria-pressed="chips.includes(c.key)"
              :label="c.label"
              @click="toggle(c.key)"
            />
          </div>

          <div v-if="shown.length" class="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
            <PlanCard v-for="p in shown" :key="p.skuId" :plan="p" :to="`/esim/${code.toLowerCase()}/${p.skuId}`" />
          </div>
          <div v-else class="py-10 text-center">
            <p class="text-muted">
              没有符合条件的套餐
            </p>
            <UButton class="mt-3" variant="outline" label="重置" @click="segment = 'all'; chips = []" />
          </div>
          <p class="text-sm text-muted">
            价格以下单页为准。实体卡需要寄送，请预留配送时间。
          </p>
        </div>

        <aside class="space-y-4 lg:sticky lg:top-20 lg:self-start">
          <UCard>
            <h2 class="mb-3 font-semibold text-highlighted">
              在{{ destination.nameZh }}使用
            </h2>
            <dl class="grid grid-cols-[auto_1fr] gap-x-4 gap-y-2 text-sm">
              <template v-if="networks.length">
                <dt class="text-muted">
                  网络
                </dt>
                <dd>{{ networks.join(' / ') }}</dd>
              </template>
              <dt class="text-muted">
                使用范围
              </dt>
              <dd>{{ destination.nameZh }}{{ destination.kind === 'COUNTRY' ? '全境' : '' }}，具体以套餐使用区域说明为准</dd>
              <dt class="text-muted">
                天数计算
              </dt>
              <dd>见各套餐详情</dd>
              <dt class="text-muted">
                电话号码
              </dt>
              <dd>仅含通话短信的套餐提供</dd>
            </dl>
            <NuxtLink to="/help/devices" class="mt-3 flex items-center gap-1.5 text-sm text-primary">
              <UIcon name="i-lucide-smartphone" class="size-4" />查看我的手机是否支持 eSIM
            </NuxtLink>
          </UCard>
          <UCard>
            <h2 class="mb-1 font-semibold text-highlighted">
              常见问题
            </h2>
            <NuxtLink v-for="f in faq" :key="f" to="/help" class="flex items-center justify-between py-2.5 text-sm">
              {{ f }}<UIcon name="i-lucide-chevron-right" class="size-4 text-dimmed" />
            </NuxtLink>
          </UCard>
        </aside>
      </div>
    </UContainer>
  </div>
</template>
