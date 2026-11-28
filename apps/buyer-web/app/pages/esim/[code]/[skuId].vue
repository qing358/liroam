<script setup lang="ts">
import { destinationPlans, planDataText, planDetailBadges, usageRegion } from '~/services/destinations'
import { formatPrice } from '~/utils/money'

// 套餐详情：服务端渲染；套餐字段取目的地页同一份 K3 数据，使用区域说明取商品详情同一接口的原文
definePageMeta({ hideTabs: true })

const { $buyerApi } = useNuxtApp()
const route = useRoute()
const code = String(route.params.code)
const skuId = String(route.params.skuId)

const { data } = await useAsyncData(`plan:${code.toUpperCase()}:${skuId}`, async () => {
  const [{ destination, plans }, usage] = await Promise.all([
    destinationPlans($buyerApi, code),
    usageRegion($buyerApi, skuId).catch(() => null),
  ])
  const plan = plans.find((p) => p.skuId === skuId)
  return plan ? { destination, plan, usage } : null
})
if (!data.value) throw createError({ statusCode: 404, statusMessage: '这款套餐已下架或不存在', fatal: true })

const destination = computed(() => data.value!.destination)
const plan = computed(() => data.value!.plan)
const name = computed(() => `${destination.value.nameZh} ${plan.value.form === 'PHYSICAL' ? '实体卡' : 'eSIM'} ${plan.value.days} 天 ${planDataText(plan.value)}`)
const usageText = computed(() => data.value!.usage?.fullText || plan.value.usageRegionNote)
const scope = computed(() => [destination.value.kind === 'COUNTRY' ? `${destination.value.nameZh}全境` : destination.value.nameZh, ...plan.value.networks].join(' · '))

const num = ref(1)
const total = computed(() => formatPrice(plan.value.priceFen * num.value))
const buy = computed(() => ({ path: '/checkout', query: { skuId, num: String(num.value), code: destination.value.code } }))

useSeoMeta({ title: name, description: () => `${name.value}，${formatPrice(plan.value.priceFen)}。${plan.value.usageRegionNote}` })

const install = [
  { title: '付款后获取激活码', text: '在支付结果页或「我的卡」查看二维码' },
  { title: '扫码或手动添加 eSIM', text: 'iPhone 与安卓手机均有分步指引' },
  { title: '落地开启数据漫游', text: '在「我的卡」随时查看剩余流量与到期时间' },
]
</script>

