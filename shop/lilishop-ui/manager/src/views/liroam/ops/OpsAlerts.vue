<template>
  <div class="search">
    <el-card>
      <div class="mb_10 ops-head">
        <el-button type="primary" :loading="loading" @click="load">刷新</el-button>
        <span class="liroam-hint">阈值取 liroam 设置项 alert.*（默认值见上线清单）；后台每 5 分钟巡检一次并写日志，这里看当前快照。</span>
      </div>
      <el-empty v-if="!loading && !alerts.length" description="当前没有告警" />
      <el-table v-else v-loading="loading" border :data="alerts" style="width: 100%">
        <el-table-column label="级别" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="alertLevelTag(row.level)" size="small">{{ row.level === "CRITICAL" ? "严重" : "注意" }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="title" label="告警" min-width="180" />
        <el-table-column prop="count" label="数量" width="80" align="center" />
        <el-table-column prop="detail" label="说明" min-width="320" show-overflow-tooltip />
        <el-table-column prop="code" label="编码" width="200" />
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, ref } from "vue";
import { listOpsAlerts } from "@/api/liroam";
import { alertLevelTag, listOf } from "@/utils/liroam";

defineOptions({ name: "liroam-ops-alerts" });

const loading = ref(false);
const alerts = ref([]);

async function load() {
  loading.value = true;
  try {
    const res = await listOpsAlerts();
    if (res && res.success) alerts.value = listOf(res.result);
  } catch {
    // 请求层已提示错误
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>

<style scoped>
.ops-head { display: flex; align-items: center; }
.liroam-hint { margin-left: 10px; font-size: 12px; color: #909399; }
</style>
