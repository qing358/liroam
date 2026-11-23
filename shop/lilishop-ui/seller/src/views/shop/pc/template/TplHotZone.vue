<template>
  <div class="tpl-hot-zone" :style="customStyle">
    <div class="zone-wrap" v-if="displayImageUrl">
      <img :src="displayImageUrl" alt="hotzone" />
      <div
        v-for="(zone, idx) in displayZones"
        :key="idx"
        class="zone-mask"
        :style="{
          top: `${zone.top !== undefined ? zone.top : ((zone.topPer || 0) * 100)}%`,
          left: `${zone.left !== undefined ? zone.left : ((zone.leftPer || 0) * 100)}%`,
          width: `${zone.width !== undefined ? zone.width : ((zone.widthPer || 0.1) * 100)}%`,
          height: `${zone.height !== undefined ? zone.height : ((zone.heightPer || 0.1) * 100)}%`
        }"
      >
        <span class="zone-label">{{ zone.title || `热区 ${idx + 1}` }}</span>
      </div>
    </div>
    <div v-else class="hotzone-empty">
      <span>热区图待配置底图</span>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue';
import { MOCK_HOT_ZONE } from '@brand/decorate-mock.js';

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
</script>

<style lang="scss" scoped>
.tpl-hot-zone {
  width: 100%;
  position: relative;
  user-select: none;
  box-sizing: border-box;

  .zone-wrap {
    position: relative;
    width: 100%;
    border-radius: inherit;
    overflow: hidden;

    img {
      width: 100%;
      display: block;
      border-radius: inherit;
      object-fit: cover;
    }

    .zone-mask {
      position: absolute;
      background: rgba(37, 99, 235, 0.22);
      border: 1.5px dashed #0b7a75;
      color: #ffffff;
      border-radius: 6px;
      display: flex;
      align-items: center;
      justify-content: center;
      backdrop-filter: blur(1px);
      box-shadow: 0 0 10px rgba(37, 99, 235, 0.35);
      transition: all 0.2s ease;

      &:hover {
        background: rgba(37, 99, 235, 0.35);
        border-color: #0b7a75;
      }

      .zone-label {
        font-size: 12px;
        font-weight: 600;
        text-shadow: 0 1px 3px rgba(0, 0, 0, 0.8);
        padding: 3px 6px;
        background: rgba(15, 23, 42, 0.65);
        border-radius: 4px;
        border: 1px solid rgba(255, 255, 255, 0.2);
        max-width: 90%;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
        text-align: center;
      }
    }
  }

  .hotzone-empty {
    padding: 40px;
    background: #f8fafc;
    border: 1px dashed #cbd5e1;
    text-align: center;
    color: #94a3b8;
    font-size: 13px;
  }
}
</style>
