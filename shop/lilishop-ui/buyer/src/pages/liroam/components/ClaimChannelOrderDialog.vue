<template>
  <el-dialog :model-value="modelValue" title="认领渠道订单" width="460px" @update:model-value="emit('update:modelValue', $event)" @open="reset">
    <p class="claim-tip">在其它平台（如电商旗舰店）买的旅行卡，填写该平台的订单号与下单时的收货手机号，认领后可在这里查看用量与激活码。</p>
    <el-form label-width="96px" @submit.prevent>
      <el-form-item label="渠道订单号">
        <el-input v-model="orderNo" maxlength="64" placeholder="平台订单详情里的订单号" clearable />
      </el-form-item>
      <el-form-item label="收货手机号">
        <el-input v-model="phone" maxlength="20" placeholder="须与当前账号绑定的手机号一致" clearable />
      </el-form-item>
    </el-form>
    <p v-if="inputError && touched" class="claim-error">{{ inputError }}</p>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">认领</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, ref } from "vue";
import { Message } from "@/utils/message";
import { claimChannelOrder } from "@/api/liroam";
import { claimInputError, failureMessage } from "@/utils/liroam";

defineOptions({ name: "LiroamClaimChannelOrderDialog" });

defineProps({
  modelValue: { type: Boolean, default: false },
});
const emit = defineEmits(["update:modelValue", "done"]);

const orderNo = ref("");
const phone = ref("");
const touched = ref(false);
const submitting = ref(false);
const inputError = computed(() => claimInputError(orderNo.value, phone.value));

function reset() {
  orderNo.value = "";
  phone.value = "";
  touched.value = false;
}

async function submit() {
  touched.value = true;
  if (inputError.value) return;
  submitting.value = true;
  try {
    const res = await claimChannelOrder(orderNo.value.trim(), phone.value.trim());
    if (!res || !res.success) {
      Message.warning(failureMessage(res, "认领失败"));
      return;
    }
    Message.success(`认领成功，${res.result?.cardCount || 0} 张卡已加入「我的卡」`);
    emit("update:modelValue", false);
    emit("done", res.result);
  } catch {
    // 请求层已提示网络错误
  } finally {
    submitting.value = false;
  }
}
</script>

<style scoped lang="scss">
.claim-tip { margin: 0 0 14px; font-size: 13px; color: #606266; line-height: 1.6; }
.claim-error { margin: 0; font-size: 12px; color: #f56c6c; }
</style>
