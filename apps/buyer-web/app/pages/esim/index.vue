<script setup lang="ts">
import { continentGroups, coverHint, destinationFrom, destinationLine, listDestinations, type DestinationKind } from '~/services/destinations'

// 全部目的地与搜索：服务端渲染；关键词、分段与洲都在查询串里，刷新与分享保持同一视图
useHead({ title: '选择目的地' })

const { $buyerApi } = useNuxtApp()
const route = useRoute()
const router = useRouter()

const q = computed(() => String(route.query.q ?? '').trim())
const tab = computed<DestinationKind>(() => (['REGION_GROUP', 'GLOBAL'].includes(String(route.query.tab)) ? route.query.tab as DestinationKind : 'COUNTRY'))
const continent = computed(() => String(route.query.continent ?? ''))

const { data: list, status } = await useAsyncData(
  () => `destinations:${q.value}`,
  () => listDestinations($buyerApi, q.value),
  { default: () => [], watch: [q] },
)

const hint = computed(() => coverHint(list.value))
const ofTab = computed(() => list.value.filter((d) => d.kind === tab.value))
const groups = computed(() => continentGroups(ofTab.value))
const shown = computed(() => (continent.value ? groups.value.filter((g) => g.label === continent.value) : groups.value))

const tabs = [
  { kind: 'COUNTRY', label: '单个国家和地区' },
  { kind: 'REGION_GROUP', label: '多国' },
  { kind: 'GLOBAL', label: '全球' },
] as const

const keyword = ref(q.value)
watch(q, (v) => { keyword.value = v })
let typing: ReturnType<typeof setTimeout> | undefined
function onInput() {
  clearTimeout(typing)
  typing = setTimeout(() => {
    const next = keyword.value.trim()
    router.replace({ query: next ? { q: next } : {} })
  }, 300)
}
const pick = (query: Record<string, string>) => ({ query: { ...route.query, ...query } })
</script>

<template>
  <div>
    <BackBar title="选择目的地" fallback="/" />
    <UContainer class="space-y-5 py-4 md:py-8">
      <h1 class="text-2xl font-semibold text-highlighted max-md:hidden">
        选择目的地
      </h1>

      <form role="search" action="/esim" @submit.prevent="onInput">
        <UInput
          v-model="keyword"
          name="q"
          size="xl"
          icon="i-lucide-search"
          placeholder="你要去哪里？输入国家或地区，如 日本、riben、Japan"
          class="w-full"
          :ui="{ base: 'rounded-full' }"
          :loading="status === 'pending'"
          aria-label="搜索目的地"
          @update:model-value="onInput"
        />
        <p class="mt-2 text-sm text-muted">
          支持中文、拼音与英文搜索；搜国家时一并列出覆盖它的多国套餐。
        </p>
      </form>

      <template v-if="q">
        <div v-if="list.length" class="divide-y divide-(--ui-border-muted) rounded-(--ui-radius) border border-muted bg-default">
          <NuxtLink
            v-for="d in list"
            :key="d.code"
            :to="`/esim/${d.code.toLowerCase()}`"
            class="flex items-center gap-3 px-3 py-2.5 hover:bg-elevated"
          >
            <DestinationCode :code="d.code" :kind="d.kind" />
            <span class="min-w-0 flex-1">
              <b class="font-semibold text-highlighted">{{ d.nameZh }}</b>
              <span class="ml-1.5 text-sm text-muted">{{ d.kind === 'COUNTRY' ? d.nameEn : hint }}</span>
            </span>
            <span class="text-sm text-muted">{{ destinationFrom(d) }}</span>
          </NuxtLink>
        </div>
        <div v-else-if="status !== 'pending'" class="py-12 text-center">
          <p class="font-medium text-highlighted">
            没有找到「{{ q }}」
          </p>
          <p class="mt-1 text-sm text-muted">
            换个名称试试，或看看覆盖更广的全球通用套餐
          </p>
          <UButton class="mt-4" variant="outline" :to="{ path: '/esim', query: { tab: 'GLOBAL' } }" label="查看全球通用" />
        </div>
      </template>

      <template v-else>
        <nav class="inline-flex max-w-full gap-0.5 overflow-x-auto rounded-full bg-muted p-1" aria-label="目的地类型">
          <NuxtLink
            v-for="t in tabs"
            :key="t.kind"
            :to="{ query: t.kind === 'COUNTRY' ? {} : { tab: t.kind } }"
            replace
            class="whitespace-nowrap rounded-full px-3.5 py-1.5 text-sm"
            :class="tab === t.kind ? 'bg-default font-semibold text-highlighted shadow-xs' : 'text-toned'"
          >
            {{ t.label }}
          </NuxtLink>
        </nav>

        <div v-if="groups.length > 1" class="flex gap-2 overflow-x-auto pb-0.5">
          <UButton
            size="sm"
            class="rounded-full"
            :variant="continent ? 'outline' : 'soft'"
            :color="continent ? 'neutral' : 'primary'"
            :to="pick({ continent: '' })"
            replace
            label="全部"
          />
          <UButton
            v-for="g in groups"
            :key="g.label"
            size="sm"
            class="rounded-full"
            :variant="continent === g.label ? 'soft' : 'outline'"
            :color="continent === g.label ? 'primary' : 'neutral'"
            :to="pick({ continent: g.label })"
            replace
            :label="g.label"
          />
        </div>

        <section v-for="g in shown" :key="g.label">
          <h2 v-if="groups.length > 1" class="mb-3 mt-2 font-semibold text-highlighted">
            {{ g.label }}
          </h2>
          <div class="grid gap-3" :class="tab === 'COUNTRY' ? 'grid-cols-2 md:grid-cols-4' : 'md:grid-cols-3'">
            <DestinationTile v-for="d in g.items" :key="d.code" :destination="d" :line="destinationLine(d)" :chevron="tab !== 'COUNTRY'" />
          </div>
        </section>

        <p v-if="!ofTab.length && status !== 'pending'" class="py-12 text-center text-muted">
          暂时没有在售的目的地
        </p>
      </template>
    </UContainer>
  </div>
</template>
