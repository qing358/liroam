<template>
  <div class="search">
    <el-card>
      <el-form inline label-width="70px" class="search-form">
        <el-form-item label="状态">
          <el-select v-model="status" placeholder="全部" clearable style="width: 160px" @change="reload">
            <el-option v-for="item in REFUND_STATUSES" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" class="search-btn" @click="reload">刷新</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card>
      <el-table v-loading="loading" border :data="data" style="width: 100%">
        <el-table-column prop="id" label="编号" width="90" />
        <el-table-column label="卡号" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ pick(row, "cardNo", "cardAssetId") }}</template>
        </el-table-column>
        <el-table-column label="对象" width="80">
          <template #default="{ row }">{{ pick(row, "targetTypeText", "targetType") }}</template>
        </el-table-column>
        <el-table-column label="退款时卡状态" width="120">
          <template #default="{ row }">{{ pick(row, "cardStateText", "cardStateAtApply") }}</template>
        </el-table-column>
        <el-table-column label="规则结论" width="120">
          <template #default="{ row }">{{ pick(row, "decisionText", "decision") }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">{{ pick(row, "statusText", "status") }}</template>
        </el-table-column>
        <el-table-column label="金额（元）" width="110" align="right">
          <template #default="{ row }">{{ fenToYuan(pick(row, "amountFen", "refundFen")) }}</template>
        </el-table-column>
        <el-table-column label="原因" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ pick(row, "reason") }}</template>
        </el-table-column>
        <el-table-column label="订单" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ pick(row, "orderSn") }}</template>
        </el-table-column>
        <el-table-column label="申请时间" min-width="140">
          <template #default="{ row }">{{ pick(row, "createdText", "createdAt") }}</template>
        </el-table-column>
        <el-table-column label="操作" width="190" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" @click="act(row, 'approve')">批准</el-button>
            <el-button link type="danger" @click="act(row, 'reject')">驳回</el-button>
            <el-button link type="primary" @click="act(row, 'complete')">退款完成</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination v-model:current-page="page" :page-size="20" :total="total" layout="total, prev, pager, next" class="mt_10" @current-change="load" />
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { actOnRefund, listRefunds } from "@/api/liroam";
import { fenToYuan, pageOf, pick } from "@/utils/liroam";

defineOptions({ name: "liroam-refund-cases" });

const REFUND_STATUSES = [
  { value: "HELD", label: "待审核" },
  { value: "APPROVED", label: "已批准" },
  { value: "EXECUTING", label: "执行中" },
  { value: "DONE", label: "已完成" },
  { value: "REJECTED", label: "已驳回" },
  { value: "RELEASED", label: "已撤销" },
];
const ACTIONS = {
  approve: { label: "批准", tip: "批准后按退款规则下发卡动作（停用/终止/取消）。" },
  reject: { label: "驳回", tip: "驳回后解除这张卡的售后冻结。" },
  complete: { label: "标记退款完成", tip: "确认款项已退回买家；卡进入终态，池卡按规则归还或作废。" },
};

const status = ref("");
const loading = ref(false);
const data = ref([]);
const page = ref(1);
const total = ref(0);

async function load() {
  loading.value = true;
  try {
    const res = await listRefunds({ status: status.value, pageNumber: page.value, pageSize: 20 });
    if (res && res.success) {
      const { records, total: count } = pageOf(res.result);
      data.value = records;
      total.value = count;
    }
  } catch {
    // 请求层已提示错误
  } finally {
    loading.value = false;
  }
}

function reload() {
  page.value = 1;
  load();
}

async function act(row, action) {
  const { label, tip } = ACTIONS[action];
  let note = "";
  try {
    const answer = await ElMessageBox.prompt(tip, `${label}退款 #${row.id}`, {
      inputPlaceholder: "备注（可选）",
      confirmButtonText: label,
      cancelButtonText: "取消",
    });
    note = (answer.value || "").trim();
  } catch {
    return;
  }
  try {
    const res = await actOnRefund(row.id, action, note);
    if (res && res.success) {
      ElMessage.success(`${label}成功`);
      load();
    }
  } catch {
    // 请求层已提示错误
  }
}

onMounted(load);
</script>
