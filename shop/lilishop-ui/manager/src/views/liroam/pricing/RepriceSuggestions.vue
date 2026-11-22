<template>
  <div class="search">
    <el-card>
      <el-form inline class="search-form" @submit.prevent="search">
        <el-form-item label="状态">
          <el-select v-model="query.status" placeholder="全部" clearable style="width: 160px" @change="search">
            <el-option v-for="s in REPRICE_STATUSES" :key="s.value" :label="s.label" :value="s.value" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" class="search-btn" @click="search">刷新</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card>
      <el-table v-loading="loading" border :data="data" style="width: 100%">
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
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { actOnRepriceSuggestion, listRepriceSuggestions } from "@/api/liroam";
import { REPRICE_STATUSES, REPRICE_TRIGGERS, fenToYuan, labelOf, pageOf, rateToPercent } from "@/utils/liroam";

defineOptions({ name: "liroam-reprice-suggestions" });

const query = reactive({ status: "PENDING", pageNumber: 1, pageSize: 20 });
const loading = ref(false);
const data = ref([]);
const total = ref(0);

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

onMounted(load);
</script>

<style scoped>
.sub { font-size: 12px; color: #909399; }
</style>
