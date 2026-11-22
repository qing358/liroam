<template>
  <div class="search">
    <el-card>
      <el-tabs v-model="tab" @tab-change="reload">
        <el-tab-pane label="提现审批" name="withdrawals">
          <el-form inline label-width="60px" class="search-form">
            <el-form-item label="状态">
              <el-select v-model="withdrawStatus" placeholder="全部" clearable style="width: 140px" @change="reload">
                <el-option v-for="s in WITHDRAWAL_STATUSES" :key="s.value" :label="s.label" :value="s.value" />
              </el-select>
            </el-form-item>
            <el-form-item><el-button type="primary" class="search-btn" @click="reload">刷新</el-button></el-form-item>
          </el-form>
          <el-table v-loading="loading" border :data="withdrawals" style="width: 100%">
            <el-table-column prop="id" label="编号" width="80" />
            <el-table-column label="分销员" min-width="140">
              <template #default="{ row }">{{ row.agentName || row.agentRef }}</template>
            </el-table-column>
            <el-table-column label="金额（元）" width="110" align="right">
              <template #default="{ row }">{{ fenToYuan(row.amountFen) }}</template>
            </el-table-column>
            <el-table-column label="收款" min-width="200">
              <template #default="{ row }">
                {{ revealed[row.id] || row.payeeMasked }}
                <el-button v-if="!revealed[row.id] && row.status !== 'REJECTED'" link type="primary" @click="reveal(row)">查看完整</el-button>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="90">
              <template #default="{ row }">{{ row.statusText || labelOf(WITHDRAWAL_STATUSES, row.status) }}</template>
            </el-table-column>
            <el-table-column label="凭证 / 备注" min-width="180" show-overflow-tooltip>
              <template #default="{ row }">{{ [row.voucher, row.note].filter(Boolean).join(" · ") }}</template>
            </el-table-column>
            <el-table-column prop="createdAt" label="申请时间" min-width="140" />
            <el-table-column prop="paidAt" label="打款时间" min-width="140" />
            <el-table-column label="操作" width="200" fixed="right" align="center">
              <template #default="{ row }">
                <el-button v-if="withdrawalActions(row).approve" link type="primary" @click="approve(row)">批准</el-button>
                <el-button v-if="withdrawalActions(row).paid" link type="success" @click="openPaid(row)">登记打款</el-button>
                <el-button v-if="withdrawalActions(row).reject" link type="danger" @click="reject(row)">驳回</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div class="liroam-hint mt_10">系统不自动打款：线下转账后在这里登记凭证号，余额才扣减。查看完整收款信息会记审计。</div>
        </el-tab-pane>

        <el-tab-pane label="分销员余额" name="balances">
          <el-table v-loading="loading" border :data="balances" style="width: 100%">
            <el-table-column label="分销员" min-width="160">
              <template #default="{ row }">{{ row.agentName || row.agentRef }} <span class="liroam-hint">{{ row.agentRef }}</span></template>
            </el-table-column>
            <el-table-column label="待结算" width="110" align="right">
              <template #default="{ row }">{{ fenToYuan(row.pendingFen) }}</template>
            </el-table-column>
            <el-table-column label="可提现" width="110" align="right">
              <template #default="{ row }"><span :class="{ 'liroam-negative': row.settleableFen < 0 }">{{ fenToYuan(row.settleableFen) }}</span></template>
            </el-table-column>
            <el-table-column label="提现中" width="110" align="right">
              <template #default="{ row }">{{ fenToYuan(row.inFlightFen) }}</template>
            </el-table-column>
            <el-table-column label="已提现" width="110" align="right">
              <template #default="{ row }">{{ fenToYuan(row.withdrawnFen) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="100" align="center">
              <template #default="{ row }"><el-button link type="primary" @click="showEntries(row.agentRef)">流水</el-button></template>
            </el-table-column>
          </el-table>
          <div class="liroam-hint mt_10">退款会按比例冲回佣金；已结算后退款可能让可提现为负，后续佣金自动抵扣。</div>
        </el-tab-pane>

        <el-tab-pane label="佣金流水" name="entries">
          <el-form inline label-width="70px" class="search-form">
            <el-form-item label="分销员"><el-input v-model="agentRef" placeholder="分销员 ID，留空看全部" clearable style="width: 200px" @change="reload" /></el-form-item>
            <el-form-item><el-button type="primary" class="search-btn" @click="reload">查询</el-button></el-form-item>
          </el-form>
          <el-table v-loading="loading" border :data="entries" style="width: 100%">
            <el-table-column prop="id" label="编号" width="80" />
            <el-table-column label="分销员" min-width="130">
              <template #default="{ row }">{{ row.agentName || row.agentRef }}</template>
            </el-table-column>
            <el-table-column label="级别" width="70">
              <template #default="{ row }">{{ row.level === 1 ? "一级" : row.level === 2 ? "二级" : "—" }}</template>
            </el-table-column>
            <el-table-column prop="typeText" label="类型" width="80" />
            <el-table-column label="金额（元）" width="110" align="right">
              <template #default="{ row }"><span :class="{ 'liroam-negative': row.amountFen < 0 }">{{ fenToYuan(row.amountFen) }}</span></template>
            </el-table-column>
            <el-table-column prop="statusText" label="状态" width="90" />
            <el-table-column label="来源" min-width="150" show-overflow-tooltip>
              <template #default="{ row }">{{ row.sourceType }} {{ row.sourceLineRef }}</template>
            </el-table-column>
            <el-table-column label="策略" width="120">
              <template #default="{ row }">{{ row.policyCode ? `${row.policyCode} v${row.policyVersion}` : "—" }}</template>
            </el-table-column>
            <el-table-column prop="settleableAt" label="可结算时间" min-width="140" />
            <el-table-column prop="createdAt" label="记账时间" min-width="140" />
          </el-table>
        </el-tab-pane>
      </el-tabs>
      <el-pagination v-model:current-page="page" :page-size="20" :total="total" layout="total, prev, pager, next" class="mt_10" @current-change="load" />
    </el-card>

    <el-dialog v-model="paidDialog" :title="`登记打款 #${paidRow && paidRow.id}`" width="480px">
      <el-form label-width="90px">
        <el-form-item label="金额">¥{{ paidRow && fenToYuan(paidRow.amountFen) }}</el-form-item>
        <el-form-item label="打款凭证" required><el-input v-model="paidForm.voucher" placeholder="转账流水号 / 凭证号" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="paidForm.note" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="paidDialog = false">取消</el-button>
        <el-button type="primary" @click="submitPaid">确认已打款</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import {
  approveWithdrawal, listAgentBalances, listCommissionEntries, listWithdrawals, markWithdrawalPaid, rejectWithdrawal, viewWithdrawalPayee,
} from "@/api/liroam";
import { WITHDRAW_METHODS, WITHDRAWAL_STATUSES, fenToYuan, labelOf, pageOf, withdrawalActions } from "@/utils/liroam";

