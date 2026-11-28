<script setup lang="ts">
import { destinationBadge, type DestinationKind } from '~/services/destinations'

// 目的地两字母圆标：国家用雾青底，多国与全球用夜航墨底、日光黄字（设计稿约定，不用旗帜与 emoji）
const props = defineProps<{ code: string, kind?: DestinationKind, size?: 'md' | 'lg' }>()
const region = computed(() => props.kind === 'REGION_GROUP' || props.kind === 'GLOBAL')
</script>

<template>
  <span
    class="inline-flex flex-none items-center justify-center rounded-full font-bold tracking-wide"
    :class="[
      size === 'lg' ? 'size-14 text-base' : 'size-10 text-[13px]',
      region ? 'bg-(--lr-ink) text-(--lr-sun)' : 'bg-(--lr-mist) text-(--lr-primary-active)',
    ]"
    aria-hidden="true"
  >{{ destinationBadge(props) }}</span>
</template>
