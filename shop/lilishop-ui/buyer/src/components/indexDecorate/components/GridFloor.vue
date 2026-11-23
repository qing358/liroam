<template>
  <div class="decor-grid-floor" :style="customStyle">
    <!-- 楼层大标题栏 (Floor Title Bar) -->
    <div class="floor-title-header" v-if="options.showFloorTitle !== false">
      <div class="floor-main-heading">
        <span class="floor-badge">{{ options.floorNum || '1F' }}</span>
        <h3 class="floor-title-text">{{ options.title || MOCK_FLOOR.title }}</h3>
        <span class="floor-subtitle-text" v-if="options.subtitle">{{ options.subtitle }}</span>
      </div>

      <div class="floor-header-right">
        <!-- 子分类快捷标签 -->
        <div class="sub-category-tags" v-if="subCategories.length">
          <router-link
            v-for="(tag, idx) in subCategories"
            :key="idx"
            to="/goods"
            class="tag-item"
            :class="{ active: activeTagIdx === idx }"
            @click.prevent="activeTagIdx = idx"
          >
            {{ tag }}
          </router-link>
        </div>
        <a href="javascript:;" class="more-link" @click="handleNavigate(options.moreUrl || '/goods')">
          查看更多 &gt;
        </a>
      </div>
    </div>

    <!-- 楼层主体：左侧竖向营销海报 + 右侧商品网格 -->
    <div class="floor-main-body" :class="{ 'no-poster': !showPoster }">
      <!-- 左侧营销大促海报 -->
      <div class="floor-left-poster" v-if="showPoster">
        <div class="poster-link" @click="handleNavigate(options.bannerUrl || options.posterUrl || '/goods')">
          <img :src="posterImg" alt="floor poster" class="poster-img" />
          <div class="poster-overlay">
            <div class="poster-top-badge">{{ options.floorNum || '1F' }} 甄选</div>
            <h4 class="poster-headline">{{ options.posterTitle || MOCK_FLOOR.posterTitle }}</h4>
            <p class="poster-subline">{{ options.posterSub || MOCK_FLOOR.posterSub }}</p>
            <div class="poster-pill-tags" v-if="posterTags.length">
              <span v-for="(pt, pIdx) in posterTags" :key="pIdx" class="pill-tag">{{ pt }}</span>
            </div>
            <div class="poster-action-btn">
              <span>立即抢购 &gt;</span>
            </div>
          </div>
        </div>
      </div>

      <!-- 右侧商品网格 (2行 x N列) -->
      <div
        class="floor-right-goods"
        :class="`cols-${rightColumns}`"
        :style="{ gridTemplateColumns: `repeat(${rightColumns}, 1fr)` }"
      >
        <div
          v-for="(g, idx) in displayGoods"
          :key="g.id || idx"
          class="floor-goods-card"
        >
          <a href="javascript:;" class="card-thumb-wrap" @click="handleNavigate(g.url || `/goods/${g.id || 1}`)">
            <img :src="g.thumbnail || defaultThumb" :alt="g.goodsName" class="goods-img" />
            <span class="corner-badge" v-if="idx < 3">HOT</span>
          </a>

          <div class="card-detail">
            <h4 class="goods-name-line">
              <a href="javascript:;" @click="handleNavigate(g.url || `/goods/${g.id || 1}`)">{{ g.goodsName }}</a>
            </h4>
            <div class="goods-tag-row">
              <span class="mini-tag">官方直营</span>
              <span class="mini-tag light">免息</span>
            </div>
            <div class="price-action-row">
              <div class="price-group">
                <span class="currency">￥</span>
                <span class="amount">{{ g.price }}</span>
                <span class="orig-amount" v-if="g.originalPrice">￥{{ g.originalPrice }}</span>
              </div>
              <button
                type="button"
                class="buy-action-btn"
                title="加入购物车"
                @click="addToCart(g)"
              >
                <svg viewBox="0 0 24 24" width="14" height="14" fill="currentColor">
                  <path d="M11 9h2V6h3V4h-3V1h-2v3H8v2h3v3zm-4 9c-1.1 0-1.99.9-1.99 2S5.9 22 7 22s2-.9 2-2-.9-2-2-2zm10 0c-1.1 0-1.99.9-1.99 2s.89 2 1.99 2 2-.9 2-2-.9-2-2-2zm-9.83-3.25l.03-.12.9-1.63h7.45c.75 0 1.41-.41 1.75-1.03l3.86-7.01L19.42 4h-.01l-1.1 2-2.76 5H8.53l-.13-.27L6.16 6l-.95-2-.94-2H1v2h2l3.6 7.59-1.35 2.45c-.16.28-.25.61-.25.96 0 1.1.9 2 2 2h12v-2H7.42c-.13 0-.25-.11-.25-.25z"/>
                </svg>
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { MOCK_FLOOR, MOCK_FLOOR_BANNER, MOCK_GOODS } from '@brand/decorate-mock.js';

