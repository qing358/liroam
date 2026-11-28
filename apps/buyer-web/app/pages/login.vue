<script setup lang="ts">
import { md5 } from 'js-md5'
import { z } from 'zod'
import type { TabsItem } from '@nuxt/ui'
import { passwordLogin, sendLoginSms, smsLogin } from '~/services/passport'
import { shellClientType } from '~/utils/terminal'

useHead({ title: '登录' })

const { $buyerApi, $terminal } = useNuxtApp()
const { signIn } = useMember()
const route = useRoute()
const toast = useToast()

const tabs: TabsItem[] = [
  { label: '验证码登录', value: 'sms', slot: 'sms' },
  { label: '密码登录', value: 'password', slot: 'password' },
]
const mode = ref('sms')

const mobile = z.string().regex(/^1\d{10}$/, '请输入 11 位手机号')
const smsSchema = z.object({ mobile, code: z.array(z.string()).transform((c) => c.join('')).pipe(z.string().regex(/^\d{6}$/, '请输入 6 位短信验证码')) })
const passwordSchema = z.object({ username: z.string().min(1, '请输入手机号或用户名'), password: z.string().min(1, '请输入密码') })

const sms = reactive({ mobile: '', code: [] as string[] })
const password = reactive({ username: '', password: '' })
const countdown = ref(0)
const submitting = ref(false)

// 滑块通过后再做的那一步：发短信或密码登录
const sliderOpen = ref(false)
let afterSlider: (() => Promise<void>) | null = null
function withSlider(next: () => Promise<void>) {
  afterSlider = next
  sliderOpen.value = true
}
async function onSliderPassed() {
  sliderOpen.value = false
  await afterSlider?.()
  afterSlider = null
}

const clientType = shellClientType($terminal)

function redirectTarget() {
  const target = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
  return target.startsWith('/') && !target.startsWith('//') ? target : '/'
}

async function finish(run: () => Promise<{ accessToken: string, refreshToken: string }>) {
  submitting.value = true
  try {
    await signIn(await run())
    await navigateTo(redirectTarget(), { replace: true })
  }
  catch (e) {
    toast.add({ title: (e as Error).message, color: 'error' })
  }
  finally {
    submitting.value = false
  }
}

function requestCode() {
  if (!mobile.safeParse(sms.mobile).success) {
    toast.add({ title: '请输入 11 位手机号', color: 'warning' })
    return
  }
  withSlider(async () => {
    try {
      await sendLoginSms($buyerApi, sms.mobile)
      toast.add({ title: '验证码已发送', color: 'success' })
      countdown.value = 60
      const timer = setInterval(() => {
        countdown.value -= 1
        if (countdown.value <= 0) clearInterval(timer)
      }, 1000)
    }
    catch (e) {
      toast.add({ title: (e as Error).message, color: 'error' })
    }
  })
}

const submitSms = () => finish(() => smsLogin($buyerApi, clientType, sms.mobile, sms.code.join('')))
const submitPassword = () => withSlider(() => finish(() => passwordLogin($buyerApi, clientType, password.username, md5(password.password))))
</script>

<template>
  <UContainer class="py-10 sm:py-16">
    <UCard class="mx-auto w-full max-w-md">
      <template #header>
        <h1 class="text-xl font-semibold text-highlighted">
          登录轻游
        </h1>
        <p class="mt-1 text-sm text-muted">
          未注册的手机号验证后自动创建账户
        </p>
      </template>

      <UTabs v-model="mode" :items="tabs" class="w-full">
        <template #sms>
          <UForm :schema="smsSchema" :state="sms" class="space-y-4 pt-4" @submit="submitSms">
            <UFormField label="手机号" name="mobile">
              <UInput v-model="sms.mobile" inputmode="numeric" autocomplete="tel" placeholder="11 位手机号" class="w-full" />
            </UFormField>
            <UFormField label="验证码" name="code">
              <div class="flex flex-wrap items-center gap-3">
                <UPinInput v-model="sms.code" :length="6" otp />
                <UButton color="neutral" variant="subtle" :disabled="countdown > 0" :label="countdown > 0 ? `${countdown} 秒后重发` : '获取验证码'" @click="requestCode" />
              </div>
            </UFormField>
            <UButton type="submit" block :loading="submitting" label="登录" />
          </UForm>
        </template>

        <template #password>
          <UForm :schema="passwordSchema" :state="password" class="space-y-4 pt-4" @submit="submitPassword">
            <UFormField label="手机号或用户名" name="username">
              <UInput v-model="password.username" autocomplete="username" class="w-full" />
            </UFormField>
            <UFormField label="密码" name="password">
              <UInput v-model="password.password" type="password" autocomplete="current-password" class="w-full" />
            </UFormField>
            <UButton type="submit" block :loading="submitting" label="登录" />
          </UForm>
        </template>
      </UTabs>
    </UCard>

    <UModal v-model:open="sliderOpen" title="安全验证" description="拖动滑块完成拼图">
      <template #body>
        <SliderVerify v-if="sliderOpen" @passed="onSliderPassed" />
      </template>
    </UModal>
  </UContainer>
</template>
