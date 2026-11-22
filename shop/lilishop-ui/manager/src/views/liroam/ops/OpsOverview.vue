<template>
  <div class="search">
    <el-card>
      <div class="mb_10 ops-head">
        <el-button type="primary" :loading="loading" @click="load">刷新</el-button>
        <span class="liroam-hint">容量阈值取设置项 alert.db_capacity_mb；超过 80% 进「运营告警」。处置见仓库 docs/ops-runbook.md。</span>
      </div>

      <el-descriptions title="数据库容量" :column="1" border class="mb_10">
        <el-descriptions-item label="占用">
          <div class="capacity">
            <el-progress :percentage="usage.percent" :status="usage.status" :stroke-width="14" style="width: 320px" />
            <span>{{ bytesText(overview.databaseBytes) }} / {{ bytesText(overview.capacityLimitBytes) }}</span>
          </div>
        </el-descriptions-item>
      </el-descriptions>

      <div class="section-title">积压</div>
      <el-table v-loading="loading" border :data="overview.backlogs" class="mb_10" style="width: 100%">
        <el-table-column prop="label" label="类别" min-width="200" />
        <el-table-column label="数量" width="120" align="center">
          <template #default="{ row }">
            <el-tag :type="row.count > 0 ? 'warning' : 'success'" size="small">{{ row.count }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="key" label="编码" width="220" />
      </el-table>

      <div class="section-title">最大的表（前 {{ overview.tables.length }}）</div>
      <el-table v-loading="loading" border :data="overview.tables" class="mb_10" style="width: 100%">
        <el-table-column prop="table" label="表" min-width="220" />
        <el-table-column prop="rows" label="行数（估）" width="140" align="right" />
        <el-table-column label="大小" width="140" align="right">
          <template #default="{ row }">{{ bytesText(row.bytes) }}</template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card class="mt_10">
      <div class="mb_10 ops-head">
        <span class="section-title inline">数据密钥</span>
        <span class="liroam-hint">当前版本 V{{ keys.currentVersion || "-" }}，已装载 {{ versionsText }}；待重加密 {{ keys.staleRows || 0 }} 行，无法解密 {{ keys.unreadableRows || 0 }} 行。</span>
        <el-input-number v-model="batchSize" :min="1" :max="1000" size="small" class="ml_10" />
        <el-button type="warning" size="small" class="ml_10" :disabled="!keys.staleRows" :loading="resealing" @click="reseal">重加密一批</el-button>
      </div>
      <el-table v-loading="loading" border :data="keys.tables" style="width: 100%">
        <el-table-column prop="table" label="表" min-width="200" />
        <el-table-column label="各版本行数" min-width="260">
          <template #default="{ row }">
            <el-tag v-for="v in row.versions" :key="v.keyVersion" :type="v.available ? (v.keyVersion === keys.currentVersion ? 'success' : 'warning') : 'danger'"
              size="small" class="mr_5">V{{ v.keyVersion }}：{{ v.rows }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="staleRows" label="待重加密" width="110" align="center" />
        <el-table-column prop="unreadableRows" label="无法解密" width="110" align="center" />
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { getDataKeyStatus, getOpsHealth, resealDataKeys } from "@/api/liroam";
import { bytesText, capacityUsage, listOf } from "@/utils/liroam";

defineOptions({ name: "liroam-ops-overview" });

const loading = ref(false);
const resealing = ref(false);
const batchSize = ref(200);
const overview = reactive({ databaseBytes: null, capacityLimitBytes: null, tables: [], backlogs: [] });
const keys = reactive({ currentVersion: null, availableVersions: [], tables: [], staleRows: 0, unreadableRows: 0 });

const usage = computed(() => capacityUsage(overview.databaseBytes || 0, overview.capacityLimitBytes || 0));
const versionsText = computed(() => listOf(keys.availableVersions).map((v) => `V${v}`).join("、") || "-");

async function load() {
  loading.value = true;
  try {
    const [o, k] = await Promise.all([getOpsHealth(), getDataKeyStatus()]);
    if (o && o.success) Object.assign(overview, o.result, { tables: listOf(o.result.tables), backlogs: listOf(o.result.backlogs) });
    if (k && k.success) Object.assign(keys, k.result, { tables: listOf(k.result.tables) });
  } catch {
    // 请求层已提示错误
  } finally {
    loading.value = false;
  }
}

async function reseal() {
  try {
    await ElMessageBox.confirm(`用当前密钥 V${keys.currentVersion} 重加密至多 ${batchSize.value} 行旧版本数据？`, "重加密", { type: "warning" });
  } catch {
    return;
  }
  resealing.value = true;
  try {
    const res = await resealDataKeys(batchSize.value);
    if (res && res.success) {
      ElMessage.success(`已重加密 ${res.result.resealed} 行，剩余 ${res.result.remaining} 行`);
      await load();
    }
  } catch {
    // 请求层已提示错误
  } finally {
    resealing.value = false;
  }
}

onMounted(load);
</script>

<style scoped>
.ops-head { display: flex; align-items: center; flex-wrap: wrap; }
.liroam-hint { margin-left: 10px; font-size: 12px; color: #909399; }
.capacity { display: flex; align-items: center; gap: 12px; }
.section-title { font-weight: 600; margin: 6px 0; }
.section-title.inline { margin: 0; }
.mt_10 { margin-top: 10px; }
.ml_10 { margin-left: 10px; }
.mr_5 { margin-right: 5px; }
</style>
