<template>
  <div class="search">
    <el-card>
      <el-form inline label-width="60px" class="search-form">
        <el-form-item label="渠道">
          <el-select v-model="filter.channelId" placeholder="全部" clearable style="width: 160px" @change="search">
            <el-option v-for="c in channels" :key="c.id" :label="`${c.code} ${c.name}`" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="filter.status" placeholder="全部" clearable style="width: 140px" @change="search">
            <el-option v-for="s in ORDER_STATUSES" :key="s.value" :label="s.label" :value="s.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="单号">
          <el-input v-model="filter.keyword" placeholder="渠道订单号" clearable style="width: 180px" @keyup.enter="search" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" class="search-btn" @click="search">查询</el-button>
        </el-form-item>
      </el-form>
      <div class="liroam-channel-ops">
        <el-button type="primary" :disabled="!filter.channelId" :loading="pulling" @click="pullNow">立即拉单</el-button>
        <el-date-picker v-model="reconDay" type="date" value-format="YYYY-MM-DD" placeholder="对账日期" style="width: 150px" />
        <el-button :disabled="!filter.channelId || !reconDay" :loading="reconciling" @click="reconcile">跑日对账</el-button>
        <span class="liroam-channel-tip">先选渠道；对账按北京时间自然日比对渠道结算单与本站渠道单、履约结果。</span>
      </div>
    </el-card>

    <el-card>
      <el-table v-loading="loading" border :data="data" row-key="id" style="width: 100%">
        <el-table-column type="expand">
          <template #default="{ row }">
            <el-table :data="row.lines" size="small" border class="liroam-channel-lines">
              <el-table-column prop="externalLineNo" label="行号" width="70" />
              <el-table-column prop="externalSkuId" label="渠道 SKU" min-width="140" />
              <el-table-column label="本站 SKU" min-width="160">
                <template #default="{ row: line }">{{ line.liGoodsSkuId || "未映射" }}</template>
              </el-table-column>
              <el-table-column prop="qty" label="数量" width="70" />
              <el-table-column label="实付（元）" width="100" align="right">
                <template #default="{ row: line }">{{ fenToYuan(line.paidFen) }}</template>
              </el-table-column>
              <el-table-column label="履约" width="110">
                <template #default="{ row: line }">{{ line.fulfillmentStatus || "未进履约" }}</template>
              </el-table-column>
              <el-table-column label="回传" width="150">
                <template #default="{ row: line }">
                  <el-tag :type="writebackTagType(line.writebackStatus)" size="small">{{ line.writebackText }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="回传错误" min-width="180" show-overflow-tooltip>
                <template #default="{ row: line }">{{ line.writebackError || "" }}</template>
              </el-table-column>
              <el-table-column label="操作" width="100" align="center">
                <template #default="{ row: line }">
                  <el-button v-if="line.writebackStatus === 'MANUAL'" link type="primary" @click="retryWriteBack(line)">重试回传</el-button>
                </template>
              </el-table-column>
            </el-table>
          </template>
        </el-table-column>
        <el-table-column prop="externalOrderId" label="渠道单号" min-width="150" show-overflow-tooltip />
        <el-table-column prop="channelCode" label="渠道" width="80" />
        <el-table-column prop="statusText" label="状态" width="100" />
        <el-table-column label="实付（元）" width="100" align="right">
          <template #default="{ row }">{{ fenToYuan(row.paidFen) }}</template>
        </el-table-column>
        <el-table-column label="回传" width="90">
          <template #default="{ row }">{{ writebackSummary(row) }}</template>
        </el-table-column>
        <el-table-column label="对账" width="90">
          <template #default="{ row }">
            <el-tag :type="reconTagType(row.reconStatus)" size="small">{{ row.reconText }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="买家认领" width="90">
          <template #default="{ row }">{{ row.claimed ? "已认领" : "—" }}</template>
        </el-table-column>
        <el-table-column prop="orderedText" label="下单时间" width="140" />
        <el-table-column label="异常" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ row.lastError || "" }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right" align="center">
          <template #default="{ row }">
            <el-button v-if="channelOrderActions(row).replay" link type="primary" @click="replay(row)">重放</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-model:current-page="page.current"
        v-model:page-size="page.size"
        class="liroam-channel-pager"
        :total="page.total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next"
        @current-change="load"
        @size-change="search"
      />
    </el-card>

    <el-card>
      <el-collapse @change="onDevToggle">
        <el-collapse-item title="模拟渠道（仅开发 / 测试环境；资质未到前用于演示）" name="dev">
          <el-form inline class="search-form">
            <el-form-item label="渠道 SKU"><el-input v-model="mock.skuId" style="width: 150px" /></el-form-item>
            <el-form-item label="数量"><el-input-number v-model="mock.qty" :min="1" :max="10" style="width: 110px" /></el-form-item>
            <el-form-item label="单价（元）"><el-input v-model="mock.unitPriceYuan" style="width: 100px" /></el-form-item>
            <el-form-item label="收货手机号"><el-input v-model="mock.mobile" style="width: 150px" /></el-form-item>
            <el-form-item><el-button type="primary" @click="createMockOrder">模拟下单</el-button></el-form-item>
          </el-form>
          <el-form inline class="search-form">
            <el-form-item>
              <el-button @click="failWriteBacks(false)">下次回传超时</el-button>
              <el-button @click="failWriteBacks(true)">下次回传被拒</el-button>
            </el-form-item>
          </el-form>
          <el-table :data="mockOrders" size="small" border>
            <el-table-column prop="orderId" label="模拟单号" min-width="140" />
            <el-table-column prop="orderState" label="渠道状态" min-width="170" />
            <el-table-column prop="orderStartTime" label="下单时间" width="160" />
            <el-table-column prop="skuId" label="SKU" min-width="120" />
            <el-table-column prop="itemTotal" label="数量" width="60" />
            <el-table-column prop="orderPayment" label="实付" width="80" />
            <el-table-column label="结算单金额" width="100">
              <template #default="{ row }">{{ row.statementPayment || "不在结算单" }}</template>
            </el-table-column>
            <el-table-column prop="mobile" label="手机号" width="120" />
            <el-table-column label="回传" width="70">
              <template #default="{ row }">{{ (row.writeBacks || []).length }}</template>
            </el-table-column>
            <el-table-column label="制造对账差异" width="180" align="center">
              <template #default="{ row }">
                <el-button link type="primary" @click="adjustStatement(row)">改金额</el-button>
                <el-button link type="danger" @click="dropStatement(row)">移出结算单</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-collapse-item>
      </el-collapse>
    </el-card>

    <el-dialog v-model="diffVisible" title="对账结果" width="760px">
      <p>渠道结算 {{ recon.theirCount }} 单，本站 {{ recon.ourCount }} 单，差异 {{ recon.diffCount }} 条（每条已开人工待办）。</p>
      <el-table :data="diffs" size="small" border>
        <el-table-column prop="diffText" label="差异" width="110" />
        <el-table-column prop="ourRef" label="本站单号" min-width="140" />
        <el-table-column prop="theirRef" label="渠道单号" min-width="140" />
        <el-table-column label="本站金额" width="100" align="right">
          <template #default="{ row }">{{ fenToYuan(row.ourAmountFen) }}</template>
        </el-table-column>
        <el-table-column label="渠道金额" width="100" align="right">
          <template #default="{ row }">{{ fenToYuan(row.theirAmountFen) }}</template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import {
  adjustMockStatement,
  createMockChannelOrder,
  dropMockStatement,
  failMockWriteBacks,
  listChannelOrders,
  listChannelReconDiffs,
  listChannels,
  listMockChannelOrders,
  pullChannelNow,
  replayChannelOrder,
  retryChannelWriteBack,
  runChannelRecon,
} from "@/api/liroam";
import { channelOrderActions, fenToYuan, listOf, pageOf, reconTagType, writebackTagType } from "@/utils/liroam";

