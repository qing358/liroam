<script setup lang="ts">
import { buyNow, checkout as loadCheckout, createTrade, needsShipping, selectShippingAddress, type Checkout } from '~/services/trade'
import { destinationPlans, planDataText, planDetailBadges, type Destination, type Plan } from '~/services/destinations'
import { PAYMENT_METHOD_LABEL, paymentChoices, paymentHint, shellClientType, type PaymentMethod } from '~/utils/terminal'
import { formatYuan } from '~/utils/money'

// 立即购买：/checkout?skuId=&num=[&code=目的地] → 壳 BUY_NOW 结算 → 建交易 → /pay/<交易单号>?method=预选方式
// code 可选：有则从 K3 取套餐展示与「需实名」；没有时只显示壳结算里的商品名
definePageMeta({ middleware: 'auth', hideTabs: true })
useHead({ title: '确认订单' })

const { $buyerApi, $terminal } = useNuxtApp()
const config = useRuntimeConfig()
const route = useRoute()
const router = useRouter()
const toast = useToast()

const skuId = String(route.query.skuId ?? '')
const destCode = String(route.query.code ?? '')
const num = ref(Math.min(99, Math.max(1, Number(route.query.num) || 1)))

const data = ref<Checkout | null>(null)
const target = ref<{ destination: Destination, plan: Plan } | null>(null)
const error = ref('')
const addressId = ref<string>()
const submitting = ref(false)
const reloading = ref(false)
const agreed = ref(false)
const shipping = computed(() => (data.value ? needsShipping(data.value) : false))

const choices = computed(() => paymentChoices($terminal, { wechatJsapiEnabled: Boolean(config.public.wechatJsapiEnabled) }))
const method = ref<PaymentMethod | undefined>(choices.value[0]?.method)

async function refresh() {
  await buyNow($buyerApi, skuId, num.value)
  data.value = await loadCheckout($buyerApi)
  const invalid = data.value.skuList.find((s) => s.invalid || s.errorMessage)
  error.value = invalid ? invalid.errorMessage || '该套餐暂不可购买' : ''
}

async function load() {
  if (!skuId) {
    error.value = '没有选择套餐'
    return
  }
  try {
    const [, plans] = await Promise.all([refresh(), destCode ? destinationPlans($buyerApi, destCode).catch(() => null) : null])
    const plan = plans?.plans.find((p) => p.skuId === skuId)
    if (plans && plan) target.value = { destination: plans.destination, plan }
  }
  catch (e) {
    error.value = (e as Error).message
  }
}

let pending: ReturnType<typeof setTimeout> | undefined
watch(num, (n) => {
  clearTimeout(pending)
  pending = setTimeout(async () => {
    reloading.value = true
    try {
      await refresh()
      router.replace({ query: { ...route.query, num: String(n) } })
    }
    catch (e) {
      toast.add({ title: (e as Error).message, color: 'error' })
    }
    finally {
      reloading.value = false
    }
  }, 400)
})

async function submit() {
  if (!agreed.value) {
    toast.add({ title: '请先阅读并同意购买须知与退款说明', color: 'warning' })
    return
  }
  if (shipping.value && !addressId.value) {
    toast.add({ title: '请选择收货地址', color: 'warning' })
    return
  }
  submitting.value = true
  try {
    if (shipping.value && addressId.value) await selectShippingAddress($buyerApi, addressId.value)
    const trade = await createTrade($buyerApi, shellClientType($terminal))
    await navigateTo({ path: `/pay/${encodeURIComponent(trade.sn)}`, query: method.value ? { method: method.value } : {} }, { replace: true })
  }
  catch (e) {
    toast.add({ title: (e as Error).message, color: 'error' })
  }
  finally {
    submitting.value = false
  }
}

onMounted(load)
</script>

