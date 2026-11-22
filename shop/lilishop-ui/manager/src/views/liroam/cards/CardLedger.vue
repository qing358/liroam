<template>
  <div class="search">
    <el-card>
      <el-form :model="searchForm" inline label-width="70px" class="search-form" @keyup.enter="handleSearch">
        <el-form-item label="关键词">
          <el-input v-model="searchForm.keyword" placeholder="卡号 / 订单号 / ICCID 尾号" clearable style="width: 220px" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="searchForm.status" placeholder="全部" clearable style="width: 160px">
            <el-option v-for="item in CARD_STATUSES" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="供应商">
          <el-input v-model="searchForm.supplierCode" placeholder="供应商编码" clearable style="width: 160px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" class="search-btn" @click="handleSearch">搜索</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card>
      <div class="mb_10">
        <el-button type="primary" @click="credentialImportVisible = true">导入激活码 CSV</el-button>
        <el-button @click="poolImportVisible = true">导入卡池 CSV</el-button>
      </div>
      <el-table v-loading="loading" border :data="data" style="width: 100%">
        <el-table-column prop="cardNo" label="卡号" min-width="160" fixed="left" show-overflow-tooltip />
        <el-table-column prop="productName" label="产品" min-width="150" show-overflow-tooltip />
        <el-table-column prop="usageRegionName" label="使用区域" min-width="110" show-overflow-tooltip />
        <el-table-column label="形态" width="80">
          <template #default="{ row }">{{ cardFormText(row) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            {{ pick(row, "statusText", "status") }}
            <el-tag v-if="row.refundHold" type="warning" size="small">售后中</el-tag>
            <el-tag v-if="row.kycBlocking" type="danger" size="small">待实名</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="remainingText" label="剩余" min-width="110" show-overflow-tooltip />
        <el-table-column prop="expiresText" label="到期" min-width="140" show-overflow-tooltip />
        <el-table-column prop="ownerMemberId" label="买家" min-width="150" show-overflow-tooltip />
        <el-table-column prop="orderSn" label="订单" min-width="170" show-overflow-tooltip />
        <el-table-column prop="supplierCode" label="供应商" width="120" show-overflow-tooltip />
        <el-table-column prop="updatedText" label="更新时间" min-width="140" show-overflow-tooltip />
        <el-table-column label="操作" width="200" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button v-if="cardActions(row).credential" link type="primary" @click="openCredential(row)">录入激活码</el-button>
            <el-button v-if="cardActions(row).shipment" link type="primary" @click="openShipment(row)">录入物流</el-button>
            <el-button v-if="cardActions(row).kycReview" link type="warning" @click="openKyc(row)">实名核验</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="mt_10" style="display: flex; justify-content: flex-end">
        <el-pagination
          v-model:current-page="searchForm.pageNumber"
          v-model:page-size="searchForm.pageSize"
          :page-sizes="[20, 50, 100]"
          :total="total"
          layout="total, sizes, prev, pager, next, jumper"
          size="small"
          @current-change="load"
          @size-change="handleSearch"
        />
      </div>
    </el-card>

    <el-drawer v-model="detailVisible" title="卡详情" size="760px">
      <div v-loading="detailLoading">
        <template v-if="detail">
          <el-descriptions :column="2" border>
            <el-descriptions-item v-for="field in DETAIL_FIELDS" :key="field.key" :label="field.label">
              {{ field.format ? field.format(detail) : pick(detail, ...field.keys) || "—" }}
            </el-descriptions-item>
          </el-descriptions>
          <h4 class="liroam-section-title">套餐</h4>
          <el-table :data="listOf(detail.plans)" border size="small" empty-text="暂无套餐">
            <el-table-column prop="name" label="套餐" min-width="160" show-overflow-tooltip />
            <el-table-column label="状态" width="110">
              <template #default="{ row }">{{ pick(row, "statusText", "status") }}</template>
            </el-table-column>
            <el-table-column prop="remainingText" label="流量剩余" min-width="100" />
            <el-table-column label="语音剩余" min-width="90">
              <template #default="{ row }">{{ row.voiceRemainingText || "—" }}</template>
            </el-table-column>
            <el-table-column label="短信剩余" min-width="90">
              <template #default="{ row }">{{ row.smsRemainingText || "—" }}</template>
            </el-table-column>
            <el-table-column prop="expiresText" label="到期" min-width="140" />
          </el-table>
          <h4 class="liroam-section-title">指令</h4>
          <el-table :data="listOf(detail.commands)" border size="small" empty-text="暂无指令">
            <el-table-column label="指令" width="140">
              <template #default="{ row }">{{ pick(row, "kindText", "kind", "type", "commandType") }}</template>
            </el-table-column>
            <el-table-column label="状态" width="110">
              <template #default="{ row }">{{ pick(row, "statusText", "status") }}</template>
            </el-table-column>
            <el-table-column label="尝试" width="70" align="center">
              <template #default="{ row }">{{ pick(row, "attempts", "attemptCount") }}</template>
            </el-table-column>
            <el-table-column label="最近错误" min-width="160" show-overflow-tooltip>
              <template #default="{ row }">{{ pick(row, "lastError", "errorMessage", "error") }}</template>
            </el-table-column>
            <el-table-column label="时间" min-width="140">
              <template #default="{ row }">{{ pick(row, "updatedText", "createdText") }}</template>
            </el-table-column>
          </el-table>
        </template>
      </div>
    </el-drawer>

    <el-dialog v-model="credentialVisible" title="录入激活码" width="520px">
      <div v-if="credentialCard" class="mb_10">卡号：{{ credentialCard.cardNo }}</div>
      <el-input v-model="credentialLpa" type="textarea" :rows="3" placeholder="LPA:1$SM-DP+地址$激活码" />
      <template #footer>
        <el-button @click="credentialVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!credentialLpa.trim()" :loading="credentialSaving" @click="saveCredential">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="shipmentVisible" title="录入物流（实体卡寄出）" width="520px">
      <el-form label-width="90px">
        <el-form-item label="卡号">{{ shipmentCard && shipmentCard.cardNo }}</el-form-item>
        <el-form-item label="物流公司" required>
          <el-select v-model="shipmentForm.logisticsId" placeholder="选择物流公司" filterable style="width: 100%">
            <el-option v-for="item in logistics" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="运单号" required>
          <el-input v-model="shipmentForm.logisticsNo" placeholder="运单号" />
        </el-form-item>
        <div class="liroam-hint">保存即视为该卡已交付；整单都交付后自动在订单上发货（同一运单），买家在「我的卡」看到物流。</div>
      </el-form>
      <template #footer>
        <el-button @click="shipmentVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!shipmentForm.logisticsId || !shipmentForm.logisticsNo.trim()" :loading="shipmentSaving" @click="saveShipment">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="kycVisible" title="实名核验" width="520px">
      <el-form label-width="90px">
        <el-form-item label="卡号">{{ kycCard && kycCard.cardNo }}</el-form-item>
        <el-form-item label="当前">{{ kycCard && pick(kycCard, "kycText", "kycStatus") }}</el-form-item>
        <el-form-item label="结果" required>
          <el-radio-group v-model="kycForm.result">
            <el-radio value="VERIFIED">通过</el-radio>
            <el-radio value="REJECTED">驳回</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="kycForm.note" type="textarea" :rows="2" placeholder="核验依据或驳回原因（买家不可见）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="kycVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!kycForm.result" :loading="kycSaving" @click="saveKyc">提交</el-button>
      </template>
    </el-dialog>

    <FileImportDialog
      v-model="credentialImportVisible"
      title="导入激活码 CSV"
      hint="CSV 两列：iccid,lpa（首行可为表头）；按 ICCID 匹配已有卡并交码。"
      :upload="importCredentials"
      @done="load"
    />
    <FileImportDialog
      v-model="poolImportVisible"
      title="导入卡池 CSV"
      hint="CSV：iccid[,lpa]，首行可为表头；导入后用于先分配卡号再订购的供应商。实体卡与 eSIM 库存分开导入。"
      :ready="!!(poolForm.supplierId && poolForm.cardProductId)"
      :upload="(file) => importPool(file, poolForm.supplierId, poolForm.cardProductId, poolForm.form)"
      @done="load"
    >
      <el-form-item label="供应商 ID" required>
        <el-input v-model="poolForm.supplierId" placeholder="供应商 ID" />
      </el-form-item>
      <el-form-item label="卡产品 ID" required>
        <el-input v-model="poolForm.cardProductId" placeholder="卡产品 ID" />
      </el-form-item>
      <el-form-item label="卡形态">
        <el-select v-model="poolForm.form" placeholder="默认 eSIM" clearable style="width: 100%">
          <el-option v-for="item in CARD_FORMS" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
      </el-form-item>
    </FileImportDialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from "vue";
