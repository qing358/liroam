<template>
  <div class="decor-hot-zone" :style="customStyle">
    <div class="hot-zone-wrap" v-if="displayImageUrl">
      <img :src="displayImageUrl" alt="hotzone" class="base-image" />
      <div
        v-for="(zone, idx) in displayZones"
        :key="idx"
        class="zone-link"
        :style="{
          top: `${zone.top !== undefined ? zone.top : ((zone.topPer || 0) * 100)}%`,
          left: `${zone.left !== undefined ? zone.left : ((zone.leftPer || 0) * 100)}%`,
          width: `${zone.width !== undefined ? zone.width : ((zone.widthPer || 0.1) * 100)}%`,
          height: `${zone.height !== undefined ? zone.height : ((zone.heightPer || 0.1) * 100)}%`
        }"
        :title="zone.title"
        @click="handleClickZone(zone)"
      />
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue';
import { useRouter } from 'vue-router';
import { MOCK_HOT_ZONE } from '@brand/decorate-mock.js';

const router = useRouter();

const props = defineProps({
  options: {
    type: Object,
    default: () => ({})
  },
  componentStyle: {
    type: Object,
    default: () => ({})
  }
});

const defaultZones = MOCK_HOT_ZONE.zones;

const displayImageUrl = computed(() => {
  return props.options?.imageUrl || MOCK_HOT_ZONE.imageUrl;
});

const displayZones = computed(() => {
  if (Array.isArray(props.options?.zones) && props.options.zones.length > 0) {
    return props.options.zones;
  }
  return defaultZones;
});

const customStyle = computed(() => {
  const s = props.componentStyle || {};
  return {
    backgroundColor: s.backgroundColor || 'transparent',
    padding: Array.isArray(s.padding)
      ? `${s.padding[0]}px ${s.padding[1]}px ${s.padding[2]}px ${s.padding[3]}px`
      : '10px 0',
    borderRadius: Array.isArray(s.radius)
      ? `${s.radius[0]}px ${s.radius[1]}px ${s.radius[2]}px ${s.radius[3]}px`
      : '8px',
    overflow: 'hidden'
  };
});

function handleClickZone(zone) {
  const url = (zone?.url || zone?.link) ? String(zone.url || zone.link).trim() : '';
  if (!url) return;
  if (/^https?:\/\//i.test(url)) {
    window.open(url, '_blank');
  } else {
    router.push(url);
  }
}
</script>

<style lang="scss" scoped>
.decor-hot-zone {
  width: 100%;
  box-sizing: border-box;

  .hot-zone-wrap {
    position: relative;
    width: 100%;
    max-width: 1600px;
    margin: 0 auto;
    border-radius: inherit;
    overflow: hidden;

    .base-image {
      display: block;
      width: 100%;
      height: auto;
      user-select: none;
      pointer-events: none;
    }

    .zone-link {
      position: absolute;
      display: block;
      cursor: pointer;
      z-index: 2;
      transition: background 0.2s, box-shadow 0.2s;

      &:hover {
        background: rgba(255, 255, 255, 0.12);
        box-shadow: inset 0 0 0 2px rgba(255, 255, 255, 0.4);
      }
    }
  }
}
</style>
