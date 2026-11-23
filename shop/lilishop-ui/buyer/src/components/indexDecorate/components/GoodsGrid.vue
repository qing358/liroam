<template>
  <div class="decor-goods-grid" :style="customStyle">
    <!-- 宫格顶部标题区 -->
    <div class="grid-header" v-if="options.title">
      <div class="grid-title-wrap">
        <span class="grid-title-decor"></span>
        <h3 class="grid-main-title">{{ options.title }}</h3>
        <span class="grid-sub-title" v-if="options.subtitle">{{ options.subtitle }}</span>
      </div>
      <a href="javascript:;" class="grid-more" v-if="options.showMoreLink !== false" @click="handleNavigate(options.moreUrl || '/goods')">
        {{ options.moreText || '查看全部' }} &gt;
      </a>
    </div>

    <!-- 紧凑严谨的商品九宫格/矩阵网格 (Bordered Matrix Grid) -->
    <div
      class="matrix-grid-container"
      :class="[`cols-${actualColumns}`, { 'with-grid-border': options.showBorder !== false }]"
      :style="{ gridTemplateColumns: `repeat(${actualColumns}, 1fr)` }"
    >
      <div
        v-for="(item, idx) in displayGoods"
        :key="item.id || idx"
        class="matrix-grid-cell"
      >
        <div class="cell-rank-tag" v-if="idx < 5">
          <span class="rank-num">TOP {{ idx + 1 }}</span>
        </div>

        <a href="javascript:;" class="cell-thumb-box" @click="handleNavigate(item.url || `/goods/${item.id || 1}`)">
          <img :src="item.thumbnail || defaultThumb" :alt="item.goodsName" class="cell-thumb" />
        </a>

        <div class="cell-body">
          <h4 class="cell-name">
            <a href="javascript:;" @click="handleNavigate(item.url || `/goods/${item.id || 1}`)">{{ item.goodsName }}</a>
          </h4>
          <div class="cell-meta">
            <span class="badge-label">热卖好物</span>
          </div>
          <div class="cell-bottom-row">
            <div class="price-box">
              <span class="sym">￥</span>
              <span class="val">{{ item.price }}</span>
              <span class="del" v-if="item.originalPrice">￥{{ item.originalPrice }}</span>
            </div>
            <span class="action-add-pill" title="立即选购" @click="addToCart(item)">+</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { MOCK_GOODS } from '@brand/decorate-mock.js';

const props = defineProps({
  component: {
    type: Object,
    default: () => ({})
  },
  options: {
    type: Object,
    default: () => ({})
  },
  componentStyle: {
    type: Object,
    default: () => ({})
  }
});

const defaultThumb = '/liroam/goods-placeholder.svg';

const actualColumns = computed(() => {
  const ratio = props.componentStyle?.widthRatio || '100%';
  const base = Number(props.options?.columns) || 5;
  if (ratio === '16.67%' || ratio === '16.6%') return 1;
  if (ratio === '33.33%' || ratio === '33.3%') return 2;
  if (ratio === '50%') return 3;
  if (ratio === '66.67%' || ratio === '66.6%') return 4;
  return base;
});

const defaultGridGoods = MOCK_GOODS.slice(0, 10);

const displayGoods = computed(() => {
  if (Array.isArray(props.options?.goods) && props.options.goods.length > 0) {
    return props.options.goods;
  }
  const count = (actualColumns.value * 2) || 10;
  return defaultGridGoods.slice(0, count);
});

const customStyle = computed(() => {
  const s = props.componentStyle || {};
  return {
    backgroundColor: s.backgroundColor || '#ffffff',
    padding: Array.isArray(s.padding)
      ? `${s.padding[0]}px ${s.padding[1]}px ${s.padding[2]}px ${s.padding[3]}px`
      : '18px 20px',
    borderRadius: Array.isArray(s.radius)
      ? `${s.radius[0]}px ${s.radius[1]}px ${s.radius[2]}px ${s.radius[3]}px`
      : '12px'
  };
});

function addToCart(item) {
  ElMessage.success(`已将 "${item.goodsName?.slice(0, 10)}..." 加入购物车`);
}
</script>

