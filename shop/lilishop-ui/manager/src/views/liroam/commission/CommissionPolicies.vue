<template>
  <div class="search">
    <el-card>
      <div class="mb_10">
        <el-button type="primary" @click="openCreate">新增策略</el-button>
        <el-button @click="load">刷新</el-button>
      </div>
      <el-table v-loading="loading" border :data="rows" style="width: 100%">
        <el-table-column label="编码" width="150">
          <template #default="{ row }">{{ row.policy.code }} <span class="liroam-hint">v{{ row.policy.version }}</span></template>
        </el-table-column>
        <el-table-column label="作用域" width="170">
          <template #default="{ row }">
            {{ labelOf(COMMISSION_SCOPES, row.policy.scopeType) }}<template v-if="row.policy.scopeRef">：{{ row.policy.scopeRef }}</template>
          </template>
        </el-table-column>
        <el-table-column label="规则" min-width="360">
          <template #default="{ row }">{{ policySummary(row.policy, modes) }}</template>
        </el-table-column>
        <el-table-column label="渠道订单" width="90" align="center">
          <template #default="{ row }">{{ row.policy.includeChannelOrders ? "计佣" : "不计" }}</template>
        </el-table-column>
        <el-table-column label="底价" width="80" align="center">
          <template #default="{ row }">{{ row.policy.mode === "FLOOR_DIFF" ? row.floorPrices.length : "—" }}</template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row.policy)">改规则</el-button>
            <el-button v-if="row.policy.mode === 'FLOOR_DIFF'" link type="primary" @click="openFloor(row)">底价</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="liroam-hint mt_10">
        每次保存都生成新版本，已产生的佣金按当时版本计，不回溯。同一笔订单按「SKU &gt; 代理等级 &gt; 全局」取一条策略；一级 = 成交的分销员，二级 = 其邀请人。
      </div>
    </el-card>

    <el-dialog v-model="policyDialog" :title="form.isNew ? '新增佣金策略' : `修改策略 ${form.code}`" width="600px">
      <el-form label-width="120px">
        <el-form-item label="编码" required>
          <el-input v-model="form.code" :disabled="!form.isNew" placeholder="大写字母开头，如 DEFAULT" />
        </el-form-item>
        <el-form-item label="佣金模式" required>
          <el-select v-model="form.mode" style="width: 100%">
            <el-option v-for="m in modes" :key="m.mode" :label="m.title" :value="m.mode" />
          </el-select>
        </el-form-item>
        <el-form-item label="作用域" required>
          <el-select v-model="form.scopeType" :disabled="!form.isNew" style="width: 160px" class="mr_10">
            <el-option v-for="s in COMMISSION_SCOPES" :key="s.value" :label="s.label" :value="s.value" />
          </el-select>
          <el-input v-if="form.scopeType !== 'GLOBAL'" v-model="form.scopeRef" :disabled="!form.isNew" style="width: 200px"
            :placeholder="form.scopeType === 'SKU' ? 'SKU 绑定 ID' : '代理等级'" />
        </el-form-item>
        <template v-if="form.mode !== 'NONE'">
          <el-form-item v-if="form.mode === 'PAID_RATE'" label="抽成比例（%）" required>
            <el-input-number v-model="form.poolPercent" :min="0" :max="100" :precision="2" />
            <span class="liroam-hint ml_10">实付（扣已退款）× 比例 = 佣金池</span>
          </el-form-item>
          <el-form-item label="一级分成（%）" required><el-input-number v-model="form.directPercent" :min="0" :max="100" :precision="2" /></el-form-item>
          <el-form-item label="二级分成（%）"><el-input-number v-model="form.invitePercent" :min="0" :max="100" :precision="2" /></el-form-item>
          <el-form-item label="结算">
            T+<el-input-number v-model="form.settleDays" :min="0" :max="30" class="ml_10 mr_10" style="width: 120px" />
            <el-select v-model="form.settleAnchor" style="width: 140px">
              <el-option v-for="a in SETTLE_ANCHORS" :key="a.value" :label="a.label" :value="a.value" />
            </el-select>
          </el-form-item>
          <el-form-item label="渠道订单计佣"><el-switch v-model="form.includeChannelOrders" /></el-form-item>
          <el-form-item label="提现需审批"><el-switch v-model="form.withdrawApproval" /></el-form-item>
        </template>
      </el-form>
      <template #footer>
        <el-button @click="policyDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="savePolicy">保存为新版本</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="floorDialog" :title="`底价 · ${floorPolicy && floorPolicy.policy.code}`" width="680px">
      <el-table border :data="floorPolicy ? floorPolicy.floorPrices : []" style="width: 100%" class="mb_10">
        <el-table-column label="SKU" min-width="200">
          <template #default="{ row }">{{ skuLabel(row.skuBindingId) }}</template>
        </el-table-column>
        <el-table-column label="适用" width="160">
          <template #default="{ row }">{{ row.agentRef ? `分销员 ${row.agentRef}` : `等级 ${row.agentGrade}` }}</template>
        </el-table-column>
        <el-table-column label="底价（元）" width="110" align="right">
          <template #default="{ row }">{{ fenToYuan(row.floorPriceFen) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="80" align="center">
          <template #default="{ row }"><el-button link type="danger" @click="removeFloor(row)">删除</el-button></template>
        </el-table-column>
      </el-table>
      <el-form inline label-width="70px">
        <el-form-item label="SKU">
          <el-select v-model="floorForm.skuBindingId" filterable style="width: 260px">
            <el-option v-for="s in skus" :key="s.id" :value="s.id" :label="skuLabel(s.id)" />
          </el-select>
        </el-form-item>
        <el-form-item label="代理等级"><el-input v-model="floorForm.agentGrade" placeholder="与分销员二选一" style="width: 140px" /></el-form-item>
        <el-form-item label="分销员"><el-input v-model="floorForm.agentRef" placeholder="分销员 ID" style="width: 140px" /></el-form-item>
        <el-form-item label="底价（元）"><el-input-number v-model="floorForm.floorYuan" :min="0" :precision="2" /></el-form-item>
        <el-form-item><el-button type="primary" :loading="saving" @click="saveFloor">保存</el-button></el-form-item>
      </el-form>
      <div class="liroam-hint">底价差价模式：实付高于底价的部分为佣金池；没设底价的 SKU 不计佣。</div>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from "vue";
