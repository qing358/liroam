<script setup lang="ts">
import type { NavigationMenuItem } from '@nuxt/ui'

// 导航按设计稿：PC 顶栏 + 深色页脚；手机底部四个标签（下单与详情页由页面自带返回栏，WS11 / WS12 落地）
const { member, load, signedIn } = useMember()
const route = useRoute()

const desktopNav: NavigationMenuItem[] = [
  { label: '选目的地', to: '/' },
  { label: '我的卡', to: '/cards' },
  { label: '订单', to: '/orders' },
  { label: '帮助中心', to: '/help' },
]

const tabs = [
  { label: '选卡', to: '/', icon: 'i-lucide-globe', match: (p: string) => p === '/' || p.startsWith('/esim') },
  { label: '我的卡', to: '/cards', icon: 'i-lucide-card-sim', match: (p: string) => p.startsWith('/cards') },
  { label: '帮助', to: '/help', icon: 'i-lucide-circle-help', match: (p: string) => p.startsWith('/help') },
  { label: '我的', to: '/account', icon: 'i-lucide-user-round', match: (p: string) => p.startsWith('/account') || p.startsWith('/orders') },
]

onMounted(() => {
  if (member.value === undefined && signedIn()) load()
})
</script>

<template>
  <div class="min-h-dvh flex flex-col bg-default">
    <UHeader to="/" title="轻游" :toggle="false" class="max-md:hidden">
      <template #title>
        <img src="/brand/liroam-horizontal-ink.svg" alt="轻游 Liroam" class="h-7 w-auto">
      </template>

      <UNavigationMenu :items="desktopNav" variant="link" />

      <template #right>
        <ClientOnly>
          <UButton v-if="member" to="/account" variant="ghost" color="neutral" icon="i-lucide-user-round" :label="member.nickName || '我的'" />
          <UButton v-else to="/login" label="登录" />
        </ClientOnly>
      </template>
    </UHeader>

    <UMain class="flex-1 max-md:pb-16">
      <slot />
    </UMain>

    <footer class="bg-inverted text-inverted max-md:hidden">
      <UContainer class="py-8 flex items-center justify-between gap-4 text-sm">
        <img src="/brand/liroam-horizontal-reverse.svg" alt="轻游 Liroam" class="h-6 w-auto">
        <p>© {{ new Date().getFullYear() }} 想象智能（济南）有限公司 · 轻游 Liroam</p>
      </UContainer>
    </footer>

    <nav class="md:hidden fixed inset-x-0 bottom-0 z-50 border-t border-default bg-default pb-[env(safe-area-inset-bottom)]">
      <ul class="grid grid-cols-4">
        <li v-for="t in tabs" :key="t.to">
          <NuxtLink :to="t.to" class="flex flex-col items-center gap-0.5 py-2 text-xs" :class="t.match(route.path) ? 'text-primary' : 'text-muted'">
            <UIcon :name="t.icon" class="size-6" />
            {{ t.label }}
          </NuxtLink>
        </li>
      </ul>
    </nav>
  </div>
</template>
