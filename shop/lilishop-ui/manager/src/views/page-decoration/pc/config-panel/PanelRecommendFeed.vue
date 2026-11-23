<template>
  <div class="panel-recommend-feed">
    <!-- 1. 标题设置 -->
    <div class="panel-section">
      <div class="section-title">标题设置</div>
      <div class="form-item">
        <span class="item-label">主标题</span>
        <el-input v-model="options.title" size="small" placeholder="如：猜你喜欢" />
      </div>
      <div class="form-item">
        <span class="item-label">副标题</span>
        <el-input v-model="options.subtitle" size="small" placeholder="如：真实推荐商品流" />
      </div>
    </div>

    <!-- 2. 布局样式 -->
    <div class="panel-section">
      <div class="section-title">布局样式</div>
      <div class="form-item">
        <span class="item-label">展现形态</span>
        <el-radio-group v-model="options.layoutStyle" size="small">
          <el-radio-button label="featured">精选大卡+网格 (推荐)</el-radio-button>
          <el-radio-button label="grid">标准推荐网格</el-radio-button>
        </el-radio-group>
      </div>
      <div class="form-item">
        <span class="item-label">背景色</span>
        <el-color-picker v-model="options.backgroundColor" size="small" />
      </div>
    </div>

    <!-- 3. 加载与数量控制 -->
    <div class="panel-section">
      <div class="section-title">推荐流加载设置</div>
      <div class="form-item">
        <span class="item-label">首屏/每页数量</span>
        <el-input-number v-model="options.pageSize" :min="4" :max="20" size="small" controls-position="right" />
      </div>
      <div class="form-item">
        <span class="item-label">最多加载页数</span>
        <el-input-number v-model="options.maxPages" :min="1" :max="10" size="small" controls-position="right" />
      </div>
    </div>

    <!-- 4. 推荐商品池管理 -->
    <div class="panel-section">
      <div class="section-title flex-between">
        <span>推荐商品池 ({{ options.goods?.length || 0 }} 件)</span>
        <div class="btn-group">
          <el-button v-if="options.goods?.length" link type="danger" size="small" @click="options.goods = []">
            清空
          </el-button>
          <el-button type="primary" size="small" @click="handleOpenGoodsPicker">
            选择商品
          </el-button>
        </div>
      </div>

      <div class="goods-list-preview" v-if="options.goods?.length">
        <div v-for="(g, idx) in options.goods" :key="g.id || idx" class="goods-item-row">
          <span class="idx-tag">{{ idx === 0 ? '精选' : idx + 1 }}</span>
          <img :src="g.thumbnail || defaultThumb" class="g-thumb" alt="" />
          <div class="g-info">
            <span class="g-name" :title="g.goodsName">{{ g.goodsName }}</span>
            <span class="g-price">￥{{ g.price }}</span>
          </div>
          <el-button link type="danger" size="small" @click="options.goods.splice(idx, 1)">
            <el-icon><Delete /></el-icon>
          </el-button>
        </div>
      </div>
      <div class="goods-empty-tip" v-else>
        <span>暂未选择特定商品，前台将展示系统推荐商品</span>
        <el-button type="primary" link size="small" @click="handleOpenGoodsPicker">
          + 点击添加商品
        </el-button>
      </div>
    </div>

    <!-- 兜底商品选择弹窗（当未注入全局 openGoodsPicker 时生效） -->
    <el-dialog
      v-if="!openGoodsPicker && showModal"
      v-model="showModal"
      title="从商品库选择商品"
      width="1160px"
      append-to-body
      destroy-on-close
    >
      <goods-dialog
        :selectedWay="tempSelectedGoods"
        @selected="(val) => { tempSelectedGoods = val; }"
      />
      <template #footer>
        <el-button @click="showModal = false">取消</el-button>
        <el-button type="primary" @click="confirmGoodsSelected">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, inject } from 'vue';
import GoodsDialog from '@/components/lili-dialog/goods-dialog.vue';
import { Delete } from '@element-plus/icons-vue';
import { copyOf, MOCK_GOODS } from '@brand/decorate-mock.js';

