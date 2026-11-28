<script setup lang="ts">
import type { Destination } from '~/services/destinations'

defineProps<{ destination: Destination, line: string, chevron?: boolean }>()
</script>

<template>
  <NuxtLink
    :to="`/esim/${destination.code.toLowerCase()}`"
    class="flex items-center gap-3 rounded-(--ui-radius) border border-muted bg-default p-3 transition-colors hover:border-(--lr-primary-light-5)"
  >
    <DestinationCode :code="destination.code" :kind="destination.kind" />
    <span class="min-w-0 flex-1">
      <b class="block truncate font-semibold text-highlighted">{{ destination.nameZh }}</b>
      <span class="block truncate text-sm text-muted">{{ line }}</span>
    </span>
    <UIcon v-if="chevron" name="i-lucide-chevron-right" class="size-4 text-dimmed" />
  </NuxtLink>
</template>
