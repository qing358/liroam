<template>
  <div class="wrapper liroam-commission">
    <UserCenterLayout>
      <div class="commission-heading">
        <div><h2>我的佣金</h2><p>分销成交的佣金、结算进度与提现记录</p></div>
        <el-button :loading="loading" @click="load">刷新</el-button>
      </div>

      <el-skeleton v-if="loading && !summary" :rows="5" animated />
      <el-empty v-else-if="summary && !summary.agent" description="你还不是分销员；成为分销员后，好友通过你的分享下单即可获得佣金" :image-size="100" />
      <template v-else-if="summary">
        <div class="commission-figures">
          <div class="commission-figure is-main">
            <label>可提现</label><b>¥{{ fenToYuan(summary.availableFen) }}</b>
            <el-button type="primary" size="small" :disabled="summary.availableFen < summary.minWithdrawFen" @click="openWithdraw">申请提现</el-button>
          </div>
          <div class="commission-figure"><label>待结算</label><b>¥{{ fenToYuan(summary.pendingFen) }}</b></div>
          <div class="commission-figure"><label>提现中</label><b>¥{{ fenToYuan(summary.inFlightFen) }}</b></div>
          <div class="commission-figure"><label>已提现</label><b>¥{{ fenToYuan(summary.withdrawnFen) }}</b></div>
        </div>
        <p class="commission-tip">
          佣金在卡交付后记入「待结算」，到结算日转为可提现；订单退款会相应扣回佣金。最低提现 ¥{{ fenToYuan(summary.minWithdrawFen) }}{{ summary.approvalRequired ? "，提交后需平台审核" : "" }}，由平台线下打款。
        </p>

        <el-tabs v-model="tab">
          <el-tab-pane label="佣金明细" name="entries">
            <el-table :data="entries" style="width: 100%" empty-text="还没有佣金记录">
              <el-table-column prop="createdAt" label="时间" min-width="140" />
              <el-table-column label="类型" width="110">
                <template #default="{ row }">{{ row.typeText }}<span v-if="row.level === 2" class="commission-sub">（邀请）</span></template>
              </el-table-column>
              <el-table-column label="金额" width="110" align="right">
                <template #default="{ row }"><span :class="row.amountFen < 0 ? 'is-minus' : 'is-plus'">{{ fenToYuan(row.amountFen) }}</span></template>
              </el-table-column>
              <el-table-column prop="statusText" label="状态" width="90" />
              <el-table-column prop="settleableAt" label="可结算时间" min-width="140" />
            </el-table>
            <el-pagination v-if="entryTotal > 20" v-model:current-page="entryPage" :page-size="20" :total="entryTotal" layout="prev, pager, next"
              class="commission-pager" @current-change="loadEntries" />
          </el-tab-pane>
          <el-tab-pane label="提现记录" name="withdrawals">
            <el-table :data="withdrawals" style="width: 100%" empty-text="还没有提现记录">
              <el-table-column prop="createdAt" label="申请时间" min-width="140" />
              <el-table-column label="金额" width="110" align="right">
                <template #default="{ row }">{{ fenToYuan(row.amountFen) }}</template>
              </el-table-column>
              <el-table-column prop="payeeMasked" label="收款" min-width="180" />
              <el-table-column prop="statusText" label="状态" width="90" />
              <el-table-column label="说明" min-width="140" show-overflow-tooltip>
                <template #default="{ row }">{{ row.status === "PAID" ? `凭证 ${row.voucher || ""}` : row.note || "" }}</template>
              </el-table-column>
            </el-table>
          </el-tab-pane>
        </el-tabs>
      </template>
    </UserCenterLayout>

    <el-dialog v-model="withdrawVisible" title="申请提现" width="460px">
      <el-form label-width="90px">
        <el-form-item label="提现金额">
          <el-input v-model="form.amountYuan" placeholder="元"><template #append>可提 ¥{{ summary && fenToYuan(summary.availableFen) }}</template></el-input>
        </el-form-item>
        <el-form-item label="收款方式">
          <el-radio-group v-model="form.method">
            <el-radio v-for="m in METHODS" :key="m.value" :value="m.value">{{ m.label }}</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="收款人"><el-input v-model="form.name" maxlength="32" placeholder="真实姓名" /></el-form-item>
        <el-form-item label="收款账号"><el-input v-model="form.account" maxlength="64" :placeholder="form.method === 'BANK' ? '银行卡号（含开户行）' : '账号 / 手机号'" /></el-form-item>
      </el-form>
      <p class="commission-tip">收款信息加密保存，仅用于本次打款。</p>
      <template #footer>
        <el-button @click="withdrawVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitWithdraw">提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from "vue";
