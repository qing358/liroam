<template>
  <div class="search">
    <el-card>
      <div class="liroam-channel-head">
        <span>外部渠道：启停、拉单间隔、计佣与买家看用量；启用前须本进程已装配该渠道适配器。</span>
        <el-button type="primary" @click="load">刷新</el-button>
      </div>
      <el-table v-loading="loading" border :data="channels" highlight-current-row style="width: 100%" @current-change="select">
        <el-table-column prop="code" label="代号" width="100" />
        <el-table-column prop="name" label="名称" min-width="140" />
        <el-table-column prop="adapterKey" label="适配器" min-width="120" />
        <el-table-column label="适配器装配" width="110">
          <template #default="{ row }">
            <el-tag :type="row.adapterWired ? 'success' : 'danger'" size="small">{{ row.adapterWired ? "已装配" : "未装配" }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="启用" width="80">
          <template #default="{ row }">{{ row.enabled ? "是" : "否" }}</template>
        </el-table-column>
        <el-table-column label="拉单间隔" width="120">
          <template #default="{ row }">{{ row.pullCron || "5m（默认）" }}</template>
        </el-table-column>
        <el-table-column label="买家可看用量" width="120">
          <template #default="{ row }">{{ row.buyerCanViewUsage ? "是" : "否" }}</template>
        </el-table-column>
        <el-table-column label="计佣" width="80">
          <template #default="{ row }">{{ row.commissionEnabled ? "是" : "否" }}</template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" @click.stop="openConfig(row)">配置</el-button>
            <el-button link type="primary" @click.stop="select(row)">SKU 映射</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card v-if="current">
      <div class="liroam-channel-head">
        <span>渠道 {{ current.code }} 的 SKU 映射：渠道 SKU → 本站旅行卡新卡 SKU（li_goods_sku.id）。停用等同未映射，渠道单会转人工。</span>
      </div>
      <el-form inline class="search-form">
        <el-form-item label="渠道 SKU">
          <el-input v-model="draft.externalSkuId" placeholder="渠道侧 SKU 编号" clearable style="width: 200px" />
        </el-form-item>
        <el-form-item label="本站 SKU">
          <el-input v-model="draft.liGoodsSkuId" placeholder="li_goods_sku.id" clearable style="width: 220px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="saving" @click="createMapping">新增映射</el-button>
        </el-form-item>
      </el-form>
      <el-table v-loading="mappingsLoading" border :data="mappings" style="width: 100%">
        <el-table-column prop="externalSkuId" label="渠道 SKU" min-width="160" />
        <el-table-column prop="liGoodsSkuId" label="本站 SKU" min-width="180" />
        <el-table-column label="启用" width="90">
          <template #default="{ row }">
            <el-switch :model-value="row.enabled" @change="(v) => toggleMapping(row, v)" />
          </template>
        </el-table-column>
        <el-table-column prop="updatedText" label="更新时间" width="150" />
        <el-table-column label="操作" width="140" align="center">
          <template #default="{ row }">
            <el-button link type="primary" @click="remapMapping(row)">改映射</el-button>
            <el-button link type="danger" @click="removeMapping(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="configVisible" :title="`渠道配置 ${config.code || ''}`" width="480px">
      <el-form label-width="110px">
        <el-form-item label="启用"><el-switch v-model="config.enabled" /></el-form-item>
        <el-form-item label="拉单间隔">
          <el-input v-model="config.pullCron" placeholder="如 5m、PT30S 或 6 段 cron；留空为 5 分钟" />
        </el-form-item>
        <el-form-item label="买家可看用量"><el-switch v-model="config.buyerCanViewUsage" /></el-form-item>
        <el-form-item label="计佣"><el-switch v-model="config.commissionEnabled" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="configVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveConfig">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import {
  createChannelSkuMapping,
  deleteChannelSkuMapping,
  listChannels,
  listChannelSkuMappings,
  updateChannel,
  updateChannelSkuMapping,
} from "@/api/liroam";
import { listOf } from "@/utils/liroam";

defineOptions({ name: "liroam-channels" });

const loading = ref(false);
const channels = ref([]);
const current = ref(null);
const mappings = ref([]);
const mappingsLoading = ref(false);
const saving = ref(false);
const draft = reactive({ externalSkuId: "", liGoodsSkuId: "" });
const configVisible = ref(false);
const config = reactive({ id: null, code: "", enabled: false, pullCron: "", buyerCanViewUsage: false, commissionEnabled: false });

async function load() {
  loading.value = true;
  try {
    const res = await listChannels();
    channels.value = listOf(res && res.success ? res.result : null);
    if (current.value) current.value = channels.value.find((c) => c.id === current.value.id) || null;
  } catch {
    // 请求层已提示错误
  } finally {
    loading.value = false;
  }
}

async function select(row) {
  if (!row) return;
  current.value = row;
  await loadMappings();
}

async function loadMappings() {
  if (!current.value) return;
  mappingsLoading.value = true;
  try {
    const res = await listChannelSkuMappings(current.value.id);
    mappings.value = listOf(res && res.success ? res.result : null);
  } catch {
    // 请求层已提示错误
  } finally {
    mappingsLoading.value = false;
  }
}

function openConfig(row) {
  Object.assign(config, {
    id: row.id,
    code: row.code,
    enabled: row.enabled,
    pullCron: row.pullCron || "",
    buyerCanViewUsage: row.buyerCanViewUsage,
    commissionEnabled: row.commissionEnabled,
  });
  configVisible.value = true;
}

async function saveConfig() {
  saving.value = true;
  try {
    const res = await updateChannel(config.id, {
      enabled: config.enabled,
      pullCron: config.pullCron.trim(),
      buyerCanViewUsage: config.buyerCanViewUsage,
      commissionEnabled: config.commissionEnabled,
    });
    if (res && res.success) {
      ElMessage.success("已保存");
      configVisible.value = false;
      load();
    }
  } catch {
    // 请求层已提示错误
  } finally {
    saving.value = false;
  }
}

async function createMapping() {
  saving.value = true;
  try {
    const res = await createChannelSkuMapping(current.value.id, {
      externalSkuId: draft.externalSkuId.trim(),
      liGoodsSkuId: draft.liGoodsSkuId.trim(),
    });
    if (res && res.success) {
      ElMessage.success("已新增映射；之前因此转人工的渠道单可在「渠道订单」重放");
      draft.externalSkuId = "";
      draft.liGoodsSkuId = "";
      loadMappings();
    }
  } catch {
    // 请求层已提示错误
  } finally {
    saving.value = false;
  }
}

async function toggleMapping(row, enabled) {
  try {
    const res = await updateChannelSkuMapping(row.id, { enabled });
    if (res && res.success) loadMappings();
  } catch {
    // 请求层已提示错误
  }
}

async function remapMapping(row) {
  let sku;
  try {
    const answer = await ElMessageBox.prompt(`渠道 SKU ${row.externalSkuId} 改映射到哪个本站 SKU？`, "改映射", {
      inputValue: row.liGoodsSkuId,
      confirmButtonText: "保存",
      cancelButtonText: "取消",
    });
    sku = (answer.value || "").trim();
  } catch {
    return;
  }
  try {
    const res = await updateChannelSkuMapping(row.id, { liGoodsSkuId: sku });
    if (res && res.success) loadMappings();
  } catch {
    // 请求层已提示错误
  }
}

async function removeMapping(row) {
  try {
    await ElMessageBox.confirm(`删除渠道 SKU ${row.externalSkuId} 的映射？之后该 SKU 的新渠道单会转人工。`, "删除映射", { type: "warning" });
  } catch {
    return;
  }
  try {
    const res = await deleteChannelSkuMapping(row.id);
    if (res && res.success) loadMappings();
  } catch {
    // 请求层已提示错误
  }
}

onMounted(load);
</script>

<style scoped>
.liroam-channel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
  color: #606266;
  font-size: 13px;
}
</style>
