<template>
  <el-dialog :model-value="modelValue" title="给这张卡加流量包" width="520px" @update:model-value="emit('update:modelValue', $event)" @open="load">
    <div v-if="card" class="topup-card">为 <strong>{{ card.productName }}</strong><span v-if="card.iccidLast4">（尾号 {{ card.iccidLast4 }}）</span> 加包</div>
    <el-skeleton v-if="loading" :rows="3" animated />
    <div v-else-if="errorText" class="topup-empty">{{ errorText }}</div>
    <div v-else-if="options.length === 0" class="topup-empty">这张卡暂时没有可加的流量包</div>
    <el-radio-group v-else v-model="selectedSkuId" class="topup-options">
      <el-radio v-for="item in options" :key="item.skuId" :value="item.skuId" border class="topup-option">
        <span class="topup-name">{{ item.name }}</span>
        <span class="topup-price">￥{{ fenToYuan(item.priceFen) }}</span>
      </el-radio>
    </el-radio-group>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :disabled="!selectedSkuId" :loading="submitting" @click="submit">立即购买</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref } from "vue";
import { useRouter } from "vue-router";
import { Message } from "@/utils/message";
import { addCartGoods } from "@/api/cart.js";
import { getTopUpOptions, openTopUpIntent } from "@/api/liroam";
import { fenToYuan, listOf, failureMessage, checkoutCartType } from "@/utils/liroam";

defineOptions({ name: "LiroamTopUpDialog" });

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  card: { type: Object, default: null },
});
const emit = defineEmits(["update:modelValue"]);
const router = useRouter();

const loading = ref(false);
const submitting = ref(false);
const errorText = ref("");
const options = ref([]);
const selectedSkuId = ref("");

async function load() {
  if (!props.card) return;
  loading.value = true;
  errorText.value = "";
  options.value = [];
  selectedSkuId.value = "";
  try {
    const res = await getTopUpOptions(props.card.id);
    if (res && res.success) {
      options.value = listOf(res.result).filter((item) => item && item.skuId);
      if (options.value.length === 1) selectedSkuId.value = options.value[0].skuId;
    } else {
      errorText.value = failureMessage(res, "暂时无法获取可加的流量包");
    }
  } catch {
    errorText.value = "暂时无法获取可加的流量包，请稍后重试";
  } finally {
    loading.value = false;
  }
}

/** 与商品详情「立即购买」同一路径；加包 SKU 是旅行卡，走普通订单（见 checkoutCartType）。 */
async function buyNow(skuId) {
  const cartType = checkoutCartType({ travelCard: true });
  const res = await addCartGoods({ num: 1, skuId, cartType });
  if (!res || !res.success) {
    Message.warning(failureMessage(res, "加入结算失败"));
    return;
  }
  router.push({ path: "/pay", query: { way: cartType } });
}

async function submit() {
  submitting.value = true;
  try {
    const res = await openTopUpIntent(props.card.id, selectedSkuId.value);
    if (!res || !res.success) {
      Message.warning(failureMessage(res, "暂时无法为这张卡加包"));
      return;
    }
    await buyNow(res.result?.skuId || selectedSkuId.value);
  } catch {
    // 请求层已提示网络错误
  } finally {
    submitting.value = false;
  }
}
</script>

<style scoped lang="scss">
.topup-card { margin-bottom: 16px; color: #606266; strong { color: #303133; } }
.topup-options { display: flex; flex-direction: column; align-items: stretch; gap: 10px; width: 100%; }
.topup-option {
  width: 100%;
  height: auto;
  margin-right: 0;
  padding: 12px 16px;
  :deep(.el-radio__label) { display: flex; flex: 1; justify-content: space-between; }
}
.topup-name { color: #303133; }
.topup-price { color: var(--theme-color, #f31947); font-weight: 600; }
.topup-empty { padding: 32px 0; text-align: center; color: #909399; }
</style>