<style lang="scss" scoped>
.decor-goods-grid {
  width: 100%;
  box-sizing: border-box;

  .grid-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding-bottom: 14px;
    margin-bottom: 14px;
    border-bottom: 1px solid #f1f5f9;

    .grid-title-wrap {
      display: flex;
      align-items: center;
      gap: 10px;

      .grid-title-decor {
        width: 4px;
        height: 18px;
        background: var(--lr-accent);
        border-radius: 2px;
      }

      .grid-main-title {
        font-size: 19px;
        font-weight: 700;
        color: #0f172a;
        margin: 0;
      }

      .grid-sub-title {
        font-size: 12px;
        color: #64748b;
        margin-left: 4px;
      }
    }

    .grid-more {
      font-size: 13px;
      color: #64748b;
      text-decoration: none;
      transition: color 0.2s;

      &:hover {
        color: var(--lr-accent-text);
      }
    }
  }

  .matrix-grid-container {
    display: grid;
    background: #ffffff;
    box-sizing: border-box;

    &.with-grid-border {
      border: 1px solid #e2e8f0;
      border-radius: 8px;
      overflow: hidden;

      .matrix-grid-cell {
        border-right: 1px solid #f1f5f9;
        border-bottom: 1px solid #f1f5f9;
      }
    }

    .matrix-grid-cell {
      position: relative;
      padding: 16px 14px 14px 14px;
      display: flex;
      flex-direction: column;
      box-sizing: border-box;
      background: #ffffff;
      transition: all 0.2s ease;

      &:hover {
        background: #fafbfc;
        z-index: 1;
        box-shadow: 0 4px 14px rgba(0, 0, 0, 0.05);

        .cell-thumb {
          transform: scale(1.06);
        }

        .action-add-pill {
          background: var(--lr-accent-text);
          color: #ffffff;
        }
      }

      .cell-rank-tag {
        position: absolute;
        top: 10px;
        left: 10px;
        z-index: 2;

        .rank-num {
          display: inline-block;
          font-size: 10px;
          font-weight: 800;
          color: var(--lr-accent-text);
          background: #ffffff;
          border: 1px solid var(--lr-accent);
          padding: 1px 6px;
          border-radius: 4px;
        }
      }

      .cell-thumb-box {
        display: flex;
        align-items: center;
        justify-content: center;
        width: 100%;
        aspect-ratio: 1 / 1;
        overflow: hidden;
        margin-bottom: 12px;
        text-decoration: none;

        .cell-thumb {
          width: 85%;
          height: 85%;
          object-fit: contain;
          transition: transform 0.3s ease;
        }
      }

      .cell-body {
        display: flex;
        flex-direction: column;
        flex: 1;

        .cell-name {
          font-size: 14px;
          font-weight: 500;
          line-height: 1.4;
          height: 40px;
          overflow: hidden;
          text-overflow: ellipsis;
          display: -webkit-box;
          -webkit-line-clamp: 2;
          -webkit-box-orient: vertical;
          margin: 0 0 6px 0;

          a {
            color: #334155;
            text-decoration: none;
            &:hover {
              color: var(--lr-accent-text);
            }
          }
        }

        .cell-meta {
          display: flex;
          margin-bottom: 8px;

          .badge-label {
            font-size: 10px;
            color: var(--lr-primary-hover);
            background: var(--lr-mist);
            padding: 1px 6px;
            border-radius: 3px;
          }
        }

        .cell-bottom-row {
          margin-top: auto;
          display: flex;
          align-items: baseline;
          justify-content: space-between;

          .price-box {
            display: flex;
            align-items: baseline;
            gap: 1px;

            .sym {
              font-size: 12px;
              font-weight: 700;
              color: #c8461f;
            }

            .val {
              font-size: 18px;
              font-weight: 800;
              color: #c8461f;
            }

            .del {
              font-size: 11px;
              color: #94a3b8;
              text-decoration: line-through;
              margin-left: 4px;
            }
          }

          .action-add-pill {
            display: inline-flex;
            align-items: center;
            justify-content: center;
            width: 22px;
            height: 22px;
            border-radius: 50%;
            background: #f1f5f9;
            color: #64748b;
            font-size: 15px;
            font-weight: 600;
            line-height: 1;
            transition: all 0.2s;
            cursor: pointer;
          }
        }
      }
    }
  }
}
</style>