import { Message } from "@/utils/message";
import { applyWithdrawal, getAgentSummary, listAgentEntries, listMyWithdrawals } from "@/api/liroam";
import { failureMessage, fenToYuan, listOf, withdrawFormError, yuanToFen } from "@/utils/liroam";

defineOptions({ name: "LiroamCommission" });

const METHODS = [
  { value: "ALIPAY", label: "支付宝" },
  { value: "WECHAT", label: "微信" },
  { value: "BANK", label: "银行卡" },
];

const loading = ref(false);
const summary = ref(null);
const tab = ref("entries");
const entries = ref([]);
const entryPage = ref(1);
const entryTotal = ref(0);
const withdrawals = ref([]);
const withdrawVisible = ref(false);
const submitting = ref(false);
const form = reactive({ amountYuan: "", method: "ALIPAY", name: "", account: "" });

async function loadEntries() {
  const res = await listAgentEntries({ pageNumber: entryPage.value, pageSize: 20 });
  if (res && res.success) {
    entries.value = listOf(res.result);
    entryTotal.value = Number(res.result?.total || 0);
  }
}

async function load() {
  loading.value = true;
  try {
    const res = await getAgentSummary();
    if (!res || !res.success) {
      Message.warning(failureMessage(res, "佣金加载失败"));
      return;
    }
    summary.value = res.result;
    if (!summary.value.agent) return;
    const [, w] = await Promise.all([loadEntries(), listMyWithdrawals()]);
    if (w && w.success) withdrawals.value = listOf(w.result);
  } catch {
    // 请求层已提示网络错误
  } finally {
    loading.value = false;
  }
}

function openWithdraw() {
  Object.assign(form, { amountYuan: fenToYuan(summary.value.availableFen), account: "" });
  withdrawVisible.value = true;
}

async function submitWithdraw() {
  const error = withdrawFormError(form, summary.value);
  if (error) {
    Message.warning(error);
    return;
  }
  submitting.value = true;
  try {
    const res = await applyWithdrawal({ amountFen: yuanToFen(form.amountYuan), method: form.method, name: form.name.trim(), account: form.account.trim() });
    if (!res || !res.success) {
      Message.warning(failureMessage(res, "提现申请失败"));
      return;
    }
    Message.success(res.result?.status === "APPLIED" ? "已提交，等待平台审核" : "已提交，等待平台打款");
    withdrawVisible.value = false;
    tab.value = "withdrawals";
    load();
  } catch {
    // 请求层已提示网络错误
  } finally {
    submitting.value = false;
  }
}

onMounted(load);
</script>

<style scoped lang="scss">
.liroam-commission {
  .commission-heading {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    padding: 10px 4px 22px;
    h2 { margin: 0 0 8px; font-size: 22px; font-weight: 600; color: #252833; }
    p { margin: 0; font-size: 13px; color: #909399; }
  }
  .commission-figures { display: grid; grid-template-columns: 1.6fr repeat(3, 1fr); gap: 12px; }
  .commission-figure {
    display: flex;
    flex-direction: column;
    gap: 6px;
    padding: 16px 18px;
    border: 1px solid #ebeef5;
    border-radius: 8px;
    label { font-size: 12px; color: #909399; }
    b { font-size: 20px; color: #252833; }
    &.is-main { background: linear-gradient(120deg, #f2f7ff, #f9fbff); align-items: flex-start; }
    &.is-main b { font-size: 26px; color: #3a6fd8; }
  }
  .commission-tip { margin: 12px 0 8px; font-size: 12px; line-height: 1.6; color: #909399; }
  .commission-sub { font-size: 12px; color: #909399; }
  .commission-pager { margin-top: 12px; justify-content: flex-end; }
  .is-plus { color: #3a6fd8; }
  .is-minus { color: #f56c6c; }
}
</style>
