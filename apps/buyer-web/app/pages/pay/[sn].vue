<script setup lang="ts">
import { renderSVG } from 'uqr'
import { cashierDetail, paid, pay, type CashierDetail } from '~/services/cashier'
import { PAYMENT_METHOD_LABEL, paymentOptions, shellClientType, type PaymentOption } from '~/utils/terminal'
import { formatYuan } from '~/utils/money'

// 收银台：/pay/<交易单号>。可用方式 = 当前终端能调起 ∩ 壳后台对该终端开启
definePageMeta({ middleware: 'auth' })
useHead({ title: '支付' })

const { $buyerApi, $terminal } = useNuxtApp()
const config = useRuntimeConfig()
const route = useRoute()
const toast = useToast()

const sn = String(route.params.sn ?? '')
const clientType = shellClientType($terminal)
const detail = ref<CashierDetail | null>(null)
const error = ref('')
const paying = ref<PaymentOption | null>(null)
const qrcode = ref('')
const pageUrl = ref('')
const polling = ref(false)
const formHost = ref<HTMLDivElement>()

const options = computed(() => detail.value
  ? paymentOptions($terminal, detail.value.support ?? [], { wechatJsapiEnabled: Boolean(config.public.wechatJsapiEnabled) })
  : [])

let timer: ReturnType<typeof setInterval> | undefined
function startPolling() {
  stopPolling()
  polling.value = true
  let tries = 0
  timer = setInterval(async () => {
    tries += 1
    if (await paid($buyerApi, sn, clientType).catch(() => false)) {
      stopPolling()
      await navigateTo({ path: '/pay/result', query: { sn } }, { replace: true })
    }
    else if (tries >= 100) stopPolling()
  }, 3000)
}
function stopPolling() {
  if (timer) clearInterval(timer)
  timer = undefined
  polling.value = false
}

function submitForm(html: string) {
  if (!formHost.value) return
  formHost.value.innerHTML = html
  formHost.value.querySelector('form')?.submit()
}

async function choose(option: PaymentOption) {
  if (option.method === 'WALLET' && detail.value && detail.value.walletValue < detail.value.price) {
    toast.add({ title: '账户余额不足，请选择其他支付方式', color: 'warning' })
    return
  }
  paying.value = option
  qrcode.value = ''
  pageUrl.value = ''
  try {
    const result = await pay($buyerApi, option, sn, clientType)
    switch (option.action) {
      case 'direct':
        await navigateTo({ path: '/pay/result', query: { sn } }, { replace: true })
        return
      case 'qrcode':
        qrcode.value = renderSVG(String(result))
        break
      case 'redirect':
        pageUrl.value = String(result)
        if ($terminal === 'PC') window.open(pageUrl.value, '_blank', 'noopener')
        else window.location.href = pageUrl.value
        break
      case 'form':
        submitForm(String(result))
        break
      case 'jsapi':
        invokeWechatPay(result as Record<string, string>)
        break
    }
    startPolling()
  }
  catch (e) {
    paying.value = null
    toast.add({ title: (e as Error).message, color: 'error' })
  }
}

// 微信内置浏览器的 JSAPI 调起；开关打开前不会走到这里
function invokeWechatPay(params: Record<string, string>) {
  const bridge = (window as unknown as { WeixinJSBridge?: { invoke: (name: string, p: unknown, cb: () => void) => void } }).WeixinJSBridge
  bridge?.invoke('getBrandWCPayRequest', params, () => undefined)
}

onMounted(async () => {
  if (!sn) {
    error.value = '没有找到要支付的订单'
    return
  }
  try {
    detail.value = await cashierDetail($buyerApi, sn, clientType)
    if (await paid($buyerApi, sn, clientType).catch(() => false)) await navigateTo({ path: '/pay/result', query: { sn } }, { replace: true })
  }
  catch (e) {
    error.value = (e as Error).message
  }
})
onBeforeUnmount(stopPolling)
</script>

<template>
  <UContainer class="py-8 max-w-xl space-y-4">
    <h1 class="text-xl font-semibold text-highlighted">
      支付订单
    </h1>

    <UAlert v-if="error" color="error" variant="subtle" :title="error" />
    <USkeleton v-else-if="!detail" class="h-40 w-full" />

    <template v-else>
      <UCard>
        <p class="text-muted text-sm">
          应付金额
        </p>
        <p class="text-3xl font-semibold text-highlighted">
          {{ formatYuan(detail.price) }}
        </p>
        <p v-if="detail.detail" class="mt-1 text-sm text-muted">
          {{ detail.detail }}
        </p>
      </UCard>

      <UCard>
        <template #header>
          <h2 class="font-medium text-highlighted">
            选择支付方式
          </h2>
        </template>
        <p v-if="!options.length" class="text-sm text-muted">
          当前环境暂无可用的支付方式，请换用手机浏览器或电脑打开本页
        </p>
        <div class="grid gap-2">
          <UButton
            v-for="o in options"
            :key="o.method"
            size="lg"
            block
            :variant="paying?.method === o.method ? 'solid' : 'outline'"
            :loading="paying?.method === o.method && !qrcode && !pageUrl && !polling"
            :label="o.method === 'WALLET' ? `${PAYMENT_METHOD_LABEL[o.method]}（可用 ${formatYuan(detail.walletValue)}）` : PAYMENT_METHOD_LABEL[o.method]"
            @click="choose(o)"
          />
        </div>
      </UCard>

      <UCard v-if="qrcode">
        <div class="flex flex-col items-center gap-2">
          <!-- eslint-disable-next-line vue/no-v-html -- uqr 本地生成的二维码 SVG -->
          <div class="size-52" v-html="qrcode" />
          <p class="text-sm text-muted">
            请用微信扫码付款，付款后本页自动跳转
          </p>
        </div>
      </UCard>

      <UCard v-if="pageUrl">
        <p class="text-sm text-muted">
          已在新窗口打开收银台，付款后本页自动跳转。
        </p>
        <UButton class="mt-2" variant="link" :to="pageUrl" target="_blank" label="没有打开？点这里" />
      </UCard>

      <div ref="formHost" class="hidden" />
    </template>
  </UContainer>
</template>
