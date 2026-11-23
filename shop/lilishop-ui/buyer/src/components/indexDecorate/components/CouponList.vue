<template>
  <div class="decor-coupon-list" :class="`skin-${options.skin || 'accent'}`" :style="customStyle">
    <div
      class="coupon-grid"
      :style="{ gridTemplateColumns: `repeat(${actualColumns}, 1fr)` }"
    >
      <div
        v-for="(cp, idx) in coupons"
        :key="idx"
        class="coupon-card"
      >
        <div class="card-left">
          <div class="amount-wrap">
            <span class="symbol">￥</span>
            <span class="amount">{{ cp.price }}</span>
          </div>
          <span class="threshold">{{ cp.consumeThreshold ? `满 ${cp.consumeThreshold} 可用` : '无门槛' }}</span>
        </div>
        <div class="card-right">
          <span class="scope">{{ cp.title }}</span>
          <button type="button" class="claim-btn" @click="handleClaim(cp)">
            立即领取
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue';
import { ElMessage } from 'element-plus';
import { MOCK_COUPONS } from '@brand/decorate-mock.js';

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

const defaultCoupons = MOCK_COUPONS;

const actualColumns = computed(() => {
  const baseCols = Number(props.options?.columns) || 4;
  const ratio = props.componentStyle?.widthRatio || '100%';
  if (ratio === '16.67%' || ratio === '16.6%' || ratio === '16.7%') {
    return 1;
  }
  if (ratio === '33.33%' || ratio === '33.3%') {
    return Math.min(baseCols, 2);
  }
  if (ratio === '50%') {
    return Math.min(baseCols, 2);
  }
  if (ratio === '66.67%' || ratio === '66.6%' || ratio === '66.7%') {
    return Math.min(baseCols, 3);
  }
  return baseCols;
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
      : '0'
  };
});

const coupons = computed(() => {
  if (Array.isArray(props.options?.coupons) && props.options.coupons.length > 0) {
    return props.options.coupons;
  }
  const count = props.options?.columns || 4;
  return defaultCoupons.slice(0, count);
});

function handleClaim(cp) {
  ElMessage.success(`领取成功！￥${cp.price} 优惠券已放入您的卡券包`);
}
</script>

<style lang="scss" scoped>
.decor-coupon-list {
  width: 100%;

  .coupon-grid {
    display: grid;
    gap: 16px;

    .coupon-card {
      display: flex;
      height: 90px;
      border-radius: 8px;
      overflow: hidden;
      box-shadow: 0 4px 12px rgba(0, 0, 0, 0.05);
      transition: transform 0.2s;

      &:hover {
        transform: translateY(-2px);
      }

      .card-left {
        width: 120px;
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        color: #ffffff;
        padding: 8px;

        .amount-wrap {
          display: flex;
          align-items: baseline;

          .symbol { font-size: 14px; font-weight: 700; }
          .amount { font-size: 28px; font-weight: 800; }
        }

        .threshold {
          font-size: 11px;
          opacity: 0.9;
        }
      }

      .card-right {
        flex: 1;
        background: #ffffff;
        display: flex;
        flex-direction: column;
        justify-content: space-between;
        padding: 12px 14px;

        .scope {
          font-size: 13px;
          font-weight: 600;
          color: #1e293b;
        }

        .claim-btn {
          align-self: flex-start;
          padding: 4px 12px;
          border-radius: 14px;
          border: none;
          font-size: 12px;
          font-weight: 600;
          cursor: pointer;
          transition: opacity 0.2s;

          &:hover { opacity: 0.85; }
        }
      }
    }
  }

  &.skin-accent {
    .coupon-card .card-left { background: var(--lr-accent-text); }
    .claim-btn { background: var(--lr-accent-text); color: white; }
  }

  &.skin-ink {
    .coupon-card .card-left { background: linear-gradient(135deg, var(--lr-ink), var(--lr-primary-active)); }
    .amount-wrap { color: var(--lr-sun); }
    .claim-btn { background: var(--lr-mist); color: var(--lr-ink); }
  }

  &.skin-primary {
    .coupon-card .card-left { background: linear-gradient(135deg, var(--lr-primary), var(--lr-primary-active)); }
    .claim-btn { background: var(--lr-mist); color: var(--lr-primary-hover); }
  }
}
</style>
