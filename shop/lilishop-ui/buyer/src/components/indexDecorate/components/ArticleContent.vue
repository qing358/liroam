<template>
  <div class="decor-article-content" :style="customStyle">
    <!-- 文章顶部标题区 -->
    <div class="article-header" v-if="options.showTitle !== false && options.title">
      <h2 class="article-main-title">{{ options.title }}</h2>
      <p class="article-sub-title" v-if="options.subtitle">{{ options.subtitle }}</p>

      <div class="article-meta-bar" v-if="options.showMeta !== false">
        <span class="meta-tag">{{ options.tag || '品牌介绍' }}</span>
        <span class="meta-item author" v-if="options.author">作者：{{ options.author }}</span>
        <span class="meta-item date" v-if="options.date">发布于 {{ options.date }}</span>
        <span class="meta-item views">阅读 2.4k+</span>
      </div>
    </div>

    <!-- 文章正文排版区 (Rich Text Article Body) -->
    <div
      class="article-body-typography"
      :class="[`align-${options.textAlign || 'left'}`]"
      :style="{ fontSize: `${options.fontSize || 15}px` }"
      v-html="renderedContent"
    ></div>
  </div>
</template>

<script setup>
import { computed } from 'vue';

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

const defaultArticleHtml = `
<h3>关于轻游 Liroam</h3>
<p>轻游是想象智能（济南）有限公司运营的出境旅行通信卡平台。按目的地和行程天数选购 eSIM 与实体卡，eSIM 付款后在线获取激活码，实体卡发货后可查物流。</p>
<blockquote>一人可以持有多张卡，每张卡单独开卡、查看用量、续费和加包。</blockquote>
<h4>在轻游可以办理</h4>
<ul>
  <li><strong>按目的地选购</strong>：套餐标明使用区域、天数和流量，下单前即可核对。</li>
  <li><strong>在线开卡</strong>：eSIM 付款后发放激活码，实体卡发货后可查物流。</li>
  <li><strong>按卡管理</strong>：在「我的卡」查看开卡进度、已用流量和到期时间。</li>
  <li><strong>续费与加包</strong>：流量不够或行程延长，直接为原卡续费或加包。</li>
</ul>
<p>在京东、小红书等渠道购买的轻游旅行卡，可凭订单号和下单手机号认领到「我的卡」统一管理。</p>
`.trim();

const renderedContent = computed(() => {
  return props.options?.content || defaultArticleHtml;
});

const customStyle = computed(() => {
  const s = props.componentStyle || {};
  return {
    backgroundColor: s.backgroundColor || '#ffffff',
    padding: Array.isArray(s.padding)
      ? `${s.padding[0]}px ${s.padding[1]}px ${s.padding[2]}px ${s.padding[3]}px`
      : '24px 28px',
    borderRadius: Array.isArray(s.radius)
      ? `${s.radius[0]}px ${s.radius[1]}px ${s.radius[2]}px ${s.radius[3]}px`
      : '12px'
  };
});
</script>

<style lang="scss" scoped>
.decor-article-content {
  width: 100%;
  box-sizing: border-box;

  .article-header {
    padding-bottom: 16px;
    margin-bottom: 18px;
    border-bottom: 1px solid #f1f5f9;

    .article-main-title {
      font-size: 24px;
      font-weight: 800;
      color: #0f172a;
      line-height: 1.35;
      margin: 0 0 8px 0;
      letter-spacing: -0.3px;
    }

    .article-sub-title {
      font-size: 14px;
      color: #64748b;
      line-height: 1.5;
      margin: 0 0 12px 0;
    }

    .article-meta-bar {
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      gap: 12px;
      font-size: 12px;
      color: #94a3b8;

      .meta-tag {
        font-size: 11px;
        font-weight: 600;
        background: #eff6ff;
        color: #2563eb;
        padding: 2px 8px;
        border-radius: 4px;
      }

      .meta-item {
        display: inline-flex;
        align-items: center;
      }
    }
  }

  .article-body-typography {
    color: #334155;
    line-height: 1.8;
    letter-spacing: 0.2px;

    &.align-center {
      text-align: center;
    }
    &.align-justify {
      text-align: justify;
    }

    :deep(h2), :deep(h3), :deep(h4) {
      color: #0f172a;
      font-weight: 700;
      line-height: 1.4;
      margin-top: 20px;
      margin-bottom: 10px;
    }

    :deep(h2) {
      font-size: 20px;
      border-left: 4px solid #2563eb;
      padding-left: 10px;
    }

    :deep(h3) {
      font-size: 18px;
      color: #1e293b;
    }

    :deep(h4) {
      font-size: 16px;
    }

    :deep(p) {
      margin: 0 0 14px 0;
      line-height: 1.8;
    }

    :deep(blockquote) {
      margin: 16px 0;
      padding: 12px 18px;
      background: #f8fafc;
      border-left: 4px solid #3b82f6;
      border-radius: 0 8px 8px 0;
      color: #475569;
      font-style: italic;
      line-height: 1.7;
    }

    :deep(ul), :deep(ol) {
      margin: 12px 0 16px 20px;
      padding: 0;

      li {
        margin-bottom: 6px;
        line-height: 1.7;
      }
    }

    :deep(img) {
      max-width: 100%;
      height: auto;
      border-radius: 8px;
      display: block;
      margin: 16px auto;
      box-shadow: 0 4px 14px rgba(0, 0, 0, 0.06);
    }

    :deep(strong) {
      color: #0f172a;
      font-weight: 700;
    }
  }
}
</style>