import { ElMessage } from "element-plus";
import { listCatalogSkus, listCommissionModes, listCommissionPolicies, saveCommissionFloorPrice, saveCommissionPolicy } from "@/api/liroam";
import { COMMISSION_SCOPES, SETTLE_ANCHORS, fenToYuan, labelOf, listOf, percentToRate, policySummary, yuanToFen } from "@/utils/liroam";

defineOptions({ name: "liroam-commission-policies" });

const loading = ref(false);
const saving = ref(false);
const rows = ref([]);
const modes = ref([]);
const skus = ref([]);
const policyDialog = ref(false);
const floorDialog = ref(false);
const floorPolicy = ref(null);
const form = reactive({});
const floorForm = reactive({ skuBindingId: null, agentGrade: "", agentRef: "", floorYuan: null });

const percent = (rate) => (rate === null || rate === undefined ? null : Number((Number(rate) * 100).toFixed(2)));

async function load() {
  loading.value = true;
  try {
    const [m, p, s] = await Promise.all([listCommissionModes(), listCommissionPolicies(), listCatalogSkus()]);
    if (m && m.success) modes.value = m.result || [];
    if (p && p.success) rows.value = p.result || [];
    if (s && s.success) skus.value = listOf(s.result);
    if (floorPolicy.value) floorPolicy.value = rows.value.find((r) => r.policy.code === floorPolicy.value.policy.code) || null;
  } catch {
    // 请求层已提示错误
  } finally {
    loading.value = false;
  }
}

function skuLabel(id) {
  const s = skus.value.find((x) => x.id === id);
  return s ? `#${s.id} ${s.cardProductName || ""} · ${s.planName || ""}` : `#${id}`;
}

function fill(p) {
  Object.assign(form, {
    isNew: !p,
    code: p ? p.code : "",
    mode: p ? p.mode : "PAID_RATE",
    scopeType: p ? p.scopeType : "GLOBAL",
    scopeRef: p ? p.scopeRef || "" : "",
    poolPercent: p ? percent(p.poolRate) : null,
    directPercent: p ? percent(p.directRate) : 100,
    invitePercent: p ? percent(p.inviteRate) : 0,
    settleDays: p ? p.settleDays : 1,
    settleAnchor: p ? p.settleAnchor : "DELIVERED",
    includeChannelOrders: p ? p.includeChannelOrders : false,
    withdrawApproval: p ? p.withdrawApproval : true,
  });
  policyDialog.value = true;
}

const openCreate = () => fill(null);
const openEdit = (p) => fill(p);

async function savePolicy() {
  saving.value = true;
  try {
    const res = await saveCommissionPolicy({
      code: form.code.trim(),
      mode: form.mode,
      scopeType: form.scopeType,
      scopeRef: form.scopeType === "GLOBAL" ? null : form.scopeRef.trim(),
      poolRate: form.mode === "PAID_RATE" ? percentToRate(form.poolPercent) : null,
      directRate: percentToRate(form.directPercent),
      inviteRate: percentToRate(form.invitePercent),
      includeChannelOrders: form.includeChannelOrders,
      settleDays: form.settleDays,
      settleAnchor: form.settleAnchor,
      withdrawApproval: form.withdrawApproval,
    });
    if (res && res.success) {
      ElMessage.success(`已保存为 v${res.result.version}`);
      policyDialog.value = false;
      load();
    }
  } catch {
    // 请求层已提示错误
  } finally {
    saving.value = false;
  }
}

function openFloor(row) {
  floorPolicy.value = row;
  Object.assign(floorForm, { skuBindingId: null, agentGrade: "", agentRef: "", floorYuan: null });
  floorDialog.value = true;
}

async function submitFloor(body, message) {
  saving.value = true;
  try {
    const res = await saveCommissionFloorPrice(floorPolicy.value.policy.code, body);
    if (res && res.success) {
      ElMessage.success(message);
      await load();
    }
  } catch {
    // 请求层已提示错误
  } finally {
    saving.value = false;
  }
}

function saveFloor() {
  submitFloor({
    skuBindingId: floorForm.skuBindingId,
    agentGrade: floorForm.agentGrade.trim() || null,
    agentRef: floorForm.agentRef.trim() || null,
    floorPriceFen: yuanToFen(floorForm.floorYuan),
  }, "底价已保存（策略升一版）");
}

function removeFloor(row) {
  submitFloor({ skuBindingId: row.skuBindingId, agentGrade: row.agentGrade, agentRef: row.agentRef, floorPriceFen: null }, "底价已删除（策略升一版）");
}

onMounted(load);
</script>
