<template>
  <div
    class="decor-category-mega-menu"
    :class="{ 'is-hovering': panelVisible && activeChildren.length }"
    :style="customStyle"
    @mouseleave="handleMouseLeave"
  >
    <!-- 顶部标题：分类 / 商品分类 -->
    <div class="menu-header">
      <span class="header-title">{{ options.title || '商品分类' }}</span>
    </div>

    <!-- 垂直一级类目列表 -->
    <div class="category-list">
      <div
        v-for="(cat, idx) in displayCategories"
        :key="cat.id || idx"
        class="cat-item"
        :class="{ 'is-active': activeIndex === idx && panelVisible }"
        :style="{
          '--text-color': options.textColor || '#334155',
          '--active-color': options.activeColor || '#0b7a75',
          '--font-size': `${options.fontSize || 13}px`
        }"
        @mouseenter="handleMouseEnter(idx, cat)"
        @click="goGoodsList(cat.id)"
      >
        <span class="cat-name">{{ cat.name }}</span>
        <el-icon class="arrow-icon"><ArrowRight /></el-icon>
      </div>
    </div>

    <!-- 右侧悬浮超级大面板 (Mega Menu) -->
    <transition name="fade-flyout">
      <div
        v-if="panelVisible && activeChildren.length"
        class="mega-flyout-panel"
        @mouseenter="handlePanelEnter"
        @mouseleave="handleMouseLeave"
      >
        <!-- 顶部快捷推荐标签（展示前8个二级分类） -->
        <div class="flyout-top-tags" v-if="activeChildren && activeChildren.length">
          <span
            v-for="(sub, sIdx) in activeChildren.slice(0, 8)"
            :key="sub.id || sIdx"
            class="top-tag-pill"
            @click.stop="goGoodsList(sub.id, sub.parentId)"
          >
            {{ sub.name }}
            <el-icon class="pill-arrow"><ArrowRight /></el-icon>
          </span>
        </div>

        <!-- 详细分类二级与三级平铺网格 -->
        <div class="flyout-content-scroll">
          <div
            v-for="(sub, sIdx) in activeChildren"
            :key="sub.id || sIdx"
            class="cat-row"
          >
            <!-- 二级分类标题 -->
            <div
              class="lvl2-title"
              @click.stop="goGoodsList(sub.id, sub.parentId)"
            >
              <span class="lvl2-name">{{ sub.name }}</span>
              <el-icon class="lvl2-arrow"><ArrowRight /></el-icon>
            </div>

            <!-- 三级分类细项 -->
            <div class="lvl3-tags-wrap">
              <span
                v-for="(leaf, lIdx) in (sub.children || [])"
                :key="leaf.id || lIdx"
                class="lvl3-tag"
                @click.stop="goGoodsList(leaf.id, sub.id, sub.parentId)"
              >
                {{ leaf.name }}
              </span>
            </div>
          </div>
        </div>
      </div>
    </transition>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { ArrowRight } from '@element-plus/icons-vue';
import { getCategory } from '@/api/goods';
import { MOCK_REGION_CATEGORIES, isMenuPlaceholder } from '@brand/decorate-mock.js';

const props = defineProps({
  options: {
    type: Object,
    default: () => ({})
  },
  componentStyle: {
    type: Object,
    default: () => ({})
  },
  rowStyle: {
    type: Object,
    default: () => ({})
  }
});

const router = useRouter();

// 弱网下分类接口未返回时的占位（按目的地分区的示例类目），防止空白
const fallbackCategories = MOCK_REGION_CATEGORIES;

const systemCategories = ref([]);
const activeIndex = ref(-1);
const activeCategory = ref(null);
const panelVisible = ref(false);

const isCustomSource = computed(() => {
  // 1. 如果明确指定为系统分类，始终按系统商品分类处理
  if (props.options?.source === 'system') return false;
  // 2. 如果配置为 custom，但分类条目依然是初始未编辑的 4 个占位条目，依然展示真实系统分类
  if (props.options?.source === 'custom') {
    if (isMenuPlaceholder(props.options?.categories)) return false;
    return true;
  }
  // 3. 其它默认情况均默认展示系统商品分类
  return false;
});

