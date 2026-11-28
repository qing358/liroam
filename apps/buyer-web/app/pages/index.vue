<script setup lang="ts">
// 首页骨架：目的地搜索与分区由 WS11 接 K3（/buyer/liroam/destinations）后补全
useHead({ title: '出境旅行通信卡' })

const keyword = ref('')
function search() {
  const q = keyword.value.trim()
  if (q) navigateTo({ path: '/esim', query: { q } })
}

const steps = [
  { icon: 'i-lucide-map-pin', title: '选目的地', text: '按国家或区域挑选流量与天数' },
  { icon: 'i-lucide-credit-card', title: '下单支付', text: '微信、支付宝付款，即买即发' },
  { icon: 'i-lucide-qr-code', title: '扫码安装', text: '按指引扫码安装 eSIM，落地即用' },
]
</script>

<template>
  <div>
    <section class="bg-accented">
      <UContainer class="py-14 sm:py-20 text-center space-y-6">
        <h1 class="text-3xl sm:text-4xl font-semibold text-highlighted">
          你要去哪里？
        </h1>
        <p class="text-muted">
          出境上网，选好目的地，一张卡就够
        </p>
        <form class="mx-auto flex max-w-xl gap-2" @submit.prevent="search">
          <UInput v-model="keyword" size="xl" icon="i-lucide-search" placeholder="输入国家或地区，如 日本、欧洲" class="flex-1" />
          <UButton type="submit" size="xl" label="搜索" />
        </form>
      </UContainer>
    </section>

    <UContainer class="py-12">
      <h2 class="text-lg font-semibold text-highlighted mb-6">
        三步用上
      </h2>
      <div class="grid gap-4 sm:grid-cols-3">
        <UCard v-for="s in steps" :key="s.title">
          <UIcon :name="s.icon" class="size-7 text-primary" />
          <p class="mt-3 font-medium text-highlighted">
            {{ s.title }}
          </p>
          <p class="mt-1 text-sm text-muted">
            {{ s.text }}
          </p>
        </UCard>
      </div>
    </UContainer>
  </div>
</template>
