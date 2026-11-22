<template>
  <div class="search">
    <el-card>
      <template #header>
        <div class="card-head">
          <span>资费表</span>
          <div>
            <el-input v-model="sheetQuery.supplierId" placeholder="按供应商 ID 筛选" clearable style="width: 180px" class="mr_10" @change="searchSheets" />
            <el-button @click="searchSheets">刷新</el-button>
            <el-button type="primary" @click="uploadVisible = true">上传资费表</el-button>
          </div>
        </div>
      </template>
      <el-table v-loading="sheetsLoading" border :data="sheets" style="width: 100%">
        <el-table-column prop="id" label="编号" width="90" />
        <el-table-column prop="supplierId" label="供应商 ID" width="100" />
        <el-table-column prop="fileName" label="文件" min-width="200" show-overflow-tooltip />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag v-if="row.status === 'ACTIVE'" type="success" size="small">{{ labelOf(TARIFF_SHEET_STATUSES, row.status) }}</el-tag>
            <span v-else>{{ labelOf(TARIFF_SHEET_STATUSES, row.status) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="生效日期" width="120">
          <template #default="{ row }">{{ row.effectiveFromText || "—" }}</template>
        </el-table-column>
        <el-table-column prop="importedBy" label="导入人" width="110" />
        <el-table-column prop="createdText" label="上传时间" min-width="150" />
        <el-table-column label="操作" width="100" fixed="right" align="center">
          <template #default="{ row }">
            <el-button v-if="row.status === 'DRAFT'" link type="primary" @click="activate(row)">启用</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="mt_10" style="display: flex; justify-content: flex-end">
        <el-pagination
          v-model:current-page="sheetQuery.pageNumber"
          v-model:page-size="sheetQuery.pageSize"
          :total="sheetsTotal"
          layout="total, prev, pager, next"
          size="small"
          @current-change="loadSheets"
        />
      </div>
    </el-card>

    <el-card>
      <template #header>卡产品使用区域（按生效资费表）</template>
      <el-form inline @submit.prevent="loadRegions">
        <el-form-item label="卡产品 ID">
          <el-input v-model="cardProductId" placeholder="卡产品 ID" clearable style="width: 200px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :disabled="!cardProductId" @click="loadRegions">查询</el-button>
        </el-form-item>
      </el-form>
      <el-table v-loading="regionsLoading" border :data="regions" row-key="regionId" style="width: 100%" empty-text="输入卡产品 ID 查询">
        <el-table-column prop="code" label="编码" width="100" />
        <el-table-column prop="name" label="国家 / 地区" min-width="130" />
        <el-table-column prop="continent" label="所属大洲" width="100" />
        <el-table-column label="需实名" width="80" align="center">
          <template #default="{ row }">{{ row.kycRequired ? "是" : "否" }}</template>
        </el-table-column>
        <el-table-column label="流量成本（HKD/GB）" width="150" align="right">
          <template #default="{ row }">{{ row.unitCostHkd ?? "—" }}</template>
        </el-table-column>
        <el-table-column label="备注" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">{{ row.note || "—" }}</template>
        </el-table-column>
      </el-table>
    </el-card>

    <FileImportDialog
      v-model="uploadVisible"
      title="上传资费表"
      accept=".xlsx"
      hint="上传供应商资费表原件（xlsx）；上传后为待启用版本，启用后才影响成本与调价建议。"
      :ready="!!supplierId"
      :upload="(file) => uploadTariffSheet(file, supplierId)"
      :describe="describeTariffImport"
      @done="loadSheets"
    >
      <el-form-item label="供应商 ID" required>
        <el-input v-model="supplierId" placeholder="供应商 ID" />
      </el-form-item>
    </FileImportDialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { activateTariffSheet, listCardProductRegions, listTariffSheets, uploadTariffSheet } from "@/api/liroam";
import { TARIFF_SHEET_STATUSES, describeTariffImport, labelOf, listOf, pageOf } from "@/utils/liroam";
import FileImportDialog from "../components/FileImportDialog.vue";

defineOptions({ name: "liroam-tariff-sheets" });

const sheetQuery = reactive({ supplierId: "", pageNumber: 1, pageSize: 20 });
const sheets = ref([]);
const sheetsTotal = ref(0);
const sheetsLoading = ref(false);
const uploadVisible = ref(false);
const supplierId = ref("");

const cardProductId = ref("");
const regions = ref([]);
const regionsLoading = ref(false);

async function loadSheets() {
  sheetsLoading.value = true;
  try {
    const { supplierId: supplier, ...paging } = sheetQuery;
    const res = await listTariffSheets(supplier ? { ...paging, supplierId: supplier.trim() } : paging);
    const page = pageOf(res && res.success ? res.result : null);
    sheets.value = page.records;
    sheetsTotal.value = page.total;
  } catch {
    // 请求层已提示错误
  } finally {
    sheetsLoading.value = false;
  }
}

function searchSheets() {
  sheetQuery.pageNumber = 1;
  loadSheets();
}

async function activate(row) {
  try {
    await ElMessageBox.confirm("启用后替换该供应商当前生效的资费表并重算成本，可能生成调价建议。", `启用资费表 #${row.id}`, { type: "warning" });
  } catch {
    return;
  }
  try {
    const res = await activateTariffSheet(row.id);
    if (res && res.success) {
      ElMessage.success(describeTariffImport(res.result).join("；"));
      loadSheets();
    }
  } catch {
    // 请求层已提示错误
  }
}

async function loadRegions() {
  if (!cardProductId.value) return;
  regionsLoading.value = true;
  try {
    const res = await listCardProductRegions(cardProductId.value.trim());
    regions.value = listOf(res && res.success ? res.result : null);
  } catch {
    // 请求层已提示错误
  } finally {
    regionsLoading.value = false;
  }
}

onMounted(loadSheets);
</script>

<style scoped>
.card-head { display: flex; align-items: center; justify-content: space-between; }
</style>
