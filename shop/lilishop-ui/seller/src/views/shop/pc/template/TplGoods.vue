<template>
  <div class="tpl-goods-list" :style="customStyle">
    <!-- 标题栏 -->
    <div class="floor-title-bar" v-if="options.showTitle !== false && options.title">
      <div class="title-left">
        <span class="main-title">{{ options.title }}</span>
        <span class="sub-title" v-if="options.subtitle">{{ options.subtitle }}</span>
        <span class="category-tag-badge" v-if="options.dataSource === 'category' && (options.categoryName || options.categoryId)">
          🏷️ {{ options.categoryName || `分类: ${options.categoryId}` }}
        </span>
      </div>
      <span class="more-link" v-if="options.showMoreLink !== false">
        {{ options.moreText || '查看更多' }} &gt;
      </span>
    </div>

    <!-- 6 列商品列表网格 (整行展示 6 个商品) -->
    <div
      class="goods-flow-grid"
      :class="`cols-${actualColumns}`"
      :style="{ gridTemplateColumns: `repeat(${actualColumns}, 1fr)` }"
    >
      <div
        v-for="(g, idx) in sampleGoods"
        :key="g.id || idx"
        class="goods-flow-card"
      >
        <div class="card-thumb-wrap">
          <img :src="g.thumbnail || defaultThumb" :alt="g.goodsName" class="thumb-img" />
          <span class="self-tag" v-if="idx % 3 === 0">自营</span>
        </div>

        <div class="card-body">
          <div class="goods-name" v-if="options.showTitle !== false">
            {{ g.goodsName }}
          </div>

          <div class="price-row">
            <div class="price-box">
              <span class="symbol">￥</span>
              <span class="price-val" v-if="options.showPrice !== false">{{ g.price }}</span>
              <span class="orig-price" v-if="options.showOriginalPrice && g.originalPrice">￥{{ g.originalPrice }}</span>
            </div>
            <button
              type="button"
              class="add-cart-circle"
              v-if="options.showCartBtn !== false"
              title="加入购物车"
            >
              +
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue';
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

const defaultMockGoods = MOCK_GOODS;

const isGoodsCard = computed(() => {
  return props.component?.type === 'goodsCard' || props.options?.type === 'goodsCard';
});

const actualColumns = computed(() => {
  // 商品列表整行默认 6 列
  let baseCols = Number(props.options?.columns) || 6;
  const ratio = props.componentStyle?.widthRatio || '100%';
  if (ratio === '16.67%' || ratio === '16.6%' || ratio === '16.7%') {
    return 1;
  }
  if (ratio === '33.33%' || ratio === '33.3%') {
    return Math.min(baseCols, 2);
  }
  if (ratio === '50%') {
    return Math.min(baseCols, 3);
  }
  if (ratio === '66.67%' || ratio === '66.6%' || ratio === '66.7%') {
    return Math.min(baseCols, 4);
  }
  return baseCols; // 100% 默认 6 列
});

const customStyle = computed(() => {
  const s = props.componentStyle || {};
  return {
    backgroundColor: s.backgroundColor || 'transparent',
    padding: Array.isArray(s.padding)
      ? `${s.padding[0]}px ${s.padding[1]}px ${s.padding[2]}px ${s.padding[3]}px`
      : '14px 0',
    borderRadius: Array.isArray(s.radius)
      ? `${s.radius[0]}px ${s.radius[1]}px ${s.radius[2]}px ${s.radius[3]}px`
      : '0'
  };
});

const sampleGoods = computed(() => {
  if (props.options.dataSource === 'manual' && Array.isArray(props.options.goods) && props.options.goods.length > 0) {
    return props.options.goods;
  }
  const count = props.options.goodsCount || (actualColumns.value * 2) || 12;
  return defaultMockGoods.slice(0, count);
});
</script>

<style lang="scss" scoped>
.tpl-goods-list {
  width: 100%;
  user-select: none;
  box-sizing: border-box;

  .floor-title-bar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 14px;
    padding: 0 4px;

    .title-left {
      display: flex;
      align-items: baseline;
      gap: 10px;

      .main-title {
        font-size: 18px;
        font-weight: 700;
        color: #1e293b;
      }

      .sub-title {
        font-size: 12px;
        color: #94a3b8;
      }

      .category-tag-badge {
        font-size: 11px;
        background: #eff6ff;
        border: 1px solid #bfdbfe;
        color: #0b7a75;
        padding: 2px 6px;
        border-radius: 4px;
        font-weight: 500;
      }
    }

    .more-link {
      font-size: 12px;
      color: #64748b;
    }
  }

  .goods-flow-grid {
    display: grid;
    gap: 12px;

    .goods-flow-card {
      background: #ffffff;
      border-radius: 8px;
      border: 1px solid #f1f5f9;
      padding: 10px;
      display: flex;
      flex-direction: column;

      .card-thumb-wrap {
        position: relative;
        width: 100%;
        aspect-ratio: 1 / 1;
        background: #f8fafc;
        border-radius: 6px;
        overflow: hidden;
        margin-bottom: 8px;

        .thumb-img {
          width: 100%;
          height: 100%;
          object-fit: cover;
        }

        .self-tag {
          position: absolute;
          top: 6px;
          left: 6px;
          background: #e4572e;
          color: #ffffff;
          font-size: 9px;
          font-weight: 700;
          padding: 1px 5px;
          border-radius: 3px;
        }
      }

      .card-body {
        display: flex;
        flex-direction: column;
        flex: 1;

        .goods-name {
          font-size: 14px;
          font-weight: 500;
          color: #1e293b;
          line-height: 1.4;
          height: 40px;
          overflow: hidden;
          text-overflow: ellipsis;
          display: -webkit-box;
          -webkit-line-clamp: 2;
          -webkit-box-orient: vertical;
          margin-bottom: 4px;
        }

        .meta-row {
          display: flex;
          align-items: center;
          gap: 6px;
          font-size: 10px;
          color: #94a3b8;
          margin-bottom: 6px;
        }

        .price-row {
          margin-top: auto;
          display: flex;
          align-items: center;
          justify-content: space-between;

          .price-box {
            display: flex;
            align-items: baseline;

            .symbol {
              font-size: 11px;
              font-weight: 700;
              color: #c8461f;
            }

            .price-val {
              font-size: 16px;
              font-weight: 800;
              color: #c8461f;
            }

            .orig-price {
              font-size: 10px;
              color: #94a3b8;
              text-decoration: line-through;
              margin-left: 3px;
            }
          }

          .add-cart-circle {
            width: 22px;
            height: 22px;
            border-radius: 50%;
            border: none;
            background: #f1f5f9;
            color: #64748b;
            font-size: 14px;
            font-weight: 700;
            display: flex;
            align-items: center;
            justify-content: center;
          }
        }
      }
    }
  }
}
</style>
