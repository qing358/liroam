<script setup lang="ts">
import { destinationFrom, destinationLine, hotDestinations, listDestinations, multiDestinations } from '~/services/destinations'

// 首页：服务端渲染 + swr（nuxt.config routeRules），数据只来自 K3 目的地列表
usePageSeo({ title: '出境旅行通信卡', path: '/' })

const { $buyerApi } = useNuxtApp()
const { data: destinations } = await useAsyncData('destinations', () => listDestinations($buyerApi), { default: () => [] })

const hot = computed(() => hotDestinations(destinations.value))
const multi = computed(() => multiDestinations(destinations.value).slice(0, 3))

const keyword = ref('')
function search() {
  const q = keyword.value.trim()
  navigateTo({ path: '/esim', query: q ? { q } : {} })
}

const steps = [
  { title: '选目的地和天数', text: '按行程挑流量和天数，价格一目了然' },
  { title: '付款后获取激活码', text: 'eSIM 在线发码，实体卡发货可查物流' },
  { title: '扫码安装，落地上网', text: '出发前装好，到达后打开数据漫游即可' },
]
const trust = [
  { icon: 'i-lucide-zap', text: 'eSIM 付款后在线发码，通常几分钟内可安装' },
  { icon: 'i-lucide-shield-check', text: '在「我的卡」按卡申请退款，审核通过后原路退回' },
  { icon: 'i-lucide-message-circle', text: '安装、用量问题可随时联系客服', to: '/help' },
]
</script>

<template>
  <div>
    <section class="bg-(--lr-ink) py-8 text-(--lr-mist) md:py-16">
      <UContainer class="max-w-3xl">
        <h1 class="text-[26px] font-semibold text-inverted md:text-4xl">
          出境上网，出发前备好
        </h1>
        <p class="mb-5 mt-2 opacity-80">
          按目的地选购 eSIM 与实体卡，付款后在线发码，落地即可上网。
        </p>
        <form class="flex gap-2" role="search" @submit.prevent="search">
          <UInput
            v-model="keyword"
            size="xl"
            icon="i-lucide-search"
            placeholder="你要去哪里？输入国家或地区，如 日本、riben、Japan"
            class="flex-1"
            :ui="{ base: 'rounded-full' }"
            aria-label="搜索目的地"
          />
          <UButton type="submit" size="xl" label="搜索" class="max-md:hidden rounded-full" />
        </form>
        <p v-if="hot.length" class="mt-3.5 flex flex-wrap items-center gap-2 text-sm opacity-90">
          热门：
          <NuxtLink
            v-for="d in hot.slice(0, 5)"
            :key="d.code"
            :to="`/esim/${d.code.toLowerCase()}`"
            class="rounded-full bg-default/10 px-3 py-0.5 text-inverted"
          >
            {{ d.nameZh }}
          </NuxtLink>
        </p>
      </UContainer>
    </section>

    <UContainer class="space-y-10 py-8">
      <section v-if="hot.length">
        <div class="mb-3 flex items-baseline justify-between">
          <h2 class="text-lg font-semibold text-highlighted">
            热门目的地
          </h2>
          <NuxtLink to="/esim" class="text-sm text-primary">全部目的地</NuxtLink>
        </div>
        <div class="grid grid-cols-2 gap-3 md:grid-cols-4">
          <DestinationTile v-for="d in hot.slice(0, 8)" :key="d.code" :destination="d" :line="destinationFrom(d)" />
        </div>
      </section>

      <section v-if="multi.length">
        <div class="mb-3 flex items-baseline justify-between gap-2">
          <h2 class="text-lg font-semibold text-highlighted">
            多国与全球
          </h2>
          <span class="text-sm text-muted">一张卡跨多国，换国不换卡</span>
        </div>
        <div class="grid gap-3 md:grid-cols-3">
          <DestinationTile v-for="d in multi" :key="d.code" :destination="d" :line="destinationLine(d)" chevron />
        </div>
      </section>

      <section>
        <div class="mb-3 flex items-baseline justify-between">
          <h2 class="text-lg font-semibold text-highlighted">
            三步用上
          </h2>
          <NuxtLink to="/help/devices" class="text-sm text-primary">我的手机支持 eSIM 吗</NuxtLink>
        </div>
        <UCard>
          <ol class="grid gap-5 md:grid-cols-3">
            <li v-for="(s, i) in steps" :key="s.title" class="flex gap-3">
              <span class="flex size-7 flex-none items-center justify-center rounded-full bg-(--lr-mist) text-sm font-semibold text-primary">{{ i + 1 }}</span>
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
      </section>

      <section class="grid gap-3 md:grid-cols-2">
        <UCard>
          <div class="flex items-start gap-3.5">
            <span class="flex size-10 flex-none items-center justify-center rounded-full bg-(--lr-mist)"><UIcon name="i-lucide-card-sim" class="size-5 text-primary" /></span>
            <div>
              <h3 class="font-medium text-highlighted">
                一人多卡，按卡管理
              </h3>
              <p class="text-sm text-muted">
                每张卡单独查看剩余流量与到期时间，流量不够在线加包，不用换卡
              </p>
            </div>
          </div>
        </UCard>
        <UCard>
          <div class="flex items-start gap-3.5">
            <span class="flex size-10 flex-none items-center justify-center rounded-full bg-(--lr-mist)"><UIcon name="i-lucide-link" class="size-5 text-primary" /></span>
            <div>
              <h3 class="font-medium text-highlighted">
                京东、小红书买的卡也能管
              </h3>
              <p class="text-sm text-muted">
                凭订单号和下单手机号认领到「我的卡」，统一查看用量与续费
              </p>
              <NuxtLink to="/account/claim" class="text-sm text-primary">去认领</NuxtLink>
            </div>
          </div>
        </UCard>
      </section>

      <section>
        <ul class="divide-y divide-(--ui-border-muted) rounded-(--ui-radius) border border-muted bg-default">
          <li v-for="t in trust" :key="t.text" class="flex items-center gap-3 px-4 py-3">
            <UIcon :name="t.icon" class="size-5 flex-none text-primary" />
            <span class="flex-1 text-sm">{{ t.text }}</span>
            <NuxtLink v-if="t.to" :to="t.to" aria-label="联系客服">
              <UIcon name="i-lucide-chevron-right" class="size-4 text-dimmed" />
            </NuxtLink>
          </li>
        </ul>
      </section>
    </UContainer>
  </div>
</template>
