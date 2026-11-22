<template>
  <div class="search">
    <el-card>
      <el-tabs v-model="tab" @tab-change="loadTab">
        <el-tab-pane label="调价报表" name="report">
          <div class="toolbar">
            <span v-if="report.fxRate !== null && report.fxRate !== undefined">
              当前汇率 1 港币 = {{ report.fxRate }} 人民币<span class="sub">（{{ report.fxFetchedText }} 同步）</span>
            </span>
            <span v-else class="danger">尚未同步过汇率</span>
            <el-tag v-if="report.fxStale" type="danger" size="small" class="ml_10">汇率已过期</el-tag>
            <el-checkbox v-model="attentionOnly" class="ml_10">只看低于最低毛利 / 有待确认建议</el-checkbox>
            <span class="spacer" />
            <el-button @click="loadReport">刷新</el-button>
            <el-button type="primary" :disabled="!selectedSkus.length" @click="openAdjust">批量改价（{{ selectedSkus.length }}）</el-button>
          </div>
          <el-table v-loading="reportLoading" border :data="reportRows" row-key="skuBindingId" style="width: 100%"
                    @selection-change="(rows) => (selectedSkus = rows)">
            <el-table-column type="selection" width="44" :selectable="(row) => row.priceFen !== null && row.costCnyFen !== null" />
            <el-table-column label="SKU" min-width="170" show-overflow-tooltip>
              <template #default="{ row }">{{ row.liGoodsSkuId }}<div class="sub">绑定 #{{ row.skuBindingId }} · {{ row.supplierCode }}</div></template>
            </el-table-column>
            <el-table-column label="卡产品 / 区域 / 套餐" min-width="200" show-overflow-tooltip>
              <template #default="{ row }">
                {{ row.cardProduct }} · {{ row.regionText || "—" }}
                <div class="sub">{{ labelOf(CARD_FORMS, row.form) }} · {{ row.planName }}</div>
              </template>
            </el-table-column>
            <el-table-column label="售价（元）" width="100" align="right">
              <template #default="{ row }">{{ row.priceFen === null ? "未定价" : fenToYuan(row.priceFen) }}</template>
            </el-table-column>
            <el-table-column label="最近成本（元）" width="120" align="right">
              <template #default="{ row }">{{ row.costCnyFen === null ? "—" : fenToYuan(row.costCnyFen) }}</template>
            </el-table-column>
            <el-table-column label="毛利率 / 最低" width="140" align="center">
              <template #default="{ row }">
                <span :class="{ danger: row.belowMin }">{{ rateToPercent(row.marginRate) || "—" }}</span>
                <span class="sub"> / {{ rateToPercent(row.minMarginRate) }}</span>
                <div v-if="row.belowMin"><el-tag type="danger" size="small">低于最低毛利</el-tag></div>
              </template>
            </el-table-column>
            <el-table-column label="待确认建议" width="130" align="center">
              <template #default="{ row }">
                <template v-if="row.pendingSuggestionId">
                  <el-button link type="primary" @click="tab = 'suggestions'; loadTab()">#{{ row.pendingSuggestionId }}</el-button>
                  <div class="sub">建议 ￥{{ fenToYuan(row.suggestedPriceFen) }}</div>
                </template>
                <span v-else>—</span>
              </template>
            </el-table-column>
          </el-table>
          <div class="liroam-hint mt_10">
            毛利率 = (售价 − 最近成本) / 售价；成本随资费表与汇率重算。未定价或缺成本的 SKU 不能批量改价，请先在「旅行卡商品」单独定价。
          </div>
        </el-tab-pane>

        <el-tab-pane label="调价建议" name="suggestions">
          <el-form inline class="search-form" @submit.prevent="search">
            <el-form-item label="状态">
              <el-select v-model="query.status" placeholder="全部" clearable style="width: 160px" @change="search">
                <el-option v-for="s in REPRICE_STATUSES" :key="s.value" :label="s.label" :value="s.value" />
              </el-select>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" class="search-btn" @click="search">刷新</el-button>
              <el-button type="primary" plain :disabled="!pendingSelected.length" @click="actAll('confirm')">批量确认（{{ pendingSelected.length }}）</el-button>
              <el-button type="danger" plain :disabled="!pendingSelected.length" @click="actAll('reject')">批量驳回（{{ pendingSelected.length }}）</el-button>
            </el-form-item>
          </el-form>
          <el-table v-loading="loading" border :data="data" row-key="id" style="width: 100%" @selection-change="(rows) => (selectedSuggestions = rows)">
            <el-table-column type="selection" width="44" :selectable="(row) => row.status === 'PENDING'" />
            <el-table-column prop="id" label="编号" width="90" />
            <el-table-column label="SKU" min-width="170" show-overflow-tooltip>
              <template #default="{ row }">{{ row.liGoodsSkuId }}<div class="sub">绑定 #{{ row.skuBindingId }}</div></template>
            </el-table-column>
            <el-table-column label="触发" width="110">
              <template #default="{ row }">{{ labelOf(REPRICE_TRIGGERS, row.triggerKind) }}</template>
            </el-table-column>
            <el-table-column label="现价（元）" width="110" align="right">
              <template #default="{ row }">{{ fenToYuan(row.currentPriceFen) }}</template>
            </el-table-column>
            <el-table-column label="建议价（元）" width="120" align="right">
              <template #default="{ row }"><strong>{{ fenToYuan(row.suggestedPriceFen) }}</strong></template>
            </el-table-column>
            <el-table-column label="毛利率 调前 → 调后" width="170" align="center">
              <template #default="{ row }">{{ rateToPercent(row.marginBefore) || "—" }} → {{ rateToPercent(row.marginAfter) || "—" }}</template>
            </el-table-column>
            <el-table-column label="状态" width="90">
              <template #default="{ row }">{{ labelOf(REPRICE_STATUSES, row.status) }}</template>
            </el-table-column>
            <el-table-column prop="createdText" label="生成时间" min-width="150" />
            <el-table-column label="处理" min-width="160">
              <template #default="{ row }">
                <template v-if="row.decidedText">{{ row.decidedBy }}<div class="sub">{{ row.decidedText }}</div></template>
                <span v-else>—</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="130" fixed="right" align="center">
              <template #default="{ row }">
                <template v-if="row.status === 'PENDING'">
                  <el-button link type="primary" @click="act(row, 'confirm')">确认</el-button>
                  <el-button link type="danger" @click="act(row, 'reject')">驳回</el-button>
                </template>
              </template>
            </el-table-column>
          </el-table>
          <div class="mt_10" style="display: flex; justify-content: flex-end">
            <el-pagination
              v-model:current-page="query.pageNumber"
              v-model:page-size="query.pageSize"
              :total="total"
              layout="total, prev, pager, next"
              size="small"
              @current-change="load"
            />
          </div>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <el-dialog v-model="adjust.visible" title="批量改价" width="760px" :close-on-click-modal="false">
      <el-form inline @submit.prevent="preview">
        <el-form-item label="方式">
          <el-radio-group v-model="adjust.mode" @change="adjust.result = null">
            <el-radio-button v-for="m in REPRICE_ADJUST_MODES" :key="m.value" :value="m.value">{{ m.label }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item :label="adjust.mode === 'PERCENT' ? '涨跌幅' : '目标毛利率'">
          <el-input-number v-model="adjust.percent" :precision="2" :step="1" :min="adjust.mode === 'PERCENT' ? -99.99 : 0" :max="adjust.mode === 'PERCENT' ? 1000 : 99.99"
                           controls-position="right" style="width: 150px" @change="adjust.result = null" />
          <span class="ml_10">%</span>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" plain :loading="adjust.busy" @click="preview">预览</el-button>
        </el-form-item>
      </el-form>
      <div class="liroam-hint">
        {{ adjust.mode === "PERCENT" ? "在当前售价上按比例涨跌（负数为降价），四舍五入到分。" : "按最近成本反推：售价 = 成本 / (1 − 目标毛利率)，向上取整到分。" }}
        任一 SKU 改价后低于最低毛利率，整批不执行。
      </div>
      <el-table v-if="adjust.result" border :data="adjust.result.rows" class="mt_10" max-height="360">
        <el-table-column prop="liGoodsSkuId" label="SKU" min-width="160" show-overflow-tooltip />
        <el-table-column label="现价" width="90" align="right">
          <template #default="{ row }">{{ fenToYuan(row.currentPriceFen) }}</template>
        </el-table-column>
        <el-table-column label="成本" width="90" align="right">
          <template #default="{ row }">{{ fenToYuan(row.costCnyFen) }}</template>
        </el-table-column>
        <el-table-column label="新售价" width="100" align="right">
          <template #default="{ row }"><strong>{{ fenToYuan(row.newPriceFen) }}</strong></template>
        </el-table-column>
        <el-table-column label="新毛利率 / 最低" min-width="150" align="center">
          <template #default="{ row }">
            <span :class="{ danger: row.belowMin }">{{ rateToPercent(row.marginAfter) }}</span>
            <span class="sub"> / {{ rateToPercent(row.minMarginRate) }}</span>
          </template>
        </el-table-column>
      </el-table>
      <el-alert v-if="adjust.result && adjust.result.belowMinCount" type="error" :closable="false" class="mt_10"
                :title="`${adjust.result.belowMinCount} 个 SKU 改价后低于最低毛利率，整批不能执行，请调整数值或取消勾选这些 SKU`" />
      <template #footer>
        <el-button @click="adjust.visible = false">取消</el-button>
        <el-button type="primary" :loading="adjust.busy" :disabled="!adjust.result || adjust.result.belowMinCount > 0" @click="apply">
          执行改价
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import {
  actOnRepriceSuggestion, actOnRepriceSuggestions, applyRepriceBatch, getRepriceReport, listRepriceSuggestions, previewRepriceBatch,
} from "@/api/liroam";
import {
  CARD_FORMS, REPRICE_ADJUST_MODES, REPRICE_STATUSES, REPRICE_TRIGGERS, describeBatchDecision, fenToYuan, labelOf, pageOf, percentToRate,
  rateToPercent,
} from "@/utils/liroam";

defineOptions({ name: "liroam-reprice-suggestions" });

const tab = ref("report");

const report = ref({ rows: [] });
const reportLoading = ref(false);
const attentionOnly = ref(false);
const selectedSkus = ref([]);
const reportRows = computed(() => (report.value.rows || []).filter((r) => !attentionOnly.value || r.belowMin || r.pendingSuggestionId));

async function loadReport() {
  reportLoading.value = true;
  try {
    const res = await getRepriceReport();
    if (res && res.success) report.value = res.result || { rows: [] };
  } catch {
    // 请求层已提示错误
  } finally {
    reportLoading.value = false;
  }
}

const adjust = reactive({ visible: false, mode: "PERCENT", percent: 5, result: null, busy: false });

function adjustRequest() {
  return { skuBindingIds: selectedSkus.value.map((r) => r.skuBindingId), mode: adjust.mode, value: percentToRate(adjust.percent) };
}

function openAdjust() {
  adjust.result = null;
  adjust.visible = true;
}

async function preview() {
  adjust.busy = true;
  try {
    const res = await previewRepriceBatch(adjustRequest());
    adjust.result = res && res.success ? res.result : null;
  } catch {
    // 请求层已提示错误
  } finally {
    adjust.busy = false;
  }
}

async function apply() {
  try {
    await ElMessageBox.confirm(`按预览给 ${adjust.result.rows.length} 个 SKU 生效新售价，前台价格随之变化，已下订单不受影响；这些 SKU 的待确认调价建议作废。`,
      "执行批量改价", { type: "warning" });
  } catch {
    return;
  }
  adjust.busy = true;
  try {
    const res = await applyRepriceBatch(adjustRequest());
    if (res && res.success) {
      ElMessage.success(`已改价 ${res.result.rows.length} 个 SKU`);
      adjust.visible = false;
      loadReport();
    }
  } catch {
    // 请求层已提示错误
  } finally {
    adjust.busy = false;
  }
}

const query = reactive({ status: "PENDING", pageNumber: 1, pageSize: 20 });
const loading = ref(false);
const data = ref([]);
const total = ref(0);
const selectedSuggestions = ref([]);
const pendingSelected = computed(() => selectedSuggestions.value.filter((r) => r.status === "PENDING"));

async function load() {
  loading.value = true;
  try {
    const res = await listRepriceSuggestions(query);
    const page = pageOf(res && res.success ? res.result : null);
    data.value = page.records;
    total.value = page.total;
  } catch {
    // 请求层已提示错误
  } finally {
    loading.value = false;
  }
}

function search() {
  query.pageNumber = 1;
  load();
}

function loadTab() {
  if (tab.value === "report") loadReport();
  else load();
}

async function act(row, action) {
  const confirmText = action === "confirm"
    ? `确认后生效新售价 ￥${fenToYuan(row.suggestedPriceFen)}，前台价格随之变化，已下订单不受影响。`
    : "驳回后保持现价。";
  try {
    await ElMessageBox.confirm(confirmText, action === "confirm" ? "确认调价" : "驳回调价建议", { type: "warning" });
  } catch {
    return;
  }
  try {
    const res = await actOnRepriceSuggestion(row.id, action);
    if (res && res.success) {
      ElMessage.success(action === "confirm" ? "已确认，新售价已生效" : "已驳回");
      load();
    }
  } catch {
    // 请求层已提示错误
  }
}

async function actAll(action) {
  const rows = pendingSelected.value;
  const verb = action === "confirm" ? "确认" : "驳回";
  const confirmText = action === "confirm"
    ? `逐条生效所选 ${rows.length} 条建议的新售价，前台价格随之变化；个别失败不影响其它条。`
    : `所选 ${rows.length} 条建议驳回后保持现价。`;
  try {
    await ElMessageBox.confirm(confirmText, `批量${verb}`, { type: "warning" });
  } catch {
    return;
  }
  try {
    const res = await actOnRepriceSuggestions(rows.map((r) => r.id), action);
    if (res && res.success) {
      const text = describeBatchDecision(res.result, verb);
      if (res.result && res.result.failed && res.result.failed.length) ElMessage.warning({ message: text, duration: 8000 });
      else ElMessage.success(text);
      load();
    }
  } catch {
    // 请求层已提示错误
  }
}

onMounted(loadReport);
</script>

<style scoped>
.sub { font-size: 12px; color: #909399; }
.danger { color: #f56c6c; }
.liroam-hint { font-size: 12px; line-height: 1.6; color: #909399; }
.toolbar { display: flex; align-items: center; margin-bottom: 10px; }
.spacer { flex: 1; }
.ml_10 { margin-left: 10px; }
</style>
