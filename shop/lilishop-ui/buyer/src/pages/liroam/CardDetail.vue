<template>
  <div class="wrapper liroam-card-detail">
    <UserCenterLayout>
      <div class="detail-heading">
        <el-button link @click="router.push({ name: 'LiroamMyCards' })">‹ 返回我的卡</el-button>
      </div>
      <el-skeleton v-if="loading && !card" :rows="6" animated />
      <div v-else-if="!card" class="detail-error"><p>{{ errorText || "卡片不存在" }}</p><el-button @click="load">重新加载</el-button></div>
      <template v-else>
        <div class="detail-summary">
          <div class="detail-title">
            <span class="detail-name">{{ card.productName || "旅行卡" }}</span>
            <span class="detail-status">{{ card.statusText || card.status }}</span>
            <span v-if="card.refundHold" class="detail-hold">售后处理中</span>
          </div>
          <el-descriptions :column="2" class="detail-fields">
            <el-descriptions-item label="使用区域">{{ card.usageRegionName || "—" }}</el-descriptions-item>
            <el-descriptions-item label="形态">{{ cardFormText(card) || "—" }}</el-descriptions-item>
            <el-descriptions-item v-if="card.capabilityText" label="能力">{{ card.capabilityText }}</el-descriptions-item>
            <el-descriptions-item v-for="row in quotaRows(card)" :key="row.label" :label="row.label">{{ row.value }}</el-descriptions-item>
            <el-descriptions-item label="到期">{{ card.expiresText || "—" }}</el-descriptions-item>
            <el-descriptions-item label="卡号尾号">{{ card.iccidLast4 || "—" }}</el-descriptions-item>
            <el-descriptions-item label="更新于">{{ card.updatedText || "—" }}</el-descriptions-item>
          </el-descriptions>
          <div v-if="kyc" :class="['detail-kyc', `detail-kyc--${kyc.type}`]">
            <span>{{ kyc.text }}</span>
            <el-button v-if="kyc.action" type="primary" size="small" :loading="declaring" @click="declareKyc">我已完成实名</el-button>
          </div>
          <div class="detail-actions">
            <el-button v-if="!card.kycBlocking" type="primary" plain @click="credentialVisible = true">{{ credentialLabel(card) }}</el-button>
            <el-button v-if="card.canTopUp" type="primary" @click="topUpVisible = true">加包</el-button>
            <el-button v-if="card.canRefund" @click="refundVisible = true">申请退款</el-button>
            <el-button :loading="loading" @click="load">刷新</el-button>
          </div>
        </div>

        <div class="detail-section-title">套餐</div>
        <el-table :data="plans" border empty-text="暂无套餐">
          <el-table-column prop="name" label="套餐" min-width="180" show-overflow-tooltip />
          <el-table-column label="状态" width="120">
            <template #default="{ row }">{{ row.statusText || row.status }}</template>
          </el-table-column>
          <el-table-column label="流量剩余" min-width="120">
            <template #default="{ row }">{{ row.remainingText || "—" }}</template>
          </el-table-column>
          <el-table-column v-if="card.voiceRemainingText" label="语音" min-width="140">
            <template #default="{ row }">{{ row.voiceUsageText || row.voiceRemainingText || "—" }}</template>
          </el-table-column>
          <el-table-column v-if="card.smsRemainingText" label="短信" min-width="130">
            <template #default="{ row }">{{ row.smsUsageText || row.smsRemainingText || "—" }}</template>
          </el-table-column>
          <el-table-column label="到期" min-width="160">
            <template #default="{ row }">{{ row.expiresText || "—" }}</template>
          </el-table-column>
        </el-table>
      </template>
    </UserCenterLayout>

    <template v-if="card">
      <CredentialDialog v-model="credentialVisible" :card-id="card.id" :physical="card.form === 'PHYSICAL'" :guide="card.formGuide || ''" />
      <TopUpDialog v-model="topUpVisible" :card="card" />
      <RefundDialog v-model="refundVisible" :card="card" @done="load" />
    </template>
  </div>
</template>

<script setup>
import { computed, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElDescriptions, ElDescriptionsItem } from "element-plus";
import "element-plus/es/components/descriptions/style/css";
import "element-plus/es/components/descriptions-item/style/css";
import { Message } from "@/utils/message";
import { declareCardKyc, getMyCard } from "@/api/liroam";
import { cardFormText, credentialLabel, failureMessage, kycNotice, listOf, quotaRows } from "@/utils/liroam";
import CredentialDialog from "./components/CredentialDialog.vue";
import TopUpDialog from "./components/TopUpDialog.vue";
import RefundDialog from "./components/RefundDialog.vue";

defineOptions({ name: "LiroamCardDetail" });

const route = useRoute();
const router = useRouter();
const card = ref(null);
const loading = ref(false);
const errorText = ref("");
const credentialVisible = ref(false);
const topUpVisible = ref(false);
const refundVisible = ref(false);
const plans = computed(() => listOf(card.value?.plans));
const kyc = computed(() => kycNotice(card.value));
const declaring = ref(false);

async function declareKyc() {
  declaring.value = true;
  try {
    const res = await declareCardKyc(card.value.id);
    if (res && res.success) {
      Message.success("已提交实名");
      if (res.result) card.value = res.result;
      load();
    } else {
      Message.error(failureMessage(res, "提交失败，请稍后重试"));
    }
  } catch {
    Message.error("提交失败，请稍后重试");
  } finally {
    declaring.value = false;
  }
}

let requestId = 0;
async function load() {
  const id = route.query.id;
  if (!id) {
    errorText.value = "缺少卡片编号";
    return;
  }
  const current = ++requestId;
  loading.value = true;
  errorText.value = "";
  try {
    const res = await getMyCard(id);
    if (current !== requestId) return;
    if (res && res.success && res.result) {
      card.value = res.result;
    } else {
      card.value = null;
      errorText.value = failureMessage(res, "卡片加载失败");
    }
  } catch {
    if (current === requestId) errorText.value = "卡片加载失败，请稍后重试";
  } finally {
    if (current === requestId) loading.value = false;
  }
}

watch(() => route.query.id, (id) => { if (id) { card.value = null; load(); } }, { immediate: true });
</script>

<style scoped lang="scss">
.liroam-card-detail {
  .detail-heading { padding: 4px 0 12px; }
  .detail-error { padding: 64px 0; text-align: center; color: #909399; }
  .detail-summary { padding: 18px 20px; border: 1px solid #ebeef5; border-radius: 8px; background: #fff; }
  .detail-title { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin-bottom: 14px; }
  .detail-name { font-size: 18px; font-weight: 600; color: #252833; }
  .detail-status { padding: 1px 7px; border-radius: 4px; background: #e8f1ff; color: #0b7a75; font-size: 12px; }
  .detail-hold { padding: 1px 7px; border-radius: 4px; background: #fdf6ec; color: #e6a23c; font-size: 12px; }
  .detail-actions { margin-top: 8px; }
  .detail-kyc { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin: 8px 0; padding: 10px 14px; border-radius: 6px; font-size: 13px; }
  .detail-kyc--warning { background: #fdf6ec; color: #b88230; }
  .detail-kyc--info { background: #f4f4f5; color: #606266; }
  .detail-kyc--error { background: #fef0f0; color: #c45656; }
  .detail-section-title { margin: 24px 0 12px; font-size: 15px; font-weight: 600; color: #303133; }
}
</style>
