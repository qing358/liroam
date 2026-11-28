<script setup lang="ts">
import { buyNow, checkout as loadCheckout, createTrade, needsShipping, selectShippingAddress, type Checkout } from '~/services/trade'
import { shellClientType } from '~/utils/terminal'
import { formatYuan } from '~/utils/money'

// 立即购买：/checkout?skuId=&num= → 壳 BUY_NOW 结算 → 建交易 → 收银台
definePageMeta({ middleware: 'auth' })
useHead({ title: '确认订单' })

const { $buyerApi, $terminal } = useNuxtApp()
const route = useRoute()
const toast = useToast()

const data = ref<Checkout | null>(null)
const error = ref('')
const addressId = ref<string>()
const submitting = ref(false)
const shipping = computed(() => (data.value ? needsShipping(data.value) : false))

async function load() {
  const skuId = String(route.query.skuId ?? '')
  const num = Math.max(1, Number(route.query.num) || 1)
  if (!skuId) {
    error.value = '没有选择套餐'
    return
  }
  try {
    await buyNow($buyerApi, skuId, num)
    data.value = await loadCheckout($buyerApi)
    const invalid = data.value.skuList.find((s) => s.invalid || s.errorMessage)
    if (invalid) error.value = invalid.errorMessage || '该套餐暂不可购买'
  }
  catch (e) {
    error.value = (e as Error).message
  }
}

async function submit() {
  if (shipping.value && !addressId.value) {
    toast.add({ title: '请选择收货地址', color: 'warning' })
    return
  }
  submitting.value = true
  try {
    if (shipping.value && addressId.value) await selectShippingAddress($buyerApi, addressId.value)
    const trade = await createTrade($buyerApi, shellClientType($terminal))
    await navigateTo(`/pay/${encodeURIComponent(trade.sn)}`, { replace: true })
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
  <UContainer class="py-8 max-w-2xl space-y-4">
    <h1 class="text-xl font-semibold text-highlighted">
      确认订单
    </h1>

    <UAlert v-if="error" color="error" variant="subtle" :title="error" />
    <USkeleton v-else-if="!data" class="h-40 w-full" />

    <template v-else>
      <UCard>
        <ul class="divide-y divide-default">
          <li v-for="item in data.skuList" :key="item.goodsSku.id" class="flex items-center gap-3 py-3">
            <img v-if="item.goodsSku.thumbnail" :src="item.goodsSku.thumbnail" alt="" class="size-14 rounded-md object-cover">
            <div class="flex-1 min-w-0">
              <p class="font-medium text-highlighted truncate">
                {{ item.goodsSku.goodsName }}
              </p>
              <p v-if="item.goodsSku.simpleSpecs" class="text-sm text-muted">
                {{ item.goodsSku.simpleSpecs }}
              </p>
            </div>
            <div class="text-right">
              <p>{{ formatYuan(item.goodsSku.price) }}</p>
              <p class="text-sm text-muted">
                × {{ item.num }}
              </p>
            </div>
          </li>
        </ul>
      </UCard>

      <UCard v-if="shipping">
        <template #header>
          <h2 class="font-medium text-highlighted">
            收货地址
          </h2>
        </template>
        <AddressPicker v-model="addressId" />
      </UCard>

      <UCard>
        <div class="flex items-center justify-between">
          <span class="text-muted">应付</span>
          <span class="text-2xl font-semibold text-highlighted">{{ formatYuan(data.priceDetailDTO.flowPrice) }}</span>
        </div>
        <UButton class="mt-4" block size="lg" :loading="submitting" label="提交订单" @click="submit" />
      </UCard>
    </template>
  </UContainer>
</template>