<template>
  <div>
    <BackBar title="套餐详情" :fallback="`/esim/${code}`" />
    <UContainer class="py-4 pb-28 md:py-8">
      <nav class="mb-3 text-sm text-muted max-md:hidden" aria-label="位置">
        <NuxtLink to="/">首页</NuxtLink> / <NuxtLink :to="`/esim/${code}`">{{ destination.nameZh }}</NuxtLink> / {{ plan.days }} 天 {{ planDataText(plan) }}
      </nav>
      <div class="grid gap-6 lg:grid-cols-[1fr_340px]">
        <div class="min-w-0 space-y-4">
          <UCard>
            <div class="flex items-center gap-3">
              <DestinationCode :code="destination.code" :kind="destination.kind" />
              <div class="min-w-0">
                <h1 class="text-xl font-semibold text-highlighted">
                  {{ name }}
                </h1>
                <p class="text-sm text-muted">
                  {{ scope }}
                </p>
              </div>
            </div>
            <div class="mt-3 flex flex-wrap gap-1.5">
              <UBadge v-for="(b, i) in planDetailBadges(plan)" :key="b" :label="b" :color="i === 0 ? 'primary' : 'neutral'" variant="soft" />
            </div>
            <div class="mt-4 grid grid-cols-3 text-center">
              <div>
                <p class="text-[22px] font-semibold text-highlighted">
                  {{ planDataText(plan) }}
                </p>
                <p class="text-sm text-muted">
                  高速流量
                </p>
              </div>
              <div>
                <p class="text-[22px] font-semibold text-highlighted">
                  {{ plan.days }} 天
                </p>
                <p class="text-sm text-muted">
                  有效天数
                </p>
              </div>
              <div>
                <p class="text-[22px] font-semibold text-(--lr-accent-text)">
                  {{ formatPrice(plan.priceFen) }}
                </p>
                <p class="text-sm text-muted">
                  售价
                </p>
              </div>
            </div>
          </UCard>

          <UCard>
            <h2 class="mb-2 text-lg font-semibold text-highlighted">
              使用区域说明
            </h2>
            <p class="whitespace-pre-line text-sm text-toned">
              {{ usageText }}
            </p>
          </UCard>

          <UCard>
            <h2 class="mb-3 text-lg font-semibold text-highlighted">
              购买前确认
            </h2>
            <div class="space-y-2 text-sm">
              <p v-if="plan.form !== 'PHYSICAL'" class="flex gap-2.5 rounded-md bg-(--lr-mist) px-3.5 py-3 text-(--lr-primary-active)">
                <UIcon name="i-lucide-smartphone" class="mt-0.5 size-4 flex-none" />
                <span>手机需支持 eSIM 且未锁网。<NuxtLink to="/help/devices" class="underline">查看支持的机型</NuxtLink></span>
              </p>
              <p class="flex gap-2.5 rounded-md bg-(--lr-accent-light-9) px-3.5 py-3">
                <UIcon name="i-lucide-info" class="mt-0.5 size-4 flex-none text-(--lr-accent-text)" />
                <span>出发前连上 Wi-Fi 安装，到达后开启这张卡的数据漫游即可上网。</span>
              </p>
            </div>
          </UCard>

          <UCard>
            <h2 class="mb-3 text-lg font-semibold text-highlighted">
              安装与使用
            </h2>
            <ol class="space-y-3">
              <li v-for="(s, i) in install" :key="s.title" class="flex gap-3">
                <span class="flex size-6 flex-none items-center justify-center rounded-full bg-(--lr-mist) text-xs font-semibold text-primary">{{ i + 1 }}</span>
                <div>
                  <h3 class="font-medium text-highlighted">
                    {{ s.title }}
                  </h3>
                  <p class="text-sm text-muted">
                    {{ s.text }}
                  </p>
                </div>
              </li>
            </ol>
          </UCard>
        </div>

        <aside class="max-lg:hidden lg:sticky lg:top-20 lg:self-start">
          <UCard>
            <h3 class="font-semibold text-highlighted">
              {{ name }}
            </h3>
            <div class="mt-4 flex items-center justify-between">
              <span class="text-toned">数量</span>
              <UInputNumber v-model="num" :min="1" :max="99" class="w-32" />
            </div>
            <div class="mt-3 flex items-center justify-between">
              <span class="text-toned">合计</span>
              <span class="text-[26px] font-semibold text-(--lr-accent-text)">{{ total }}</span>
            </div>
            <UButton class="mt-4" block size="xl" :to="buy" label="立即购买" />
            <p class="mt-2 text-center text-sm text-muted">
              付款后在线发码，可在「我的卡」安装
            </p>
          </UCard>
        </aside>
      </div>
    </UContainer>

    <div class="fixed inset-x-0 bottom-0 z-40 flex items-center gap-3 border-t border-muted bg-default px-4 pb-[calc(0.625rem+env(safe-area-inset-bottom))] pt-2.5 lg:hidden">
      <UInputNumber v-model="num" :min="1" :max="99" size="sm" class="w-28" aria-label="数量" />
      <div class="flex-1 text-right">
        <span class="text-sm text-muted">合计</span>
        <span class="ml-1 text-[22px] font-semibold text-(--lr-accent-text)">{{ total }}</span>
      </div>
      <UButton size="lg" :to="buy" label="立即购买" />
    </div>
  </div>
</template>