defineOptions({ name: "liroam-commission-ledger" });

const tab = ref("withdrawals");
const loading = ref(false);
const page = ref(1);
const total = ref(0);
const withdrawStatus = ref("");
const agentRef = ref("");
const withdrawals = ref([]);
const balances = ref([]);
const entries = ref([]);
const revealed = reactive({});
const paidDialog = ref(false);
const paidRow = ref(null);
const paidForm = reactive({ voucher: "", note: "" });

const SOURCES = {
  withdrawals: { list: withdrawals, fetch: (p) => listWithdrawals({ ...p, status: withdrawStatus.value }) },
  balances: { list: balances, fetch: (p) => listAgentBalances(p) },
  entries: { list: entries, fetch: (p) => listCommissionEntries({ ...p, agentRef: agentRef.value }) },
};

async function load() {
  const source = SOURCES[tab.value];
  loading.value = true;
  try {
    const res = await source.fetch({ pageNumber: page.value, pageSize: 20 });
    if (res && res.success) {
      const { records, total: count } = pageOf(res.result);
      source.list.value = records;
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

function showEntries(ref) {
  agentRef.value = ref;
  tab.value = "entries";
  reload();
}

async function reveal(row) {
  try {
    const res = await viewWithdrawalPayee(row.id);
    if (res && res.success) {
      const p = res.result;
      revealed[row.id] = `${labelOf(WITHDRAW_METHODS, p.method)} ${p.name} ${p.account}`;
    }
  } catch {
    // 请求层已提示错误
  }
}

async function run(call, message) {
  try {
    const res = await call();
    if (res && res.success) {
      ElMessage.success(message);
      load();
      return true;
    }
  } catch {
    // 请求层已提示错误
  }
  return false;
}

async function approve(row) {
  try {
    await ElMessageBox.confirm(`批准 ${row.agentName || row.agentRef} 提现 ¥${fenToYuan(row.amountFen)}？批准后进入待打款。`, "批准提现", { type: "warning" });
  } catch {
    return;
  }
  run(() => approveWithdrawal(row.id), "已批准，待打款");
}

async function reject(row) {
  let note;
  try {
    const answer = await ElMessageBox.prompt("驳回后金额回到可提现余额。", `驳回提现 #${row.id}`, {
      inputPlaceholder: "驳回原因（必填）",
      inputValidator: (v) => !!(v && v.trim()) || "请填写原因",
    });
    note = answer.value.trim();
  } catch {
    return;
  }
  run(() => rejectWithdrawal(row.id, note), "已驳回");
}

function openPaid(row) {
  paidRow.value = row;
  Object.assign(paidForm, { voucher: "", note: "" });
  paidDialog.value = true;
}

async function submitPaid() {
  if (!paidForm.voucher.trim()) {
    ElMessage.warning("请填写打款凭证");
    return;
  }
  if (await run(() => markWithdrawalPaid(paidRow.value.id, paidForm.voucher.trim(), paidForm.note.trim()), "已登记打款")) {
    paidDialog.value = false;
  }
}

onMounted(load);
</script>

<style scoped>
.liroam-negative {
  color: #f56c6c;
}
</style>