<template>
  <div>
    <BackBar title="确认订单" :fallback="destCode ? `/esim/${destCode.toLowerCase()}/${skuId}` : '/'" />
    <UContainer class="max-w-5xl py-4 pb-28 md:py-8">
      <h1 class="mb-5 text-2xl font-semibold text-highlighted max-md:hidden">
        确认订单
      </h1>

      <UAlert v-if="error" color="error" variant="subtle" :title="error" />
      <USkeleton v-else-if="!data" class="h-40 w-full" />

      <div v-else class="grid gap-6 lg:grid-cols-[1fr_340px]">
        <div class="min-w-0 space-y-4">
          <UCard v-for="item in data.skuList" :key="item.goodsSku.id">
            <div class="flex items-start gap-3">
              <DestinationCode v-if="target" :code="target.destination.code" :kind="target.destination.kind" />
              <div class="min-w-0 flex-1">
                <h2 class="font-semibold text-highlighted">
                  {{ target ? `${target.destination.nameZh} ${target.plan.days} 天 ${planDataText(target.plan)}` : item.goodsSku.goodsName }}
                </h2>
                <p class="text-sm text-muted">
                  {{ target ? planDetailBadges(target.plan).join(' · ') : item.goodsSku.simpleSpecs }}
                </p>
              </div>
              <span class="font-semibold text-(--lr-accent-text)">{{ formatYuan(item.goodsSku.price) }}</span>
            </div>
            <div class="mt-3.5 flex items-center justify-between">
              <span class="text-toned">数量</span>
              <UInputNumber v-model="num" :min="1" :max="99" :disabled="reloading" class="w-32" />
            </div>
            <p class="mt-1.5 text-sm text-muted">
              每张卡单独发码、单独计算用量，可分给同行的人使用。
            </p>
          </UCard>

          <UCard v-if="target?.plan.kycRequired">
            <h2 class="mb-2 font-semibold text-highlighted">
              实名信息
            </h2>
            <p class="flex gap-2.5 rounded-md bg-(--lr-accent-light-9) px-3.5 py-3 text-sm">
              <UIcon name="i-lucide-info" class="mt-0.5 size-4 flex-none text-(--lr-accent-text)" />
              <span>该套餐按当地要求需实名，付款后在「我的卡」填写证件信息，审核通过即可安装。</span>
            </p>
          </UCard>

          <UCard v-if="shipping">
            <h2 class="mb-2 font-semibold text-highlighted">
              收货地址
            </h2>
            <AddressPicker v-model="addressId" />
          </UCard>

          <UCard>
            <h2 class="mb-2 font-semibold text-highlighted">
              支付方式
            </h2>
            <p v-if="!choices.length" class="text-sm text-muted">
              当前环境暂无可用的支付方式，请换用手机浏览器或电脑打开本页
            </p>
            <URadioGroup
              v-else
              v-model="method"
              variant="table"
              :items="choices.map((o) => ({ value: o.method, label: PAYMENT_METHOD_LABEL[o.method], description: paymentHint($terminal, o.method) }))"
            />
          </UCard>
        </div>

        <aside class="lg:sticky lg:top-20 lg:self-start">
          <UCard>
            <dl class="space-y-2.5 text-sm">
              <div class="flex justify-between">
                <dt class="text-toned">
                  商品金额
                </dt>
                <dd>{{ formatYuan(data.priceDetailDTO.goodsPrice) }}</dd>
              </div>
              <div class="flex justify-between">
                <dt class="text-toned">
                  运费
                </dt>
                <dd>{{ formatYuan(data.priceDetailDTO.freightPrice) }}</dd>
              </div>
              <div class="flex items-baseline justify-between border-t border-muted pt-3">
                <dt class="font-semibold">
                  应付
                </dt>
                <dd class="text-[26px] font-semibold text-(--lr-accent-text)">
                  {{ formatYuan(data.priceDetailDTO.flowPrice) }}
                </dd>
              </div>
            </dl>
            <UButton class="mt-4 max-lg:hidden" block size="xl" :loading="submitting" :disabled="reloading || !choices.length" label="提交订单并支付" @click="submit" />
            <UCheckbox v-model="agreed" class="mt-3" :ui="{ label: 'text-sm text-muted font-normal' }">
              <template #label>
                我已阅读并同意 <NuxtLink to="/help" class="text-primary">购买须知</NuxtLink> 与 <NuxtLink to="/help" class="text-primary">退款说明</NuxtLink>
              </template>
            </UCheckbox>
          </UCard>
        </aside>
      </div>
    </UContainer>

    <div v-if="data && !error" class="fixed inset-x-0 bottom-0 z-40 flex items-center gap-3 border-t border-muted bg-default px-4 pb-[calc(0.625rem+env(safe-area-inset-bottom))] pt-2.5 lg:hidden">
      <div class="flex-1">
        <span class="text-sm text-muted">应付</span>
        <span class="ml-1 text-[22px] font-semibold text-(--lr-accent-text)">{{ formatYuan(data.priceDetailDTO.flowPrice) }}</span>
      </div>
      <UButton size="lg" :loading="submitting" :disabled="reloading || !choices.length" label="提交订单并支付" @click="submit" />
    </div>
  </div>
</template>
