<template>
  <div class="search">
    <el-card>
      <el-form inline label-width="70px" class="search-form">
        <el-form-item label="日期">
          <el-date-picker v-model="range" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始" end-placeholder="结束" />
        </el-form-item>
        <el-form-item><el-button type="primary" class="search-btn" @click="load">查询</el-button></el-form-item>
      </el-form>
    </el-card>

    <el-card v-loading="loading">
      <div v-if="overview" class="liroam-figures">
        <div v-for="f in FIGURES" :key="f.key" class="liroam-figure">
          <div class="liroam-figure-label">{{ f.label }}</div>
          <div class="liroam-figure-value">{{ f.money ? `¥${fenToYuan(overview.totals[f.key])}` : overview.totals[f.key] }}</div>
        </div>
      </div>
      <el-table border :data="overview ? overview.days : []" style="width: 100%" class="mt_10">
        <el-table-column prop="day" label="日期" width="120" />
        <el-table-column v-for="f in FIGURES" :key="f.key" :label="f.label" min-width="100" align="right">
          <template #default="{ row }">{{ f.money ? fenToYuan(row[f.key]) : row[f.key] }}</template>
        </el-table-column>
      </el-table>
      <div class="liroam-hint mt_10">
        按北京时间自然日、下单日归集。成本 = 下单时快照的人民币进价；毛利 = 商城实付 + 渠道实付 − 退款 − 成本 − 佣金（未扣支付手续费与税）。
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, ref } from "vue";
import { getOpsOverview } from "@/api/liroam";
import { fenToYuan } from "@/utils/liroam";

defineOptions({ name: "liroam-ops-reports" });

const FIGURES = [
  { key: "lines", label: "订单行" },
  { key: "shopPaidFen", label: "商城实付", money: true },
  { key: "channelPaidFen", label: "渠道实付", money: true },
  { key: "cardsDelivered", label: "交付卡数" },
  { key: "refundFen", label: "退款", money: true },
  { key: "costFen", label: "成本", money: true },
  { key: "commissionFen", label: "佣金", money: true },
  { key: "grossFen", label: "毛利", money: true },
];

function ymd(d) {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

const today = new Date();
const range = ref([ymd(new Date(today.getTime() - 6 * 86400000)), ymd(today)]);
const loading = ref(false);
const overview = ref(null);

async function load() {
  if (!range.value || range.value.length !== 2) return;
  loading.value = true;
  try {
    const res = await getOpsOverview({ from: range.value[0], to: range.value[1] });
    if (res && res.success) overview.value = res.result;
  } catch {
    // 请求层已提示错误
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>

<style scoped>
.liroam-figures {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}
.liroam-figure {
  min-width: 120px;
  padding: 10px 14px;
  border: 1px solid #ebeef5;
  border-radius: 4px;
}
.liroam-figure-label {
  color: #909399;
  font-size: 12px;
}
.liroam-figure-value {
  margin-top: 4px;
  font-size: 18px;
  font-weight: 600;
}
</style>