defineOptions({ name: "liroam-channel-orders" });

const ORDER_STATUSES = [
  { value: "RECEIVED", label: "已接收" },
  { value: "FULFILLING", label: "履约中" },
  { value: "DONE", label: "已完成" },
  { value: "MANUAL", label: "待人工处理" },
];

const channels = ref([]);
const filter = reactive({ channelId: null, status: "", keyword: "" });
const page = reactive({ current: 1, size: 20, total: 0 });
const loading = ref(false);
const data = ref([]);
const pulling = ref(false);
const reconciling = ref(false);
const reconDay = ref("");
const diffVisible = ref(false);
const diffs = ref([]);
const recon = reactive({ theirCount: 0, ourCount: 0, diffCount: 0 });
const mock = reactive({ skuId: "JD-SKU-1001", qty: 1, unitPriceYuan: "49.00", mobile: "13800138000" });
const mockOrders = ref([]);

async function loadChannels() {
  try {
    const res = await listChannels();
    channels.value = listOf(res && res.success ? res.result : null);
    if (!filter.channelId && channels.value.length === 1) filter.channelId = channels.value[0].id;
  } catch {
    // 请求层已提示错误
  }
}

function search() {
  page.current = 1;
  load();
}

async function load() {
  loading.value = true;
  try {
    const res = await listChannelOrders({
      channelId: filter.channelId || undefined,
      status: filter.status || undefined,
      keyword: filter.keyword.trim() || undefined,
      pageNumber: page.current,
      pageSize: page.size,
    });
    const { records, total } = pageOf(res && res.success ? res.result : null);
    data.value = records;
    page.total = total;
  } catch {
    // 请求层已提示错误
  } finally {
    loading.value = false;
  }
}

