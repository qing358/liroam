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
          </template>
        </el-table-column>
        <el-table-column prop="remainingText" label="剩余" min-width="110" show-overflow-tooltip />
        <el-table-column prop="expiresText" label="到期" min-width="140" show-overflow-tooltip />
        <el-table-column prop="ownerMemberId" label="买家" min-width="150" show-overflow-tooltip />
        <el-table-column prop="orderSn" label="订单" min-width="170" show-overflow-tooltip />
        <el-table-column prop="supplierCode" label="供应商" width="120" show-overflow-tooltip />
        <el-table-column prop="updatedText" label="更新时间" min-width="140" show-overflow-tooltip />
        <el-table-column label="操作" width="150" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button link type="primary" @click="openCredential(row)">录入激活码</el-button>
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
            <el-table-column prop="remainingText" label="剩余" min-width="110" />
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
      hint="卡池 CSV 格式以供应商卡产品为准；导入后用于先分配卡号再订购的供应商。"
      :ready="!!(poolForm.supplierId && poolForm.cardProductId)"
      :upload="(file) => importPool(file, poolForm.supplierId, poolForm.cardProductId)"
      @done="load"
    >
      <el-form-item label="供应商 ID" required>
        <el-input v-model="poolForm.supplierId" placeholder="供应商 ID" />
      </el-form-item>
      <el-form-item label="卡产品 ID" required>
        <el-input v-model="poolForm.cardProductId" placeholder="卡产品 ID" />
      </el-form-item>
    </FileImportDialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from "vue";
import { ElMessage } from "element-plus";
import { getCard, importCredentials, importPool, listCards, saveCardCredential } from "@/api/liroam";
import { CARD_STATUSES, cardFormText, listOf, pageOf, pick } from "@/utils/liroam";
import FileImportDialog from "../components/FileImportDialog.vue";

defineOptions({ name: "liroam-card-ledger" });

const DETAIL_FIELDS = [
  { key: "cardNo", label: "卡号", keys: ["cardNo"] },
  { key: "status", label: "状态", keys: ["statusText", "status"] },
  { key: "productName", label: "产品", keys: ["productName"] },
  { key: "usageRegionName", label: "使用区域", keys: ["usageRegionName"] },
  { key: "form", label: "形态", format: cardFormText },
  { key: "iccidLast4", label: "ICCID 尾号", keys: ["iccidLast4"] },
  { key: "remainingText", label: "剩余", keys: ["remainingText"] },
  { key: "expiresText", label: "到期", keys: ["expiresText"] },
  { key: "refundHold", label: "售后冻结", format: (d) => (d.refundHold ? "是" : "否") },
  { key: "kyc", label: "实名", keys: ["kycStatusText", "kycStatus"] },
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
const poolForm = reactive({ supplierId: "", cardProductId: "" });

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

onMounted(load);
</script>

<style scoped>
.liroam-section-title { margin: 20px 0 10px; font-size: 14px; font-weight: 600; color: #303133; }
</style>
