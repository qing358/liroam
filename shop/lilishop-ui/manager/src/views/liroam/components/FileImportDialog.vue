<template>
  <el-dialog :model-value="modelValue" :title="title" width="520px" @update:model-value="emit('update:modelValue', $event)" @open="reset">
    <el-form label-width="110px">
      <slot />
      <el-form-item label="文件" required>
        <input ref="fileInput" type="file" :accept="accept" @change="onFile" />
        <div v-if="hint" class="import-hint">{{ hint }}</div>
      </el-form-item>
    </el-form>
    <div v-if="summary.length" class="import-summary">
      <div v-for="line in summary" :key="line">{{ line }}</div>
    </div>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">关闭</el-button>
      <el-button type="primary" :disabled="!file || !ready" :loading="submitting" @click="submit">上传导入</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref } from "vue";
import { ElMessage } from "element-plus";

defineOptions({ name: "LiroamFileImportDialog" });

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  title: { type: String, required: true },
  accept: { type: String, default: ".csv" },
  hint: { type: String, default: "" },
  ready: { type: Boolean, default: true },
  /** (file) => Promise<ApiResult> */
  upload: { type: Function, required: true },
  /** (result) => string[]；不传按通用规则展示 */
  describe: { type: Function, default: null },
});
const emit = defineEmits(["update:modelValue", "done"]);

const fileInput = ref(null);
const file = ref(null);
const submitting = ref(false);
const summary = ref([]);

const SUMMARY_LABELS = {
  total: "总行数", imported: "已导入", matched: "已匹配", delivered: "已交码", created: "已新增",
  skipped: "已跳过", duplicated: "重复", failed: "失败", unmatched: "未匹配",
};

function reset() {
  file.value = null;
  summary.value = [];
  if (fileInput.value) fileInput.value.value = "";
}

function onFile(event) {
  file.value = event.target.files && event.target.files[0] ? event.target.files[0] : null;
  summary.value = [];
}

/** 导入结果形状由后端定：数字计数按已知中文名展示，错误明细逐行列出，其余原样。 */
function describe(result) {
  if (result === null || result === undefined || result === "") return ["导入完成"];
  if (typeof result !== "object") return [String(result)];
  const lines = [];
  Object.entries(result).forEach(([key, value]) => {
    if (Array.isArray(value)) {
      if (value.length) lines.push(`${SUMMARY_LABELS[key] || key}：`, ...value.slice(0, 50).map((v) => `  ${typeof v === "object" ? JSON.stringify(v) : v}`));
    } else if (value !== null && typeof value === "object") {
      lines.push(`${SUMMARY_LABELS[key] || key}：${JSON.stringify(value)}`);
    } else {
      lines.push(`${SUMMARY_LABELS[key] || key}：${value}`);
    }
  });
  return lines.length ? lines : ["导入完成"];
}

async function submit() {
  submitting.value = true;
  try {
    const res = await props.upload(file.value);
    if (res && res.success) {
      summary.value = (props.describe || describe)(res.result);
      ElMessage.success("导入完成");
      emit("done", res.result);
    }
  } catch {
    // 请求层已提示错误
  } finally {
    submitting.value = false;
  }
}
</script>

<style scoped>
.import-hint { width: 100%; margin-top: 6px; font-size: 12px; line-height: 1.6; color: #909399; }
.import-summary { max-height: 240px; overflow: auto; padding: 10px 12px; background: #f7f8fa; border-radius: 4px; font-size: 12px; line-height: 1.8; white-space: pre-wrap; }
</style>
