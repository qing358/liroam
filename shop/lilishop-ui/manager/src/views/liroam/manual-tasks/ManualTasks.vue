<template>
  <div class="search">
    <el-card>
      <el-form inline label-width="70px" class="search-form">
        <el-form-item label="状态">
          <el-select v-model="status" placeholder="全部" clearable style="width: 160px" @change="load">
            <el-option label="待处理" value="OPEN" />
            <el-option label="已处理" value="RESOLVED" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" class="search-btn" @click="load">刷新</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card>
      <el-table v-loading="loading" border :data="data" style="width: 100%">
        <el-table-column prop="id" label="编号" width="90" />
        <el-table-column label="类型" width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ pick(row, "kindText", "kind", "type") }}</template>
        </el-table-column>
        <el-table-column label="内容" min-width="240" show-overflow-tooltip>
          <template #default="{ row }">{{ pick(row, "title", "summary", "reason", "message") }}</template>
        </el-table-column>
        <el-table-column label="关联卡" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ pick(row, "cardNo", "cardAssetId") }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">{{ pick(row, "statusText", "status") }}</template>
        </el-table-column>
        <el-table-column label="创建时间" min-width="140">
          <template #default="{ row }">{{ pick(row, "createdText", "createdAt") }}</template>
        </el-table-column>
        <el-table-column label="处理备注" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ pick(row, "note", "resolveNote", "resolution") }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right" align="center">
          <template #default="{ row }">
            <el-button v-if="!isResolved(row)" link type="primary" @click="openResolve(row)">处理</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="resolveVisible" title="处理人工待办" width="480px">
      <div v-if="current" class="mb_10">{{ pick(current, "title", "summary", "reason", "message") }}</div>
      <el-input v-model="note" type="textarea" :rows="4" maxlength="500" show-word-limit placeholder="处理说明（必填，会记入审计）" />
      <template #footer>
        <el-button @click="resolveVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!note.trim()" :loading="saving" @click="resolve">标记已处理</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, ref } from "vue";
import { ElMessage } from "element-plus";
import { listManualTasks, resolveManualTask } from "@/api/liroam";
import { listOf, pick } from "@/utils/liroam";

defineOptions({ name: "liroam-manual-tasks" });

const status = ref("");
const loading = ref(false);
const data = ref([]);
const resolveVisible = ref(false);
const current = ref(null);
const note = ref("");
const saving = ref(false);

function isResolved(row) {
  return ["RESOLVED", "DONE", "CLOSED"].includes(String(row.status || "").toUpperCase());
}

async function load() {
  loading.value = true;
  try {
    const res = await listManualTasks({ status: status.value });
    data.value = listOf(res && res.success ? res.result : null);
  } catch {
    // 请求层已提示错误
  } finally {
    loading.value = false;
  }
}

function openResolve(row) {
  current.value = row;
  note.value = "";
  resolveVisible.value = true;
}

async function resolve() {
  saving.value = true;
  try {
    const res = await resolveManualTask(current.value.id, note.value.trim());
    if (res && res.success) {
      ElMessage.success("已处理");
      resolveVisible.value = false;
      load();
    }
  } catch {
    // 请求层已提示错误
  } finally {
    saving.value = false;
  }
}

onMounted(load);
</script>
