<template>
  <div class="search">
    <el-card v-loading="settingsLoading">
      <template #header>定价设置</template>
      <el-form :model="settings" label-width="150px" style="max-width: 720px">
        <el-form-item label="调价方式">
          <el-radio-group v-model="settings.repriceMode">
            <el-radio value="MANUAL_CONFIRM">生成建议，人工确认</el-radio>
            <el-radio value="AUTO">自动生效（留审计）</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="毛利率变动阈值">
          <el-input-number v-model="settings.repriceThresholdRate" :min="0.0001" :max="0.9999" :step="0.01" :precision="4" />
          <span class="form-hint">小数，{{ rateToPercent(settings.repriceThresholdRate) || "—" }}；毛利率变动超过此值才出调价</span>
        </el-form-item>
        <el-form-item label="最低毛利率">
          <el-input-number v-model="settings.minMarginRate" :min="0" :max="0.9999" :step="0.01" :precision="4" />
          <span class="form-hint">小数，{{ rateToPercent(settings.minMarginRate) || "—" }}</span>
        </el-form-item>
        <el-form-item label="汇率来源">
          <el-input v-model="settings.fxSource" placeholder="汇率来源编码" style="width: 240px" />
        </el-form-item>
        <el-form-item label="汇率同步时间">
          <el-input v-model="settings.fxCron" placeholder="0 0 9 * * *" style="width: 240px" />
          <span class="form-hint">cron：秒 分 时 日 月 周</span>
        </el-form-item>
        <el-form-item label="汇率过期告警（小时）">
          <el-input-number v-model="settings.fxStaleHours" :min="1" :step="1" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="settingsSaving" @click="saveSettings">保存</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card>
      <template #header>
        <div class="card-head">
          <span>
            汇率（HKD → CNY）
            <el-tag v-if="fxStale" type="danger" size="small" class="ml_10">已超过 {{ settings.fxStaleHours }} 小时未更新，成本按旧汇率计算</el-tag>
          </span>
          <el-button type="primary" :loading="syncing" @click="sync">立即同步</el-button>
        </div>
      </template>
      <el-table v-loading="ratesLoading" border :data="rates" style="width: 100%">
        <el-table-column prop="id" label="版本" width="90" />
        <el-table-column label="汇率" min-width="140">
          <template #default="{ row }">1 HKD = {{ row.rate }} CNY</template>
        </el-table-column>
        <el-table-column prop="sourceKey" label="来源" min-width="110" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag v-if="row.status === 'ACTIVE'" type="success" size="small">{{ labelOf(FX_RATE_STATUSES, row.status) }}</el-tag>
            <span v-else>{{ labelOf(FX_RATE_STATUSES, row.status) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="fetchedText" label="取得时间" min-width="160" />
        <el-table-column label="来源发布时间" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ row.sourcePublishedRaw || "—" }}</template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from "vue";
import { ElMessage } from "element-plus";
import { getPricingSettings, listFxRates, savePricingSettings, syncFxRates } from "@/api/liroam";
import { FX_RATE_STATUSES, fxSyncOutcome, labelOf, listOf, rateToPercent } from "@/utils/liroam";

defineOptions({ name: "liroam-pricing-settings" });

const FIELDS = ["repriceMode", "repriceThresholdRate", "minMarginRate", "fxSource", "fxCron", "fxStaleHours"];
const RATE_LIMIT = 20;

const settings = reactive({
  repriceMode: "MANUAL_CONFIRM",
  repriceThresholdRate: null,
  minMarginRate: null,
  fxSource: "",
  fxCron: "",
  fxStaleHours: null,
});
const fxStale = ref(false);
const settingsLoading = ref(false);
const settingsSaving = ref(false);
const rates = ref([]);
const ratesLoading = ref(false);
const syncing = ref(false);

function toNumber(value) {
  if (value === null || value === undefined || value === "") return null;
  const n = Number(value);
  return Number.isFinite(n) ? n : null;
}

function applySettings(result) {
  if (!result || typeof result !== "object") return;
  Object.assign(settings, {
    repriceMode: result.repriceMode,
    repriceThresholdRate: toNumber(result.repriceThresholdRate),
    minMarginRate: toNumber(result.minMarginRate),
    fxSource: result.fxSource || "",
    fxCron: result.fxCron || "",
    fxStaleHours: toNumber(result.fxStaleHours),
  });
  fxStale.value = result.fxStale === true;
}

/** PUT 只改传了的字段：空值不传，保持原值。 */
function settingsBody() {
  const body = {};
  FIELDS.forEach((key) => {
    const value = typeof settings[key] === "string" ? settings[key].trim() : settings[key];
    if (value !== null && value !== undefined && value !== "") body[key] = value;
  });
  return body;
}

async function loadSettings() {
  settingsLoading.value = true;
  try {
    const res = await getPricingSettings();
    if (res && res.success) applySettings(res.result);
  } catch {
    // 请求层已提示错误
  } finally {
    settingsLoading.value = false;
  }
}

async function saveSettings() {
  settingsSaving.value = true;
  try {
    const res = await savePricingSettings(settingsBody());
    if (res && res.success) {
      ElMessage.success("已保存");
      applySettings(res.result);
    }
  } catch {
    // 请求层已提示错误
  } finally {
    settingsSaving.value = false;
  }
}

async function loadRates() {
  ratesLoading.value = true;
  try {
    const res = await listFxRates({ limit: RATE_LIMIT });
    rates.value = listOf(res && res.success ? res.result : null);
  } catch {
    // 请求层已提示错误
  } finally {
    ratesLoading.value = false;
  }
}

async function sync() {
  syncing.value = true;
  try {
    const res = await syncFxRates();
    if (res && res.success) {
      const outcome = fxSyncOutcome(res.result);
      if (outcome.ok) ElMessage.success(outcome.message);
      else ElMessage.error(outcome.message);
      loadRates();
      loadSettings();
    }
  } catch {
    // 请求层已提示错误
  } finally {
    syncing.value = false;
  }
}

onMounted(() => {
  loadSettings();
  loadRates();
});
</script>

<style scoped>
.form-hint { margin-left: 12px; font-size: 12px; color: #909399; }
.card-head { display: flex; align-items: center; justify-content: space-between; }
</style>
