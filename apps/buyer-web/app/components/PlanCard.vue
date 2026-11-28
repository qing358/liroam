<script setup lang="ts">
import { planData, planTags, type Plan } from '~/services/destinations'
import { formatPrice } from '~/utils/money'

defineProps<{ plan: Plan, to: string }>()
</script>

<template>
  <NuxtLink
    :to="to"
    class="flex flex-col gap-2.5 rounded-(--ui-radius) border border-muted bg-default p-4 transition hover:border-(--lr-primary-light-5) hover:shadow-sm"
  >
    <div>
      <p class="text-[26px] font-bold leading-tight text-highlighted">
        {{ planData(plan).value }}<small class="ml-0.5 text-sm font-semibold">{{ planData(plan).unit }}</small>
      </p>
      <p class="font-medium text-toned">
        {{ plan.days }} 天
      </p>
    </div>
    <div class="flex flex-wrap gap-1.5">
      <span v-for="tag in planTags(plan)" :key="tag" class="rounded border border-default px-1.5 text-xs leading-5 text-toned">{{ tag }}</span>
    </div>
    <div class="mt-auto flex items-center justify-between border-t border-dashed border-default pt-2.5">
      <span class="text-[22px] font-semibold text-(--lr-accent-text)">{{ formatPrice(plan.priceFen) }}</span>
      <UButton size="sm" variant="soft" label="选择" tabindex="-1" />
    </div>
  </NuxtLink>
</template>