import { ElMessage } from "element-plus";
import { getCard, importCredentials, importPool, listCards, listLogistics, reviewCardKyc, saveCardCredential, saveCardShipment } from "@/api/liroam";
import { CARD_FORMS, CARD_STATUSES, cardActions, cardFormText, listOf, pageOf, pick } from "@/utils/liroam";
import FileImportDialog from "../components/FileImportDialog.vue";

defineOptions({ name: "liroam-card-ledger" });

const DETAIL_FIELDS = [
  { key: "cardNo", label: "卡号", keys: ["cardNo"] },
  { key: "status", label: "状态", keys: ["statusText", "status"] },
  { key: "productName", label: "产品", keys: ["productName"] },
  { key: "usageRegionName", label: "使用区域", keys: ["usageRegionName"] },
  { key: "form", label: "形态", format: cardFormText },
  { key: "iccidLast4", label: "ICCID 尾号", keys: ["iccidLast4"] },
  { key: "capabilityText", label: "能力", keys: ["capabilityText"] },
  { key: "remainingText", label: "流量剩余", keys: ["remainingText"] },
  { key: "voiceRemainingText", label: "语音剩余", keys: ["voiceRemainingText"] },
  { key: "smsRemainingText", label: "短信剩余", keys: ["smsRemainingText"] },
  { key: "expiresText", label: "到期", keys: ["expiresText"] },
  { key: "refundHold", label: "售后冻结", format: (d) => (d.refundHold ? "是" : "否") },
  { key: "kyc", label: "实名", keys: ["kycText", "kycStatus"] },
  { key: "ownerMemberId", label: "买家", keys: ["ownerMemberId"] },
  { key: "orderSn", label: "订单", keys: ["orderSn"] },
  { key: "supplierCode", label: "供应商", keys: ["supplierCode"] },
  { key: "createdText", label: "创建时间", keys: ["createdText"] },
  { key: "updatedText", label: "更新时间", keys: ["updatedText"] },
];