const props = defineProps({
  component: {
    type: Object,
    default: () => ({})
  },
  options: {
    type: Object,
    default: () => ({})
  }
});

const defaultThumb = '/liroam/goods-placeholder.svg';
const showModal = ref(false);
const openGoodsPicker = inject('openGoodsPicker', null);
const tempSelectedGoods = ref([]);

if (!props.options.title) props.options.title = '猜你喜欢';
if (!props.options.subtitle) props.options.subtitle = '真实推荐商品流';
if (!props.options.layoutStyle) props.options.layoutStyle = 'featured';
if (!props.options.backgroundColor) props.options.backgroundColor = '#f8fafc';
if (!props.options.pageSize) props.options.pageSize = 8;
if (!props.options.maxPages) props.options.maxPages = 3;
if (!props.options.goods) {
  props.options.goods = copyOf(MOCK_GOODS.slice(0, 8));
}

function handleOpenGoodsPicker() {
  if (typeof openGoodsPicker === 'function') {
    openGoodsPicker((selectedGoods) => {
      applySelectedGoods(selectedGoods);
    }, props.options.goods || []);
  } else {
    tempSelectedGoods.value = Array.isArray(props.options.goods) ? [...props.options.goods] : [];
    showModal.value = true;
  }
}

function confirmGoodsSelected() {
  applySelectedGoods(tempSelectedGoods.value);
  showModal.value = false;
}

function applySelectedGoods(selectedGoods) {
  if (Array.isArray(selectedGoods)) {
    props.options.goods = selectedGoods.map(g => ({
      id: g.id || g.skuId || g.goodsId,
      goodsId: g.goodsId,
      goodsName: g.goodsName || g.name,
      price: g.price,
      originalPrice: g.originalPrice || g.price,
      thumbnail: g.thumbnail || g.pic || defaultThumb
    }));
  }
}
</script>

<style lang="scss" scoped>
.panel-recommend-feed {
  padding: 8px 4px;

  .panel-section {
    margin-bottom: 18px;
    padding-bottom: 14px;
    border-bottom: 1px dashed #e2e8f0;

    .section-title {
      font-size: 13px;
      font-weight: 600;
      color: #0f172a;
      margin-bottom: 10px;

      &.flex-between {
        display: flex;
        justify-content: space-between;
        align-items: center;
      }

      .btn-group {
        display: flex;
        align-items: center;
        gap: 8px;
      }
    }

    .form-item {
      display: flex;
      align-items: center;
      justify-content: space-between;
      margin-bottom: 8px;

      .item-label {
        font-size: 12px;
        color: #475569;
        margin-right: 8px;
        white-space: nowrap;
      }
    }
  }

  .goods-list-preview {
    max-height: 260px;
    overflow-y: auto;
    display: flex;
    flex-direction: column;
    gap: 6px;
    margin-top: 8px;

    .goods-item-row {
      display: flex;
      align-items: center;
      gap: 8px;
      background: #f8fafc;
      padding: 6px 8px;
      border-radius: 6px;
      border: 1px solid #f1f5f9;

      .idx-tag {
        font-size: 11px;
        font-weight: 700;
        color: #64748b;
        background: #e2e8f0;
        padding: 1px 5px;
        border-radius: 3px;
        white-space: nowrap;
      }

      .g-thumb {
        width: 36px;
        height: 36px;
        border-radius: 4px;
        object-fit: cover;
      }

      .g-info {
        flex: 1;
        overflow: hidden;
        display: flex;
        flex-direction: column;

        .g-name {
          font-size: 12px;
          color: #1e293b;
          white-space: nowrap;
          overflow: hidden;
          text-overflow: ellipsis;
        }

        .g-price {
          font-size: 11px;
          font-weight: 700;
          color: #c8461f;
        }
      }
    }
  }

  .goods-empty-tip {
    padding: 16px;
    text-align: center;
    background: #f8fafc;
    border-radius: 6px;
    font-size: 12px;
    color: #94a3b8;
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 6px;
  }
}
</style>
