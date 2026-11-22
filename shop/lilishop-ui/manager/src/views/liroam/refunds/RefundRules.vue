<template>
  <div class="search">
    <el-card>
      <div class="rules-toolbar">
        <div class="rules-tip">退款申请时按「退款时卡状态 + 对象」匹配一条规则；每次保存生成新版本并记审计。没有匹配规则时一律需人工审核。</div>
        <div>
          <el-button @click="addRow">新增规则</el-button>
          <el-button type="primary" @click="load">刷新</el-button>
        </div>
      </div>
      <el-table v-loading="loading" border :data="rows" style="width: 100%">
        <el-table-column label="退款时卡状态" min-width="160">
          <template #default="{ row }">
            <el-select v-model="row.cardState" filterable allow-create placeholder="状态" @change="row._dirty = true">
              <el-option v-for="item in STATE_OPTIONS" :key="item.value" :label="`${item.label}（${item.value}）`" :value="item.value" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="对象" width="120">
          <template #default="{ row }">
            <el-select v-model="row.targetType" placeholder="对象" @change="row._dirty = true">
              <el-option label="卡" value="CARD" />
              <el-option label="套餐" value="PLAN" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="结论" min-width="150">
          <template #default="{ row }">
            <el-select v-model="row.decision" placeholder="结论" @change="row._dirty = true">
              <el-option label="自动通过" value="AUTO_APPROVE" />
              <el-option label="需人工审核" value="NEED_APPROVAL" />
              <el-option label="默认拒绝" value="DEFAULT_REJECT" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="卡动作" min-width="170">
          <template #default="{ row }">
            <el-select v-model="row.cardAction" filterable allow-create clearable placeholder="无" @change="row._dirty = true">
              <el-option v-for="item in CARD_ACTIONS" :key="item.value" :label="`${item.label}（${item.value}）`" :value="item.value" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="池动作" width="140">
          <template #default="{ row }">
            <el-select v-model="row.poolAction" clearable placeholder="无" @change="row._dirty = true">
              <el-option label="归还卡池" value="RETURN" />
              <el-option label="作废" value="VOID" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="冲正佣金" width="90" align="center">
          <template #default="{ row }">
            <el-switch v-model="row.reverseCommission" @change="row._dirty = true" />
          </template>
        </el-table-column>
        <el-table-column label="版本" width="70" align="center">
          <template #default="{ row }">{{ row.version ?? "新" }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" :disabled="!row._dirty" :loading="row._saving" @click="save(row)">保存</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, ref } from "vue";
import { ElMessage } from "element-plus";
import { listRefundRules, saveRefundRule } from "@/api/liroam";
import { CARD_STATUSES, listOf } from "@/utils/liroam";

defineOptions({ name: "liroam-refund-rules" });

/** 卡状态（§7.3）+ 套餐状态（§7.4），可手输后端新增的状态。 */
const STATE_OPTIONS = [
  ...CARD_STATUSES,
  { value: "PENDING", label: "套餐待订购" },
  { value: "PROVISIONING", label: "套餐订购中" },
  { value: "READY", label: "套餐未开始" },
  { value: "DEPLETED", label: "套餐已用尽" },
];
const CARD_ACTIONS = [
  { value: "CANCEL_COMMANDS", label: "取消未执行指令" },
  { value: "TERMINATE", label: "终止" },
  { value: "SUSPEND_THEN_TERMINATE", label: "停用后终止" },
  { value: "REVOKE_PLAN", label: "撤销套餐" },
];

const loading = ref(false);
const rows = ref([]);

function toRow(rule) {
  return {
    ...rule,
    cardState: rule.cardState ?? "",
    targetType: rule.targetType ?? "CARD",
    decision: rule.decision ?? "NEED_APPROVAL",
    cardAction: rule.cardAction ?? "",
    poolAction: rule.poolAction ?? "",
    reverseCommission: Boolean(rule.reverseCommission),
    _dirty: false,
    _saving: false,
  };
}

function toPayload(row) {
  const payload = {};
  Object.keys(row).forEach((key) => {
    if (!key.startsWith("_")) payload[key] = row[key];
  });
  payload.cardAction = payload.cardAction || null;
  payload.poolAction = payload.poolAction || null;
  return payload;
}

async function load() {
  loading.value = true;
  try {
    const res = await listRefundRules();
    rows.value = listOf(res && res.success ? res.result : null).map(toRow);
  } catch {
    // 请求层已提示错误
  } finally {
    loading.value = false;
  }
}

function addRow() {
  rows.value.unshift({ ...toRow({}), _dirty: true });
}

async function save(row) {
  if (!row.cardState || !row.targetType || !row.decision) {
    ElMessage.warning("卡状态、对象、结论不能为空");
    return;
  }
  row._saving = true;
  try {
    const res = await saveRefundRule(toPayload(row));
    if (res && res.success) {
      ElMessage.success("已保存");
      if (res.result && typeof res.result === "object") {
        Object.assign(row, toRow(res.result));
      } else {
        load();
      }
    }
  } catch {
    // 请求层已提示错误
  } finally {
    row._saving = false;
  }
}

onMounted(load);
</script>

<style scoped>
.rules-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 12px; }
.rules-tip { font-size: 12px; color: #909399; }
</style>