const searchForm = reactive({ keyword: "", status: "", supplierCode: "", pageNumber: 1, pageSize: 20 });
const loading = ref(false);
const data = ref([]);
const total = ref(0);

const detailVisible = ref(false);
const detailLoading = ref(false);
const detail = ref(null);

const credentialVisible = ref(false);
const credentialCard = ref(null);
const credentialLpa = ref("");
const credentialSaving = ref(false);

const credentialImportVisible = ref(false);
const poolImportVisible = ref(false);
const poolForm = reactive({ supplierId: "", cardProductId: "", form: "" });

const shipmentVisible = ref(false);
const shipmentCard = ref(null);
const shipmentForm = reactive({ logisticsId: "", logisticsNo: "" });
const shipmentSaving = ref(false);
const logistics = ref([]);

const kycVisible = ref(false);
const kycCard = ref(null);
const kycForm = reactive({ result: "", note: "" });
const kycSaving = ref(false);

function handleSearch() {
  searchForm.pageNumber = 1;
  load();
}

async function load() {
  loading.value = true;
  try {
    const res = await listCards(searchForm);
    const page = pageOf(res && res.success ? res.result : null);
    data.value = page.records;
    total.value = page.total;
  } catch {
    // 请求层已提示错误
  } finally {
    loading.value = false;
  }
}

async function openDetail(row) {
  detailVisible.value = true;
  detail.value = row;
  detailLoading.value = true;
  try {
    const res = await getCard(row.id);
    if (res && res.success && res.result) detail.value = res.result;
  } catch {
    // 请求层已提示错误
  } finally {
    detailLoading.value = false;
  }
}

function openCredential(row) {
  credentialCard.value = row;
  credentialLpa.value = "";
  credentialVisible.value = true;
}

async function saveCredential() {
  credentialSaving.value = true;
  try {
    const res = await saveCardCredential(credentialCard.value.id, credentialLpa.value.trim());
    if (res && res.success) {
      ElMessage.success("激活码已录入");
      credentialVisible.value = false;
      load();
    }
  } catch {
    // 请求层已提示错误
  } finally {
    credentialSaving.value = false;
  }
}

async function openShipment(row) {
  shipmentCard.value = row;
  shipmentForm.logisticsId = "";
  shipmentForm.logisticsNo = "";
  shipmentVisible.value = true;
  if (logistics.value.length) return;
  try {
    const res = await listLogistics();
    logistics.value = listOf(res && res.success ? res.result : null).filter((l) => l.disabled !== "CLOSE");
  } catch {
    // 请求层已提示错误
  }
}

async function saveShipment() {
  const company = logistics.value.find((l) => l.id === shipmentForm.logisticsId);
  shipmentSaving.value = true;
  try {
    const res = await saveCardShipment(shipmentCard.value.id, {
      logisticsId: shipmentForm.logisticsId, logisticsName: company ? company.name : "", logisticsNo: shipmentForm.logisticsNo.trim(),
    });
    if (res && res.success) {
      ElMessage.success("物流已录入");
      shipmentVisible.value = false;
      load();
    }
  } catch {
    // 请求层已提示错误
  } finally {
    shipmentSaving.value = false;
  }
}

function openKyc(row) {
  kycCard.value = row;
  kycForm.result = "";
  kycForm.note = "";
  kycVisible.value = true;
}

async function saveKyc() {
  kycSaving.value = true;
  try {
    const res = await reviewCardKyc(kycCard.value.id, kycForm.result, kycForm.note.trim());
    if (res && res.success) {
      ElMessage.success("实名核验已提交");
      kycVisible.value = false;
      load();
    }
  } catch {
    // 请求层已提示错误
  } finally {
    kycSaving.value = false;
  }
}

onMounted(load);
</script>

<style scoped>
.liroam-hint { font-size: 12px; line-height: 1.6; color: #909399; }
.liroam-section-title { margin: 20px 0 10px; font-size: 14px; font-weight: 600; color: #303133; }
</style>