function writebackSummary(row) {
  const lines = row.lines || [];
  const done = lines.filter((l) => l.writebackStatus === "DONE").length;
  return `${done}/${lines.length}`;
}

async function pullNow() {
  pulling.value = true;
  try {
    const res = await pullChannelNow(filter.channelId);
    if (res && res.success) {
      const r = res.result || {};
      ElMessage.success(`拉到 ${r.pulled || 0} 单，新入 ${r.created || 0} 单，转人工 ${r.manual || 0} 单`);
      load();
    }
  } catch {
    // 请求层已提示错误
  } finally {
    pulling.value = false;
  }
}

async function reconcile() {
  reconciling.value = true;
  try {
    const res = await runChannelRecon(filter.channelId, reconDay.value);
    if (res && res.success) {
      Object.assign(recon, res.result || {});
      const d = await listChannelReconDiffs(res.result.runId);
      diffs.value = listOf(d && d.success ? d.result : null);
      diffVisible.value = true;
      load();
    }
  } catch {
    // 请求层已提示错误
  } finally {
    reconciling.value = false;
  }
}

async function replay(row) {
  try {
    const res = await replayChannelOrder(row.id);
    if (res && res.success) {
      ElMessage.success(`渠道单 ${row.externalOrderId} 已重放：${res.result.statusText}`);
      load();
    }
  } catch {
    // 请求层已提示错误
  }
}

async function retryWriteBack(line) {
  try {
    const res = await retryChannelWriteBack(line.id);
    if (res && res.success) {
      ElMessage.success("已重新排队回传，worker 下一轮会回传");
      load();
    }
  } catch {
    // 请求层已提示错误
  }
}

async function loadMockOrders() {
  try {
    const res = await listMockChannelOrders();
    mockOrders.value = listOf(res && res.success ? res.result : null);
  } catch {
    // 非 dev 进程无此接口
  }
}

function onDevToggle(names) {
  if (names.includes("dev")) loadMockOrders();
}

async function createMockOrder() {
  try {
    const res = await createMockChannelOrder({ ...mock });
    if (res && res.success) {
      ElMessage.success(`模拟单 ${res.result.orderId} 已生成，点「立即拉单」入单`);
      loadMockOrders();
    }
  } catch {
    // 请求层已提示错误
  }
}

async function failWriteBacks(rejected) {
  try {
    const res = await failMockWriteBacks(1, rejected);
    if (res && res.success) ElMessage.success(rejected ? "下一次回传将被渠道拒绝" : "下一次回传将超时（可重试）");
  } catch {
    // 请求层已提示错误
  }
}

async function adjustStatement(row) {
  let paid;
  try {
    const answer = await ElMessageBox.prompt(`结算单上 ${row.orderId} 记成多少元？`, "制造金额不符", {
      inputValue: row.orderPayment,
      confirmButtonText: "保存",
      cancelButtonText: "取消",
    });
    paid = (answer.value || "").trim();
  } catch {
    return;
  }
  try {
    const res = await adjustMockStatement(row.orderId, paid);
    if (res && res.success) mockOrders.value = listOf(res.result);
  } catch {
    // 请求层已提示错误
  }
}

async function dropStatement(row) {
  try {
    const res = await dropMockStatement(row.orderId);
    if (res && res.success) mockOrders.value = listOf(res.result);
  } catch {
    // 请求层已提示错误
  }
}

onMounted(async () => {
  await loadChannels();
  load();
});
</script>

<style scoped>
.liroam-channel-ops {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}
.liroam-channel-tip {
  color: #909399;
  font-size: 12px;
}
.liroam-channel-lines {
  margin: 4px 16px;
  width: calc(100% - 32px);
}
.liroam-channel-pager {
  margin-top: 12px;
  justify-content: flex-end;
}
</style>