const router = useRouter();

function handleNavigate(url) {
  const target = url ? String(url).trim() : '/goods';
  if (!target) return;
  if (/^https?:\/\//i.test(target)) {
    window.open(target, '_blank');
  } else {
    router.push(target);
  }
}

function handleNavigateGoods(g) {
  if (g.url) {
    handleNavigate(g.url);
    return;
  }
  router.push(`/goods/${g.id || 1}`);
}

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
const activeTagIdx = ref(0);

const subCategories = computed(() => {
  if (Array.isArray(props.options?.subCategories) && props.options.subCategories.length > 0) {
    return props.options.subCategories;
  }
  return MOCK_FLOOR.subCategories;
});

const posterTags = computed(() => {
  if (Array.isArray(props.options?.posterTags) && props.options.posterTags.length > 0) {
    return props.options.posterTags;
  }
  return MOCK_FLOOR.posterTags;
});

const posterImg = computed(() => {
  return props.options?.posterImg || MOCK_FLOOR_BANNER;
});

const showPoster = computed(() => {
  const ratio = props.componentStyle?.widthRatio || '100%';
  if (ratio === '16.67%' || ratio === '16.6%' || ratio === '33.33%' || ratio === '33.3%') {
    return false;
  }
  return props.options?.showPoster !== false;
});

const rightColumns = computed(() => {
  const ratio = props.componentStyle?.widthRatio || '100%';
  let baseCols = Number(props.options?.columns) || 4;
  if (ratio === '16.67%' || ratio === '16.6%') return 1;
  if (ratio === '33.33%' || ratio === '33.3%') return 2;
  if (ratio === '50%') return showPoster.value ? 2 : 3;
  if (ratio === '66.67%' || ratio === '66.6%') return showPoster.value ? 3 : 4;
  return baseCols;
});

const defaultFloorGoods = MOCK_GOODS.slice(0, 8);

const displayGoods = computed(() => {
  if (Array.isArray(props.options?.goods) && props.options.goods.length > 0) {
    return props.options.goods;
  }
  const count = (rightColumns.value * 2) || 8;
  return defaultFloorGoods.slice(0, count);
});

