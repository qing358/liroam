<script setup lang="ts">
import { renderSVG } from 'uqr'
import { cashierDetail, orderSnList, paid, pay, payCountdown, type CashierDetail } from '~/services/cashier'
import { PAYMENT_METHOD_LABEL, paymentOptions, preferredOption, shellClientType, type PaymentOption } from '~/utils/terminal'
import { formatYuan } from '~/utils/money'

// 收银台：/pay/<交易单号>[?method=确认订单页预选的方式]。可用方式 = 当前终端能调起 ∩ 壳后台对该终端开启。
// 预选方式只自动调起一次（记在本标签页 sessionStorage）：从支付应用返回本页时显示等待态，不再重复调起。
definePageMeta({ middleware: 'auth', hideTabs: true })
useHead({ title: '支付' })

const { $buyerApi, $terminal } = useNuxtApp()
const config = useRuntimeConfig()
const route = useRoute()
const router = useRouter()
const toast = useToast()

const sn = String(route.params.sn ?? '')
const clientType = shellClientType($terminal)
const invokedKey = `liroam.pay.${sn}`
const detail = ref<CashierDetail | null>(null)
const error = ref('')
const active = ref<PaymentOption | null>(null)
const invoking = ref(false)
const qrcode = ref('')
const now = ref(Date.now())

const options = computed(() => detail.value
  ? paymentOptions($terminal, detail.value.support ?? [], { wechatJsapiEnabled: Boolean(config.public.wechatJsapiEnabled) })
  : [])
const countdown = computed(() => payCountdown(detail.value?.autoCancel, now.value))
const orderSns = computed(() => orderSnList(detail.value?.orderSns).join('、'))

const toResult = () => navigateTo({ path: '/pay/result', query: { sn } }, { replace: true })

let poller: ReturnType<typeof setInterval> | undefined
function startPolling() {
  stopPolling()
  let tries = 0
  poller = setInterval(async () => {
    tries += 1
    if (await paid($buyerApi, sn, clientType).catch(() => false)) {
      stopPolling()
      await toResult()
    }
    else if (tries >= 200) stopPolling()
  }, 3000)
}
function stopPolling() {
  if (poller) clearInterval(poller)
  poller = undefined
}

function submitForm(html: string) {
  const host = document.createElement('div')
  host.hidden = true
  host.innerHTML = html
  document.body.appendChild(host)
  host.querySelector('form')?.submit()
}

async function choose(option: PaymentOption) {
  if (option.method === 'WALLET' && detail.value && detail.value.walletValue < detail.value.price) {
    toast.add({ title: '账户余额不足，请选择其他支付方式', color: 'warning' })
    return
  }
  active.value = option
  invoking.value = true
  qrcode.value = ''
  sessionStorage.setItem(invokedKey, option.method)
  try {
    const result = await pay($buyerApi, option, sn, clientType)
    startPolling()
    switch (option.action) {
      case 'direct':
        await toResult()
        return
      case 'qrcode':
        qrcode.value = renderSVG(String(result))
        break
      case 'redirect':
        window.location.href = String(result)
        break
      case 'form':
        submitForm(String(result))
        break
      case 'jsapi':
        invokeWechatPay(result as Record<string, string>)
        break
    }
  }
  catch (e) {
    active.value = null
    sessionStorage.removeItem(invokedKey)
    toast.add({ title: (e as Error).message, color: 'error' })
  }
  finally {
    invoking.value = false
  }
}

// 微信内置浏览器的 JSAPI 调起；开关打开前不会走到这里
function invokeWechatPay(params: Record<string, string>) {
  const bridge = (window as unknown as { WeixinJSBridge?: { invoke: (name: string, p: unknown, cb: () => void) => void } }).WeixinJSBridge
  bridge?.invoke('getBrandWCPayRequest', params, () => undefined)
}

async function confirmPaid() {
  if (await paid($buyerApi, sn, clientType).catch(() => false)) await toResult()
  else toast.add({ title: '还没有收到支付结果，请稍候或重新打开支付', color: 'warning' })
}

function change() {
  stopPolling()
  active.value = null
  qrcode.value = ''
  sessionStorage.removeItem(invokedKey)
}

let ticker: ReturnType<typeof setInterval> | undefined
onMounted(async () => {
  ticker = setInterval(() => { now.value = Date.now() }, 1000)
  if (!sn) {
    error.value = '没有找到要支付的订单'
    return
  }
  try {
    detail.value = await cashierDetail($buyerApi, sn, clientType)
    if (await paid($buyerApi, sn, clientType).catch(() => false)) return toResult()
    const invoked = preferredOption(options.value, sessionStorage.getItem(invokedKey) ?? undefined)
    if (invoked) {
      active.value = invoked
      startPolling()
      return
    }
    const preferred = preferredOption(options.value, route.query.method as string | undefined)
    if (preferred) {
      await router.replace({ query: {} })
      await choose(preferred)
    }
  }
  catch (e) {
    error.value = (e as Error).message
  }
})
onBeforeUnmount(() => {
  stopPolling()
  clearInterval(ticker)
})
</script>

<template>
  <div>
    <BackBar title="支付" fallback="/orders" />
    <UContainer class="max-w-xl py-4 md:py-10">
      <UAlert v-if="error" color="error" variant="subtle" :title="error" />
      <USkeleton v-else-if="!detail" class="h-48 w-full" />

      <UCard v-else>
        <div class="space-y-6 text-center">
          <div>
            <p class="text-sm text-muted">
              订单 {{ orderSns || sn }}<template v-if="countdown">
                · 请在 {{ countdown }} 内完成支付
              </template>
            </p>
            <p class="mt-1.5 text-[34px] font-semibold text-(--lr-accent-text)">
              {{ formatYuan(detail.price) }}
            </p>
          </div>

          <div v-if="qrcode" class="flex flex-col items-center gap-2">
            <!-- eslint-disable-next-line vue/no-v-html -- uqr 本地生成的二维码 SVG -->
            <div class="size-52" v-html="qrcode" />
            <p class="text-toned">
              打开微信扫一扫，扫码完成支付
            </p>
            <p class="text-sm text-muted">
              支付完成后页面自动跳转
            </p>
          </div>

          <div v-else-if="active" class="space-y-3 text-left">
            <p class="flex gap-2.5 rounded-md bg-(--lr-mist) px-3.5 py-3 text-sm text-(--lr-primary-active)">
              <UIcon name="i-lucide-clock" class="mt-0.5 size-4 flex-none" />
              <span>正在等待支付结果，已在支付应用完成付款的请稍候</span>
            </p>
            <UButton block size="xl" :loading="invoking" label="重新打开支付" @click="choose(active)" />
            <UButton block size="lg" variant="outline" color="neutral" label="我已完成支付" @click="confirmPaid" />
          </div>

          <div v-else class="space-y-2 text-left">
            <p v-if="!options.length" class="text-sm text-muted">
              当前环境暂无可用的支付方式，请换用手机浏览器或电脑打开本页
            </p>
            <UButton
              v-for="o in options"
              :key="o.method"
              size="xl"
              block
              variant="outline"
              color="neutral"
              :loading="invoking"
              :label="o.method === 'WALLET' ? `${PAYMENT_METHOD_LABEL[o.method]}（可用 ${formatYuan(detail.walletValue)}）` : PAYMENT_METHOD_LABEL[o.method]"
              @click="choose(o)"
            />
          </div>

          <UButton v-if="active" variant="link" color="neutral" label="更换支付方式" @click="change" />
        </div>
      </UCard>
    </UContainer>
  </div>
</template>
