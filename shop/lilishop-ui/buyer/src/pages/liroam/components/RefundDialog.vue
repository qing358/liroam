<template>
  <el-dialog :model-value="modelValue" title="申请退款" width="480px" @update:model-value="emit('update:modelValue', $event)" @open="reason = ''">
    <div v-if="card" class="refund-card">
      {{ card.productName }}<span v-if="card.iccidLast4">（尾号 {{ card.iccidLast4 }}）</span> · {{ card.statusText }}
    </div>
    <el-input v-model="reason" type="textarea" :rows="4" maxlength="200" show-word-limit placeholder="请填写退款原因" />
    <p class="refund-tip">提交后这张卡进入售后处理，期间暂停查看激活码与加包；是否可退及退款方式以审核结果为准。</p>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :disabled="!reason.trim()" :loading="submitting" @click="submit">提交申请</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref } from "vue";
import { Message } from "@/utils/message";
import { applyCardRefund } from "@/api/liroam";
import { failureMessage } from "@/utils/liroam";

defineOptions({ name: "LiroamRefundDialog" });

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  card: { type: Object, default: null },
});
const emit = defineEmits(["update:modelValue", "done"]);

const reason = ref("");
const submitting = ref(false);

async function submit() {
  submitting.value = true;
  try {
    const res = await applyCardRefund(props.card.id, reason.value.trim());
    if (!res || !res.success) {
      Message.warning(failureMessage(res, "退款申请提交失败"));
      return;
    }
    const outcome = [res.result?.statusText, res.result?.decisionText].filter(Boolean).join("，");
    Message.success(outcome ? `退款申请已提交：${outcome}` : "退款申请已提交");
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
.refund-card { margin-bottom: 12px; color: #606266; }
.refund-tip { margin: 12px 0 0; font-size: 12px; color: #909399; line-height: 1.6; }
</style>