const customStyle = computed(() => {
  const s = props.componentStyle || {};
  return {
    backgroundColor: s.backgroundColor || '#ffffff',
    padding: Array.isArray(s.padding)
      ? `${s.padding[0]}px ${s.padding[1]}px ${s.padding[2]}px ${s.padding[3]}px`
      : '16px 20px',
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
.decor-grid-floor {
  width: 100%;
  box-sizing: border-box;

  .floor-title-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding-bottom: 14px;
    border-bottom: 2px solid #f1f5f9;
    margin-bottom: 16px;

    .floor-main-heading {
      display: flex;
      align-items: center;
      gap: 10px;

      .floor-badge {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        background: linear-gradient(135deg, #0b7a75, #09625e);
        color: #ffffff;
        font-weight: 800;
        font-size: 13px;
        padding: 3px 10px;
        border-radius: 4px;
        letter-spacing: 0.5px;
        box-shadow: 0 2px 6px rgba(37, 99, 235, 0.25);
      }

      .floor-title-text {
        font-size: 20px;
        font-weight: 700;
        color: #1e293b;
        letter-spacing: -0.2px;
        margin: 0;
      }

      .floor-subtitle-text {
        font-size: 12px;
        font-weight: 600;
        color: #94a3b8;
        text-transform: uppercase;
        letter-spacing: 1px;
        margin-left: 4px;
      }
    }

    .floor-header-right {
      display: flex;
      align-items: center;
      gap: 16px;

      .sub-category-tags {
        display: flex;
        align-items: center;
        gap: 8px;

        .tag-item {
          font-size: 13px;
          color: #64748b;
          padding: 4px 10px;
          border-radius: 14px;
          text-decoration: none;
          transition: all 0.2s;

          &:hover, &.active {
            color: #0b7a75;
            background: #eff6ff;
            font-weight: 600;
          }
        }
      }

      .more-link {
        cursor: pointer;
        font-size: 13px;
        color: #64748b;
        text-decoration: none;
        display: flex;
        align-items: center;
        gap: 2px;
        transition: color 0.2s;

        &:hover {
          color: #0b7a75;
        }
      }
    }
  }

  .floor-main-body {
    display: flex;
    gap: 16px;
    align-items: stretch;

    &.no-poster {
      .floor-right-goods {
        width: 100%;
      }
    }

    .floor-left-poster {
      width: 240px;
      flex-shrink: 0;
      border-radius: 10px;
      overflow: hidden;
      position: relative;
      background: #1e293b;

      .poster-link {
        cursor: pointer;
        display: block;
        position: relative;
        width: 100%;
        height: 100%;
        min-height: 520px;
        text-decoration: none;

        .poster-img {
          width: 100%;
          height: 100%;
          object-fit: cover;
          display: block;
          filter: brightness(0.85);
          transition: transform 0.4s ease;
        }

        &:hover .poster-img {
          transform: scale(1.03);
        }

        .poster-overlay {
          position: absolute;
          inset: 0;
          padding: 24px 18px;
          display: flex;
          flex-direction: column;
          background: linear-gradient(180deg, rgba(15, 23, 42, 0.75) 0%, rgba(15, 23, 42, 0.2) 40%, rgba(15, 23, 42, 0.85) 100%);
          color: #ffffff;

          .poster-top-badge {
            align-self: flex-start;
            font-size: 11px;
            font-weight: 700;
            background: rgba(255, 255, 255, 0.25);
            backdrop-filter: blur(4px);
            padding: 3px 8px;
            border-radius: 4px;
            text-transform: uppercase;
            letter-spacing: 0.5px;
            margin-bottom: 12px;
          }

          .poster-headline {
            font-size: 22px;
            font-weight: 800;
            line-height: 1.3;
            margin: 0 0 6px 0;
            text-shadow: 0 2px 4px rgba(0, 0, 0, 0.4);
          }

          .poster-subline {
            font-size: 13px;
            color: rgba(255, 255, 255, 0.85);
            margin: 0 0 16px 0;
            line-height: 1.4;
          }

          .poster-pill-tags {
            display: flex;
            flex-wrap: wrap;
            gap: 6px;
            margin-bottom: auto;

            .pill-tag {
              font-size: 11px;
              padding: 3px 8px;
              background: rgba(255, 255, 255, 0.18);
              border-radius: 10px;
              backdrop-filter: blur(4px);
            }
          }

          .poster-action-btn {
            align-self: flex-start;
            margin-top: auto;
            background: #ffffff;
            color: #0f172a;
            font-size: 13px;
            font-weight: 700;
            padding: 8px 16px;
            border-radius: 20px;
            box-shadow: 0 4px 12px rgba(0, 0, 0, 0.2);
            transition: all 0.2s;

            &:hover {
              background: #0b7a75;
              color: #ffffff;
            }
          }
        }
      }
    }

    .floor-right-goods {
      flex: 1;
      display: grid;
      gap: 12px;

      .floor-goods-card {
        background: #ffffff;
        border: 1px solid #f1f5f9;
        border-radius: 8px;
        padding: 12px;
        display: flex;
        flex-direction: column;
        transition: all 0.25s ease;

        &:hover {
          border-color: #cbd5e1;
          box-shadow: 0 6px 16px rgba(0, 0, 0, 0.06);
          transform: translateY(-2px);
        }

        .card-thumb-wrap {
          display: block;
          position: relative;
          width: 100%;
          aspect-ratio: 1 / 1;
          background: #f8fafc;
          border-radius: 6px;
          overflow: hidden;
          margin-bottom: 10px;

          .goods-img {
            width: 100%;
            height: 100%;
            object-fit: cover;
            transition: transform 0.3s;
          }

          &:hover .goods-img {
            transform: scale(1.05);
          }

          .corner-badge {
            position: absolute;
            top: 6px;
            left: 6px;
            background: linear-gradient(135deg, #e4572e, #e4572e);
            color: #ffffff;
            font-size: 10px;
            font-weight: 700;
            padding: 2px 6px;
            border-radius: 4px;
          }
        }

        .card-detail {
          display: flex;
          flex-direction: column;
          flex: 1;

          .goods-name-line {
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
              color: #1e293b;
              text-decoration: none;
              &:hover {
                color: #0b7a75;
              }
            }
          }

          .goods-tag-row {
            display: flex;
            gap: 4px;
            margin-bottom: 8px;

            .mini-tag {
              font-size: 10px;
              padding: 1px 5px;
              border-radius: 3px;
              background: #fee2e2;
              color: #c8461f;

              &.light {
                background: #eff6ff;
                color: #0b7a75;
              }
            }
          }

          .price-action-row {
            margin-top: auto;
            display: flex;
            align-items: center;
            justify-content: space-between;

            .price-group {
              display: flex;
              align-items: baseline;
              gap: 2px;

              .currency {
                font-size: 12px;
                font-weight: 700;
                color: #c8461f;
              }

              .amount {
                font-size: 17px;
                font-weight: 800;
                color: #c8461f;
              }

              .orig-amount {
                font-size: 11px;
                color: #94a3b8;
                text-decoration: line-through;
                margin-left: 4px;
              }
            }

            .buy-action-btn {
              width: 26px;
              height: 26px;
              border-radius: 50%;
              border: none;
              background: #f1f5f9;
              color: #475569;
              display: flex;
              align-items: center;
              justify-content: center;
              cursor: pointer;
              transition: all 0.2s;

              &:hover {
                background: #0b7a75;
                color: #ffffff;
              }
            }
          }
        }
      }
    }
  }
}
</style>
