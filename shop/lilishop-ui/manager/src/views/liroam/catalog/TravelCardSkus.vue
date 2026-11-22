<template>
  <div class="search">
    <el-card>
      <el-tabs v-model="tab" @tab-change="loadTab">
        <el-tab-pane label="SKU 绑定" name="skus">
          <div class="mb_10">
            <el-button type="primary" @click="openSkuCreate">绑定壳 SKU</el-button>
            <span class="liroam-hint">壳商品先在「商品管理」建好（eSIM / 软卡用虚拟商品，实体卡用实物商品），这里把 SKU 绑到套餐映射上。</span>
          </div>
          <el-table v-loading="loading" border :data="skus" style="width: 100%">
            <el-table-column prop="shellSkuId" label="壳 SKU" min-width="170" show-overflow-tooltip />
            <el-table-column label="类型" width="80">
              <template #default="{ row }">{{ labelOf(SKU_KINDS, row.skuKind) }}</template>
            </el-table-column>
            <el-table-column prop="supplierCode" label="供应商" width="100" show-overflow-tooltip />
            <el-table-column prop="cardProductName" label="卡产品" min-width="130" show-overflow-tooltip />
            <el-table-column prop="regionName" label="使用区域" min-width="100" show-overflow-tooltip />
            <el-table-column prop="planName" label="套餐" min-width="150" show-overflow-tooltip />
            <el-table-column label="形态" width="80">
              <template #default="{ row }">{{ labelOf(CARD_FORMS, row.form) }}</template>
            </el-table-column>
            <el-table-column label="实名" width="100">
              <template #default="{ row }">{{ labelOf(KYC_GATE_MODES, row.kycGateMode) }}</template>
            </el-table-column>
            <el-table-column label="含号码" width="80" align="center">
              <template #default="{ row }">{{ row.numberedGate ? "是" : "否" }}</template>
            </el-table-column>
            <el-table-column label="售价" width="100" align="right">
              <template #default="{ row }">{{ row.priceFen === null || row.priceFen === undefined ? "未定价" : `¥${fenToYuan(row.priceFen)}` }}</template>
            </el-table-column>
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="row.listingState === 'LISTED' ? 'success' : 'info'" size="small">{{ labelOf(LISTING_STATES, row.listingState) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="260" fixed="right" align="center">
              <template #default="{ row }">
                <el-button link type="primary" @click="openPrice(row)">定价</el-button>
                <el-button link type="primary" @click="openGates(row)">实名/号码</el-button>
                <el-button v-if="skuActions(row).list" link type="success" @click="act(listSku, row, '已上架')">上架</el-button>
                <el-button v-if="skuActions(row).delist" link type="warning" @click="act(delistSku, row, '已下架')">下架</el-button>
                <el-button v-if="skuActions(row).remove" link type="danger" @click="removeSku(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="套餐映射" name="offerings">
          <div class="mb_10">
            <el-button type="primary" @click="openOfferingCreate">新增映射</el-button>
            <span class="liroam-hint">供应商 × 卡产品 × 使用区域 × 套餐模板 → 上游套餐码；使用区域取自该卡产品当前生效资费表。</span>
          </div>
          <el-table v-loading="loading" border :data="offerings" style="width: 100%">
            <el-table-column prop="id" label="ID" width="80" />
            <el-table-column prop="supplierCode" label="供应商" width="100" />
            <el-table-column prop="cardProductName" label="卡产品" min-width="140" show-overflow-tooltip />
            <el-table-column prop="regionName" label="使用区域" min-width="100" />
            <el-table-column prop="planName" label="套餐模板" min-width="160" show-overflow-tooltip />
            <el-table-column label="类型" width="90">
              <template #default="{ row }">{{ row.kind === "TOP_UP" ? "加油包" : "基础套餐" }}</template>
            </el-table-column>
            <el-table-column prop="upstreamPackageCode" label="上游套餐码" min-width="180" show-overflow-tooltip />
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="套餐模板" name="templates">
          <div class="mb_10"><el-button type="primary" @click="openTemplateCreate">新增模板</el-button></div>
          <el-table v-loading="loading" border :data="templates" style="width: 100%">
            <el-table-column prop="id" label="ID" width="80" />
            <el-table-column prop="name" label="名称" min-width="180" />
            <el-table-column label="额度" min-width="240">
              <template #default="{ row }">{{ planQuotaText(row) }}</template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="卡产品" name="products">
          <el-table v-loading="loading" border :data="products" style="width: 100%">
            <el-table-column prop="id" label="ID" width="80" />
            <el-table-column prop="supplierCode" label="供应商" width="100" />
            <el-table-column prop="name" label="名称" min-width="160" />
            <el-table-column label="能力" min-width="140">
              <template #default="{ row }">{{ capabilityOf(row) }}</template>
            </el-table-column>
            <el-table-column prop="allowedForms" label="可选形态" min-width="140" />
            <el-table-column label="含号码" width="100" align="center">
              <template #default="{ row }">
                <el-switch :model-value="row.hasNumber" @change="(v) => toggleHasNumber(row, v)" />
              </template>
            </el-table-column>
          </el-table>
          <div class="liroam-hint mt_10">含号码的卡产品默认挂「号码卡」闸：全局开关「号码卡可售」关闭时不可下单。</div>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <el-dialog v-model="skuDialog" title="绑定壳 SKU" width="560px">
      <el-form label-width="100px">
        <el-form-item label="壳 SKU ID" required><el-input v-model="skuForm.shellSkuId" placeholder="商品管理里的 SKU ID" /></el-form-item>
        <el-form-item label="套餐映射" required>
          <el-select v-model="skuForm.offeringId" filterable placeholder="选择映射" style="width: 100%">
            <el-option v-for="o in offerings" :key="o.id" :value="o.id"
              :label="`#${o.id} ${o.supplierCode} · ${o.cardProductName} · ${o.regionName || '—'} · ${o.planName}`" />
          </el-select>
        </el-form-item>
        <el-form-item label="卡形态" required>
          <el-select v-model="skuForm.form" style="width: 100%">
            <el-option v-for="f in CARD_FORMS" :key="f.value" :label="f.label" :value="f.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="实名">
          <el-select v-model="skuForm.kycGateMode" clearable placeholder="按区域默认（需实名区域为提示实名）" style="width: 100%">
            <el-option v-for="m in KYC_GATE_MODES" :key="m.value" :label="m.label" :value="m.value" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="skuDialog = false">取消</el-button>
        <el-button type="primary" :disabled="!skuForm.shellSkuId.trim() || !skuForm.offeringId" :loading="saving" @click="saveSku">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="gatesDialog" title="实名与号码" width="480px">
      <el-form label-width="100px">
        <el-form-item label="实名">
          <el-select v-model="gatesForm.kycGateMode" style="width: 100%">
            <el-option v-for="m in KYC_GATE_MODES" :key="m.value" :label="m.label" :value="m.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="号码卡闸"><el-switch v-model="gatesForm.numberedGate" /></el-form-item>
        <div class="liroam-hint">实名后交付：买家自报实名或后台核验通过前不出码、不发货。</div>
      </el-form>
      <template #footer>
        <el-button @click="gatesDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveGates">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="priceDialog" title="设置售价" width="420px">
      <el-form label-width="80px">
        <el-form-item label="SKU">{{ current && current.shellSkuId }}</el-form-item>
        <el-form-item label="售价（元）" required><el-input-number v-model="priceYuan" :min="0.01" :precision="2" :step="1" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="priceDialog = false">取消</el-button>
        <el-button type="primary" :disabled="!priceYuan" :loading="saving" @click="savePrice">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="offeringDialog" title="新增套餐映射" width="560px">
      <el-form label-width="100px">
        <el-form-item label="卡产品" required>
          <el-select v-model="offeringForm.cardProductId" filterable style="width: 100%" @change="loadRegions">
            <el-option v-for="p in products" :key="p.id" :label="`${p.supplierCode} · ${p.name}`" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="使用区域" required>
          <el-select v-model="offeringForm.regionId" filterable style="width: 100%" :disabled="!regions.length">
            <el-option v-for="r in regions" :key="r.regionId" :label="`${r.name}${r.kycRequired ? '（需实名）' : ''}`" :value="r.regionId" />
          </el-select>
        </el-form-item>
        <el-form-item label="套餐模板" required>
          <el-select v-model="offeringForm.planTemplateId" filterable style="width: 100%">
            <el-option v-for="t in templates" :key="t.id" :label="`${t.name}（${planQuotaText(t)}）`" :value="t.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="类型">
          <el-radio-group v-model="offeringForm.kind">
            <el-radio value="BASE">基础套餐</el-radio>
            <el-radio value="TOP_UP">加油包</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="上游套餐码" required><el-input v-model="offeringForm.upstreamPackageCode" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="offeringDialog = false">取消</el-button>
        <el-button type="primary" :disabled="!offeringReady" :loading="saving" @click="saveOffering">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="templateDialog" title="新增套餐模板" width="480px">
      <el-form label-width="100px">
        <el-form-item label="名称" required><el-input v-model="templateForm.name" placeholder="如 日本 7 天 3GB + 100 分钟" /></el-form-item>
        <el-form-item label="流量（GB）"><el-input-number v-model="templateForm.dataGb" :min="0" /></el-form-item>
        <el-form-item label="语音（分钟）"><el-input-number v-model="templateForm.voiceMinutes" :min="0" /></el-form-item>
        <el-form-item label="短信（条）"><el-input-number v-model="templateForm.smsCount" :min="0" /></el-form-item>
        <el-form-item label="有效天数" required><el-input-number v-model="templateForm.validityDays" :min="1" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="templateDialog = false">取消</el-button>
        <el-button type="primary" :disabled="!templateForm.name.trim() || !templateForm.validityDays" :loading="saving" @click="saveTemplate">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import {
  createCatalogSku, createOffering, createPlanTemplate, deleteSku, delistSku, listCardProductRegions, listCatalogCardProducts, listCatalogSkus,
  listOfferings, listPlanTemplates, listSku, setCardProductHasNumber, setSkuPrice, updateSkuGates,
} from "@/api/liroam";
import {
  CARD_FORMS, KYC_GATE_MODES, LISTING_STATES, SKU_KINDS, fenToYuan, labelOf, listOf, planQuotaText, skuActions, yuanToFen,
} from "@/utils/liroam";

defineOptions({ name: "liroam-travel-card-skus" });

const tab = ref("skus");
const loading = ref(false);
const saving = ref(false);
const skus = ref([]);
const offerings = ref([]);
const templates = ref([]);
const products = ref([]);
const regions = ref([]);
const current = ref(null);

const skuDialog = ref(false);
const skuForm = reactive({ shellSkuId: "", offeringId: null, form: "ESIM", kycGateMode: "" });
const gatesDialog = ref(false);
const gatesForm = reactive({ kycGateMode: "NONE", numberedGate: false });
const priceDialog = ref(false);
const priceYuan = ref(null);
const offeringDialog = ref(false);
const offeringForm = reactive({ cardProductId: null, regionId: null, planTemplateId: null, kind: "BASE", upstreamPackageCode: "" });
const templateDialog = ref(false);
const templateForm = reactive({ name: "", dataGb: 0, voiceMinutes: 0, smsCount: 0, validityDays: 7 });

const offeringReady = computed(() => offeringForm.cardProductId && offeringForm.regionId && offeringForm.planTemplateId
  && offeringForm.upstreamPackageCode.trim());

function capabilityOf(p) {
  return [p.capData && "流量", p.capVoice && "语音", p.capSms && "短信"].filter(Boolean).join(" · ");
}

async function fetchList(api, target) {
  const res = await api();
  if (res && res.success) target.value = listOf(res.result);
}

async function loadTab() {
  loading.value = true;
  try {
    if (tab.value === "skus") await Promise.all([fetchList(listCatalogSkus, skus), fetchList(listOfferings, offerings)]);
    if (tab.value === "offerings") await Promise.all([fetchList(listOfferings, offerings), fetchList(listPlanTemplates, templates), fetchList(listCatalogCardProducts, products)]);
    if (tab.value === "templates") await fetchList(listPlanTemplates, templates);
    if (tab.value === "products") await fetchList(listCatalogCardProducts, products);
  } catch {
    // 请求层已提示错误
  } finally {
    loading.value = false;
  }
}

async function run(call, message, after) {
  saving.value = true;
  try {
    const res = await call();
    if (res && res.success) {
      ElMessage.success(message);
      if (after) after();
      await loadTab();
    }
  } catch {
    // 请求层已提示错误
  } finally {
    saving.value = false;
  }
}

function openSkuCreate() {
  Object.assign(skuForm, { shellSkuId: "", offeringId: null, form: "ESIM", kycGateMode: "" });
  skuDialog.value = true;
}

function saveSku() {
  run(() => createCatalogSku({
    shellSkuId: skuForm.shellSkuId.trim(), offeringId: skuForm.offeringId, form: skuForm.form, kycGateMode: skuForm.kycGateMode || null,
  }), "已绑定，设置售价后可上架", () => { skuDialog.value = false; });
}

function openGates(row) {
  current.value = row;
  Object.assign(gatesForm, { kycGateMode: row.kycGateMode, numberedGate: row.numberedGate });
  gatesDialog.value = true;
}

function saveGates() {
  run(() => updateSkuGates(current.value.id, { ...gatesForm }), "已保存", () => { gatesDialog.value = false; });
}

function openPrice(row) {
  current.value = row;
  priceYuan.value = row.priceFen ? Number(fenToYuan(row.priceFen)) : null;
  priceDialog.value = true;
}

function savePrice() {
  run(() => setSkuPrice(current.value.id, yuanToFen(priceYuan.value)), "售价已生效并写回商品", () => { priceDialog.value = false; });
}

function act(api, row, message) {
  run(() => api(row.id), message);
}

async function removeSku(row) {
  try {
    await ElMessageBox.confirm(`删除草稿 SKU 绑定 ${row.shellSkuId}？壳商品本身不受影响。`, "删除", { type: "warning" });
  } catch {
    return;
  }
  run(() => deleteSku(row.id), "已删除");
}

async function openOfferingCreate() {
  Object.assign(offeringForm, { cardProductId: null, regionId: null, planTemplateId: null, kind: "BASE", upstreamPackageCode: "" });
  regions.value = [];
  offeringDialog.value = true;
}

async function loadRegions(cardProductId) {
  offeringForm.regionId = null;
  regions.value = [];
  try {
    const res = await listCardProductRegions(cardProductId);
    if (res && res.success) regions.value = listOf(res.result);
  } catch {
    // 请求层已提示错误
  }
}

function saveOffering() {
  const product = products.value.find((p) => p.id === offeringForm.cardProductId);
  run(() => createOffering({ ...offeringForm, supplierId: product.supplierId, upstreamPackageCode: offeringForm.upstreamPackageCode.trim() }),
    "映射已新增", () => { offeringDialog.value = false; });
}

function openTemplateCreate() {
  Object.assign(templateForm, { name: "", dataGb: 0, voiceMinutes: 0, smsCount: 0, validityDays: 7 });
  templateDialog.value = true;
}

function saveTemplate() {
  const nz = (v) => (v ? v : null);
  run(() => createPlanTemplate({
    name: templateForm.name.trim(), dataGb: nz(templateForm.dataGb), voiceMinutes: nz(templateForm.voiceMinutes), smsCount: nz(templateForm.smsCount),
    validityDays: templateForm.validityDays,
  }), "模板已新增", () => { templateDialog.value = false; });
}

function toggleHasNumber(row, value) {
  run(() => setCardProductHasNumber(row.id, value), value ? "已标记含号码" : "已取消含号码");
}

onMounted(loadTab);
</script>

<style scoped>
.liroam-hint { margin-left: 10px; font-size: 12px; line-height: 1.6; color: #909399; }
</style>
