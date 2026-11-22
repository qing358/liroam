<template>
  <div class="search">
    <el-card>
      <el-form inline label-width="70px" class="search-form">
        <el-form-item label="类型">
          <el-select v-model="kind" placeholder="全部" clearable style="width: 160px" @change="reload">
            <el-option v-for="k in RECON_KINDS" :key="k.value" :label="k.label" :value="k.value" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" class="search-btn" @click="reload">刷新</el-button>
          <el-button @click="openUpload('PAYMENT_DAILY')">上传支付对账单</el-button>
          <el-button @click="openUpload('SUPPLIER_DAILY')">上传供应商账单</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card>
      <el-table v-loading="loading" border :data="runs" style="width: 100%" highlight-current-row @current-change="openRun">
        <el-table-column prop="id" label="编号" width="80" />
        <el-table-column label="类型" width="130">
          <template #default="{ row }">{{ labelOf(RECON_KINDS, row.kind) }}</template>
        </el-table-column>
        <el-table-column prop="period" label="账期" width="120" />
        <el-table-column label="范围" width="130">
          <template #default="{ row }">{{ reconScopeText(row) }}</template>
        </el-table-column>
        <el-table-column prop="matched" label="平账" width="80" align="right" />
        <el-table-column label="差异 / 未处理" width="120" align="right">
          <template #default="{ row }">
            {{ row.diffCount }} / <span :class="{ 'liroam-open': row.openDiffs > 0 }">{{ row.openDiffs }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="createdBy" label="操作人" width="120" />
        <el-table-column prop="createdAt" label="对账时间" min-width="150" />
      </el-table>
      <el-pagination v-model:current-page="page" :page-size="20" :total="total" layout="total, prev, pager, next" class="mt_10" @current-change="load" />
      <div class="liroam-hint mt_10">每天对一次昨天：支付对账单列 order_sn,trade_no,amount（元）；供应商账单列 iccid,item,amount_hkd（真实格式待供应商确认）；渠道日对账由系统每天自动拉渠道结算单。点一行看差异。</div>
    </el-card>

    <el-card v-if="current">
      <div class="mb_10"><b>#{{ current.id }} {{ labelOf(RECON_KINDS, current.kind) }} {{ current.period }} 差异</b></div>
      <el-table border :data="diffs" style="width: 100%">
        <el-table-column label="类型" width="100">
          <template #default="{ row }">{{ labelOf(RECON_DIFF_KINDS, row.diffKind) }}</template>
        </el-table-column>
        <el-table-column label="说明" min-width="320">
          <template #default="{ row }">{{ reconDiffText(row) }}</template>
        </el-table-column>
        <el-table-column label="处理" width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <template v-if="row.status === 'OPEN'">待处理</template>
            <template v-else>{{ row.status === "RESOLVED" ? "已处理" : "已忽略" }}：{{ row.resolution }}（{{ row.resolver }}）</template>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="130" align="center">
          <template #default="{ row }">
            <template v-if="row.status === 'OPEN'">
              <el-button link type="primary" @click="resolve(row, 'RESOLVED')">已处理</el-button>
              <el-button link @click="resolve(row, 'IGNORED')">忽略</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="uploadDialog" :title="upload.kind === 'PAYMENT_DAILY' ? '支付日对账' : '供应商日对账'" width="520px">
      <el-form label-width="90px">
        <el-form-item label="账期" required>
          <el-date-picker v-model="upload.day" type="date" value-format="YYYY-MM-DD" placeholder="哪一天的账" />
        </el-form-item>
        <el-form-item v-if="upload.kind === 'SUPPLIER_DAILY'" label="供应商 ID" required>
          <el-input v-model="upload.supplierId" />
        </el-form-item>
        <el-form-item label="对账单" required>
          <input type="file" accept=".csv,text/csv" @change="pickFile" />
          <div class="liroam-hint">{{ upload.fileName || "CSV，首行为表头" }}</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="uploadDialog = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitUpload">开始对账</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { listReconDiffs, listReconRuns, resolveReconDiff, runPaymentRecon, runSupplierRecon } from "@/api/liroam";
import { RECON_DIFF_KINDS, RECON_KINDS, labelOf, pageOf, reconDiffText, reconScopeText } from "@/utils/liroam";

defineOptions({ name: "liroam-reconciliation" });

const kind = ref("");
const loading = ref(false);
const page = ref(1);
const total = ref(0);
const runs = ref([]);
const current = ref(null);
const diffs = ref([]);
const uploadDialog = ref(false);
const submitting = ref(false);
const upload = reactive({ kind: "", day: "", supplierId: "", csv: "", fileName: "" });

async function load() {
  loading.value = true;
  try {
    const res = await listReconRuns({ kind: kind.value, pageNumber: page.value, pageSize: 20 });
    if (res && res.success) {
      const { records, total: count } = pageOf(res.result);
      runs.value = records;
      total.value = count;
    }
  } catch {
    // 请求层已提示错误
  } finally {
    loading.value = false;
  }
}

function reload() {
  page.value = 1;
  load();
}

async function openRun(row) {
  if (!row) return;
  current.value = row;
  try {
    const res = await listReconDiffs(row.id);
    if (res && res.success) diffs.value = res.result || [];
  } catch {
    // 请求层已提示错误
  }
}

function yesterday() {
  const d = new Date(Date.now() - 86400000);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

function openUpload(k) {
  Object.assign(upload, { kind: k, day: yesterday(), supplierId: "", csv: "", fileName: "" });
  uploadDialog.value = true;
}

function pickFile(event) {
  const file = event.target.files && event.target.files[0];
  if (!file) return;
  const reader = new FileReader();
  reader.onload = () => {
    upload.csv = String(reader.result || "");
    upload.fileName = file.name;
  };
  reader.readAsText(file, "utf-8");
}

async function submitUpload() {
  if (!upload.day || !upload.csv || (upload.kind === "SUPPLIER_DAILY" && !upload.supplierId)) {
    ElMessage.warning("请填写账期并选择对账单");
    return;
  }
  submitting.value = true;
  try {
    const res = upload.kind === "PAYMENT_DAILY"
      ? await runPaymentRecon(upload.day, upload.csv)
      : await runSupplierRecon(Number(upload.supplierId), upload.day, upload.csv);
    if (res && res.success) {
      const run = res.result;
      ElMessage.success(`对账完成：平账 ${run.matched}，差异 ${run.diffCount}`);
      uploadDialog.value = false;
      await load();
      openRun(run);
    }
  } catch {
    // 请求层已提示错误
  } finally {
    submitting.value = false;
  }
}

async function resolve(row, status) {
  let resolution;
  try {
    const answer = await ElMessageBox.prompt(reconDiffText(row), status === "RESOLVED" ? "标记已处理" : "忽略差异", {
      inputPlaceholder: "处理说明（必填）",
      inputValidator: (v) => !!(v && v.trim()) || "请填写说明",
    });
    resolution = answer.value.trim();
  } catch {
    return;
  }
  try {
    const res = await resolveReconDiff(row.id, status, resolution);
    if (res && res.success) {
      ElMessage.success("已记录");
      openRun(current.value);
      load();
    }
  } catch {
    // 请求层已提示错误
  }
}

onMounted(load);
</script>

<style scoped>
.liroam-open {
  color: #e6a23c;
  font-weight: 600;
}
</style>
