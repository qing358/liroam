<template>
  <div class="wrapper liroam-my-cards">
    <UserCenterLayout>
      <div class="cards-heading">
        <div><h2>我的卡</h2><p>每张旅行卡的剩余额度、到期时间与激活码 / 物流</p></div>
        <div class="cards-heading__actions">
          <el-button @click="claimVisible = true">认领渠道订单</el-button>
          <el-button :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>

      <el-alert v-if="notices.length" type="warning" show-icon class="cards-notice" close-text="知道了" @close="dismissNotices">
        <template #title>
          <div v-for="n in notices" :key="n.id">{{ n.text }}</div>
        </template>
      </el-alert>

      <el-skeleton v-if="loading && !cards.length" :rows="5" animated />
      <div v-else-if="loadFailed" class="cards-error"><p>卡片加载失败，请稍后重试</p><el-button @click="load">重新加载</el-button></div>
      <el-empty v-else-if="!cards.length" description="还没有旅行卡，去挑一张出发吧" :image-size="100">
        <el-button type="primary" @click="router.push('/goodsList')">去逛逛</el-button>
      </el-empty>
      <div v-else class="cards-grid">
        <div v-for="card in cards" :key="card.id" class="travel-card" :class="{ 'is-hold': card.refundHold }">
          <div class="travel-card__head">
            <div class="travel-card__title">
              <span class="travel-card__name">{{ card.productName || "旅行卡" }}</span>
              <span class="travel-card__status">{{ card.statusText || card.status }}</span>
              <span v-if="card.refundHold" class="travel-card__hold">售后处理中</span>
            </div>
            <div class="travel-card__meta">
              <span v-if="card.usageRegionName">{{ card.usageRegionName }}</span>
              <span v-if="cardFormText(card)">{{ cardFormText(card) }}</span>
              <span v-if="card.iccidLast4">尾号 {{ card.iccidLast4 }}</span>
            </div>
          </div>
          <div class="travel-card__body">
            <div v-for="(row, i) in quotaRows(card)" :key="row.label" class="travel-card__row">
              <label>{{ row.label }}</label><span :class="{ 'travel-card__strong': i === 0 }">{{ row.value }}</span>
            </div>
            <div class="travel-card__row"><label>到期</label><span>{{ card.expiresText || "—" }}</span></div>
            <div v-if="card.updatedText" class="travel-card__updated">更新于 {{ card.updatedText }}</div>
          </div>
          <div class="travel-card__actions">
            <el-button size="small" @click="openDetail(card)">详情</el-button>
            <el-button v-if="card.canTopUp" size="small" type="primary" @click="openTopUp(card)">加包</el-button>
            <el-button v-if="card.canRefund" size="small" @click="openRefund(card)">申请退款</el-button>
          </div>
        </div>
      </div>
    </UserCenterLayout>

    <TopUpDialog v-model="topUpVisible" :card="activeCard" />
    <RefundDialog v-model="refundVisible" :card="activeCard" @done="load" />
    <ClaimChannelOrderDialog v-model="claimVisible" @done="load" />
  </div>
</template>

<script setup>
import { onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { Message } from "@/utils/message";
import { listMyCards, listMyNotifications, markNotificationsRead } from "@/api/liroam";
import { cardFormText, expiryNotices, failureMessage, listOf, quotaRows } from "@/utils/liroam";
import TopUpDialog from "./components/TopUpDialog.vue";
import RefundDialog from "./components/RefundDialog.vue";
import ClaimChannelOrderDialog from "./components/ClaimChannelOrderDialog.vue";

defineOptions({ name: "LiroamMyCards" });

const router = useRouter();
const cards = ref([]);
const loading = ref(false);
const loadFailed = ref(false);
const activeCard = ref(null);
const topUpVisible = ref(false);
const refundVisible = ref(false);
const notices = ref([]);
const claimVisible = ref(false);

async function load() {
  loading.value = true;
  loadFailed.value = false;
  try {
    const res = await listMyCards();
    if (res && res.success) {
      cards.value = listOf(res.result);
    } else {
      loadFailed.value = true;
      Message.warning(failureMessage(res, "卡片加载失败"));
    }
  } catch {
    loadFailed.value = true;
  } finally {
    loading.value = false;
  }
}

async function loadNotices() {
  try {
    const res = await listMyNotifications();
    if (res && res.success) notices.value = expiryNotices(res.result);
  } catch {
    // 提醒拉不到不影响卡列表
  }
}

async function dismissNotices() {
  notices.value = [];
  try {
    await markNotificationsRead();
  } catch {
    // 下次进入仍会显示
  }
}

function openDetail(card) {
  router.push({ name: "LiroamCardDetail", query: { id: card.id } });
}

function openTopUp(card) {
  activeCard.value = card;
  topUpVisible.value = true;
}

function openRefund(card) {
  activeCard.value = card;
  refundVisible.value = true;
}

onMounted(() => {
  load();
  loadNotices();
});
</script>

<style scoped lang="scss">
.liroam-my-cards {
  .cards-heading {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    padding: 10px 4px 22px;
    h2 { margin: 0 0 8px; font-size: 22px; font-weight: 600; color: #252833; }
    p { margin: 0; font-size: 13px; color: #909399; }
    &__actions { display: flex; gap: 8px; }
  }
  .cards-notice { margin-bottom: 16px; }
  .cards-error { padding: 64px 0; text-align: center; color: #909399; }
  .cards-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
}

.travel-card {
  display: flex;
  flex-direction: column;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  overflow: hidden;
  background: #fff;
  &.is-hold { border-color: #f3d19e; }
  &__head { padding: 16px 18px 12px; background: linear-gradient(120deg, #f2f7ff, #f9fbff); }
  &__title { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
  &__name { font-size: 15px; font-weight: 600; color: #252833; }
  &__status { padding: 1px 7px; border-radius: 4px; background: #e8f1ff; color: #0b7a75; font-size: 12px; }
  &__hold { padding: 1px 7px; border-radius: 4px; background: #fdf6ec; color: #e6a23c; font-size: 12px; }
  &__meta { display: flex; flex-wrap: wrap; gap: 12px; margin-top: 8px; font-size: 12px; color: #7a8294; }
  &__body { flex: 1; padding: 14px 18px 4px; }
  &__row {
    display: flex;
    gap: 12px;
    line-height: 26px;
    font-size: 13px;
    color: #606266;
    label { flex: none; width: 56px; white-space: nowrap; color: #909399; }
  }
  &__strong { font-weight: 600; color: #252833; }
  &__updated { margin-top: 6px; font-size: 12px; color: #b0b5bf; }
  &__actions { display: flex; justify-content: flex-end; padding: 12px 18px 16px; }
}
</style>
