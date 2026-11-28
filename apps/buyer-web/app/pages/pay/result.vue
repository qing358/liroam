<script setup lang="ts">
import { cashierDetail, orderSnList, paid } from '~/services/cashier'
import { cardsOfOrder, deliveryProgress, orderDetail, toOrderBrief, type DeliveryProgress } from '~/services/delivery'
import { shellClientType } from '~/utils/terminal'

// 支付结果：/pay/result?sn=<交易单号>。先确认已付款，再轮询交付进度：壳订单状态，开关 cardsByOrder 打开后加按订单号取的卡（K4）逐张显示。
// 支付宝电脑网站支付付款后回跳 BASE_SETTING.staticPageAddress + /payDone（壳约定路径，切新站归 WS14）；没有交易单号时只给去向
definePageMeta({ alias: ['/payDone'], middleware: 'auth', hideTabs: true })
useHead({ title: '支付结果' })

const { $buyerApi, $terminal } = useNuxtApp()
const config = useRuntimeConfig()
const route = useRoute()

const sn = String(route.query.sn ?? '')
const clientType = shellClientType($terminal)
const byCard = Boolean(config.public.cardsByOrder)

const state = ref<'checking' | 'unpaid' | 'paid'>(sn ? 'checking' : 'paid')
const progress = ref<DeliveryProgress | null>(null)
const slow = ref(false)
const error = ref('')

async function loadProgress(orderSn: string) {
  const [order, cards] = await Promise.all([
    orderDetail($buyerApi, orderSn),
    byCard ? cardsOfOrder($buyerApi, orderSn) : Promise.resolve(null),
  ])
  progress.value = deliveryProgress(toOrderBrief(order), cards)
}

const POLL_MS = 3000
const SLOW_AFTER = 60
let timer: ReturnType<typeof setTimeout> | undefined
async function poll(orderSn: string, tries = 0) {
  try {
    await loadProgress(orderSn)
  }
  catch (e) {
    error.value = (e as Error).message
  }
  if (progress.value?.done) return
  if (tries >= SLOW_AFTER) {
    slow.value = true
    return
  }
  // 只按订单状态时 eSIM 进度不会变，不必轮询
  if (progress.value && !progress.value.physical && !byCard) return
  timer = setTimeout(() => poll(orderSn, tries + 1), POLL_MS)
}

async function waitPaid(tries = 0): Promise<boolean> {
  if (await paid($buyerApi, sn, clientType).catch(() => false)) return true
  if (tries >= 10) return false
  await new Promise((r) => { timer = setTimeout(r, POLL_MS) })
  return waitPaid(tries + 1)
}

onMounted(async () => {
  if (!sn) return
  try {
    if (!(await waitPaid())) {
      state.value = 'unpaid'
      return
    }
    state.value = 'paid'
    const [orderSn] = orderSnList((await cashierDetail($buyerApi, sn, clientType)).orderSns)
    if (orderSn) await poll(orderSn)
  }
  catch (e) {
    error.value = (e as Error).message
  }
})
onBeforeUnmount(() => clearTimeout(timer))
</script>

<template>
  <div>
    <BackBar title="支付结果" fallback="/cards" />
    <UContainer class="max-w-2xl py-4 md:py-10">
      <USkeleton v-if="state === 'checking'" class="h-48 w-full" />

      <UCard v-else-if="state === 'unpaid'">
        <div class="space-y-3 text-center">
          <h1 class="text-xl font-semibold text-highlighted">
            还没有收到支付结果
          </h1>
          <p class="text-toned">
            已在支付应用完成付款的请稍候，或返回重新支付。
          </p>
          <div class="flex justify-center gap-3">
            <UButton :to="`/pay/${encodeURIComponent(sn)}`" label="返回支付" />
            <UButton to="/orders" variant="outline" color="neutral" label="查看订单" />
          </div>
        </div>
      </UCard>

      <UCard v-else>
        <div class="space-y-6">
          <div class="space-y-2 text-center">
            <span class="inline-flex size-14 items-center justify-center rounded-full bg-primary text-inverted">
              <UIcon name="i-lucide-check" class="size-7" />
            </span>
            <h1 class="text-xl font-semibold text-highlighted">
              支付成功
            </h1>
            <p v-if="!progress?.physical" class="text-toned">
              正在为你开卡，通常几分钟内完成，完成后即可安装。
            </p>
          </div>

          <ol v-if="progress" class="space-y-0">
            <li v-for="(s, i) in progress.steps" :key="s.label" class="relative flex gap-3 pb-4 last:pb-0">
              <span
                v-if="i < progress.steps.length - 1"
                class="absolute bottom-0 left-[5px] top-[18px] w-0.5"
                :class="s.state === 'done' ? 'bg-primary' : 'bg-accented'"
              />
              <span
                class="relative mt-1.5 size-3 flex-none rounded-full"
                :class="{ 'bg-primary': s.state === 'done', 'border-[3px] border-primary bg-default': s.state === 'now', 'bg-accented': s.state === 'todo' }"
              />
              <div>
                <b class="font-semibold" :class="s.state === 'todo' ? 'text-muted' : 'text-highlighted'">{{ s.label }}</b>
                <p v-if="s.detail" class="text-sm text-muted">
                  {{ s.detail }}
                </p>
              </div>
            </li>
          </ol>

          <ul v-if="progress?.cards.length" class="divide-y divide-(--ui-border-muted) rounded-(--ui-radius) border border-muted">
            <li v-for="c in progress.cards" :key="c.index" class="flex items-center gap-3 px-3 py-2.5">
              <div class="min-w-0 flex-1">
                <b class="block truncate font-semibold text-highlighted">{{ c.name }}</b>
                <span class="text-sm text-muted">第 {{ c.index }} 张 · {{ c.ready ? '可以安装' : '开卡中' }}</span>
              </div>
              <UButton v-if="c.ready" size="sm" :to="`/cards/${c.cardId}`" label="去安装" />
              <UBadge v-else color="neutral" variant="soft" label="开卡中" />
            </li>
          </ul>

          <p v-if="slow" class="text-center text-sm text-muted">
            开卡时间较长，完成后会通知你。<NuxtLink to="/help" class="text-primary">联系客服</NuxtLink>
          </p>
          <UAlert v-if="error" color="warning" variant="subtle" :title="error" />

          <div class="flex flex-wrap justify-center gap-3">
            <UButton v-if="progress?.physical" :to="`/orders/${encodeURIComponent(progress.orderSn)}`" label="查看物流" />
            <UButton to="/cards" variant="outline" label="查看我的卡" />
            <UButton to="/" variant="ghost" color="neutral" label="继续选购" />
          </div>
        </div>
      </UCard>
    </UContainer>
  </div>
</template>