const displayCategories = computed(() => {
  if (isCustomSource.value && Array.isArray(props.options?.categories) && props.options.categories.length) {
    return props.options.categories.map(c => ({
      ...c,
      name: c.name || c.title || ''
    }));
  }
  if (systemCategories.value && systemCategories.value.length) {
    return systemCategories.value;
  }
  return fallbackCategories;
});

const activeChildren = computed(() => {
  if (!activeCategory.value) return [];
  if (Array.isArray(activeCategory.value.children) && activeCategory.value.children.length) {
    return activeCategory.value.children;
  }
  if (Array.isArray(activeCategory.value.subCategories) && activeCategory.value.subCategories.length) {
    return activeCategory.value.subCategories.map((s, idx) => ({
      id: s.id || `sub_${idx}`,
      name: s.name,
      parentId: activeCategory.value.id,
      children: (s.items || []).map((it, iIdx) => ({
        id: `leaf_${idx}_${iIdx}`,
        name: typeof it === 'string' ? it : it.name,
        parentId: s.id
      }))
    }));
  }
  return [];
});

function handleMouseEnter(idx, cat) {
  activeIndex.value = idx;
  activeCategory.value = cat;
  if (cat.children && cat.children.length) {
    panelVisible.value = true;
  } else if (cat.subCategories && cat.subCategories.length) {
    panelVisible.value = true;
  } else {
    panelVisible.value = false;
  }
}

function handlePanelEnter() {
  panelVisible.value = true;
}

function handleMouseLeave() {
  panelVisible.value = false;
  activeIndex.value = -1;
  activeCategory.value = null;
}

function goGoodsList(id, secondId, firstId) {
  const arr = [firstId, secondId, id];
  if (!arr[1]) arr.splice(0, 2);
  if (!arr[0]) arr.shift();
  router.push({
    path: '/goodsList',
    query: { categoryId: arr.filter(Boolean).join(',') }
  });
}

function loadCategories() {
  // 1. 优先读取 localStorage 缓存快速渲染
  try {
    const cached = localStorage.getItem('category');
    const exp = localStorage.getItem('category_expiration_time');
    if (cached && exp && new Date() <= Number(exp)) {
      const parsed = JSON.parse(cached);
      if (Array.isArray(parsed) && parsed.length) {
        systemCategories.value = parsed;
      }
    }
  } catch (e) {}

  // 2. 调接口拉取最新分类
  getCategory(0).then(res => {
    if (res.success && Array.isArray(res.result) && res.result.length) {
      systemCategories.value = res.result;
      const expTime = new Date().setHours(new Date().getHours() + 1);
      localStorage.setItem('category_expiration_time', expTime);
      localStorage.setItem('category', JSON.stringify(res.result));
    }
  }).catch(err => {
    console.warn('获取分类失败', err);
  });
}

onMounted(() => {
  loadCategories();
});

const customStyle = computed(() => {
  const s = props.componentStyle || {};
  const hasRowHeight = Boolean(props.rowStyle?.height && Number(props.rowStyle.height) > 0);
  return {
    backgroundColor: (s.backgroundColor && s.backgroundColor !== 'transparent') ? s.backgroundColor : '#ffffff',
    borderRadius: Array.isArray(s.radius) && s.radius.some(r => r > 0)
      ? `${s.radius[0]}px ${s.radius[1]}px ${s.radius[2]}px ${s.radius[3]}px`
      : '8px',
    height: '100%',
    minHeight: hasRowHeight ? '0' : '420px'
  };
});
</script>

<style lang="scss" scoped>
.decor-category-mega-menu {
  width: 100%;
  height: 100%;
  box-sizing: border-box;
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.03);
  display: flex;
  flex-direction: column;
  position: relative;
  overflow: visible;

  &.is-hovering {
    z-index: 1050;
  }

  .menu-header {
    height: 42px;
    padding: 0 16px;
    display: flex;
    align-items: center;
    border-bottom: 1px solid #f1f5f9;
    flex-shrink: 0;

    .header-title {
      font-size: 14px;
      font-weight: 700;
      color: #1e293b;
      letter-spacing: 0.5px;
    }
  }

  .category-list {
    flex: 1;
    display: flex;
    flex-direction: column;
    justify-content: space-evenly;
    padding: 4px 0 8px;
    box-sizing: border-box;
    min-height: 0;
    overflow-y: auto;

    .cat-item {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 6px 16px;
      cursor: pointer;
      text-decoration: none;
      transition: all 0.15s ease;

      .cat-name {
        font-size: var(--font-size, 13px);
        color: var(--text-color, #334155);
        font-weight: 500;
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
      }

      .arrow-icon {
        font-size: 11px;
        color: #94a3b8;
        transition: transform 0.15s ease, color 0.15s ease;
        flex-shrink: 0;
      }

      &:hover,
      &.is-active {
        background: #eef6f5;

        .cat-name {
          color: var(--active-color, #0b7a75);
          font-weight: 600;
        }

        .arrow-icon {
          color: var(--active-color, #0b7a75);
          transform: translateX(2px);
        }
      }
    }
  }

  .mega-flyout-panel {
    position: absolute;
    top: 0;
    left: 100%;
    width: 780px;
    min-height: 100%;
    max-height: 520px;
    background: #ffffff;
    z-index: 1100;
    box-shadow: 0 12px 32px rgba(0, 0, 0, 0.12), 0 2px 8px rgba(0, 0, 0, 0.04);
    border: 1px solid #e2e8f0;
    border-left: none;
    border-radius: 0 8px 8px 0;
    padding: 16px 20px;
    box-sizing: border-box;
    display: flex;
    flex-direction: column;

    .flyout-top-tags {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      padding-bottom: 12px;
      border-bottom: 1px solid #f1f5f9;
      margin-bottom: 12px;
      flex-shrink: 0;

      .top-tag-pill {
        display: inline-flex;
        align-items: center;
        gap: 4px;
        padding: 4px 10px;
        background: #f8fafc;
        border-radius: 14px;
        font-size: 12px;
        font-weight: 600;
        color: #334155;
        cursor: pointer;
        transition: all 0.15s;

        .pill-arrow {
          font-size: 10px;
          color: #94a3b8;
        }

        &:hover {
          background: #eef6f5;
          color: #0b7a75;

          .pill-arrow {
            color: #0b7a75;
          }
        }
      }
    }

    .flyout-content-scroll {
      flex: 1;
      overflow-y: auto;
      padding-right: 6px;

      &::-webkit-scrollbar {
        width: 4px;
      }
      &::-webkit-scrollbar-thumb {
        background: #cbd5e1;
        border-radius: 4px;
      }

      .cat-row {
        display: flex;
        align-items: flex-start;
        padding: 10px 0;
        border-bottom: 1px dashed #f1f5f9;

        &:last-child {
          border-bottom: none;
        }

        .lvl2-title {
          width: 96px;
          flex-shrink: 0;
          display: inline-flex;
          align-items: center;
          gap: 3px;
          cursor: pointer;
          color: #1e293b;
          font-weight: 600;
          font-size: 12px;
          padding-top: 1px;

          .lvl2-name {
            overflow: hidden;
            text-overflow: ellipsis;
            white-space: nowrap;
          }

          .lvl2-arrow {
            font-size: 10px;
            color: #94a3b8;
          }

          &:hover {
            color: #0b7a75;
            .lvl2-arrow {
              color: #0b7a75;
            }
          }
        }

        .lvl3-tags-wrap {
          flex: 1;
          display: flex;
          flex-wrap: wrap;
          gap: 6px 14px;

          .lvl3-tag {
            font-size: 12px;
            color: #64748b;
            cursor: pointer;
            transition: color 0.15s;
            line-height: 1.6;

            &:hover {
              color: #0b7a75;
            }
          }
        }
      }
    }
  }
}

.fade-flyout-enter-active,
.fade-flyout-leave-active {
  transition: opacity 0.15s ease, transform 0.15s ease;
}

.fade-flyout-enter-from,
.fade-flyout-leave-to {
  opacity: 0;
  transform: translateX(-4px);
}
</style>
