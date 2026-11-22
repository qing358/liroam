<template>
  <el-dialog :model-value="modelValue" :title="physical ? '物流信息' : 'eSIM 激活码'" width="460px" @update:model-value="emit('update:modelValue', $event)" @open="load">
    <el-skeleton v-if="loading" :rows="4" animated />
    <div v-else-if="errorText" class="credential-empty">{{ errorText }}</div>
    <div v-else-if="shipment" class="credential">
      <dl class="credential-fields">
        <dt>物流公司</dt>
        <dd><span>{{ shipment.company }}</span></dd>
        <dt>运单号</dt>
        <dd><span>{{ shipment.no }}</span><el-button link type="primary" @click="copy(shipment.no)">复制</el-button></dd>
      </dl>
      <el-timeline v-if="track && track.events.length" class="credential-track">
        <el-timeline-item v-for="(e, i) in track.events" :key="i" :timestamp="e.timeText" :type="i === 0 ? 'primary' : ''">{{ e.description }}</el-timeline-item>
      </el-timeline>
      <p v-else-if="track && track.message" class="credential-track-empty">{{ track.message }}</p>
      <p class="credential-tip">实体卡收到后按卡附说明插卡使用。</p>
    </div>
    <div v-else-if="credential" class="credential">
      <div v-if="credential.lpa && isLpa" class="credential-qr">
        <vue-qr :text="credential.lpa" :margin="0" colorDark="#000" colorLight="#fff" :size="200" />
        <p>在手机「设置 › 蜂窝网络 › 添加 eSIM」中扫描此二维码</p>
      </div>
      <dl class="credential-fields">
        <template v-if="credential.smdpAddress">
          <dt>SM-DP+ 地址</dt>
          <dd><span>{{ credential.smdpAddress }}</span><el-button link type="primary" @click="copy(credential.smdpAddress)">复制</el-button></dd>
        </template>
        <template v-if="credential.activationCode">
          <dt>激活码</dt>
          <dd><span>{{ credential.activationCode }}</span><el-button link type="primary" @click="copy(credential.activationCode)">复制</el-button></dd>
        </template>
        <template v-if="credential.confirmationCode">
          <dt>确认码</dt>
          <dd><span>{{ credential.confirmationCode }}</span><el-button link type="primary" @click="copy(credential.confirmationCode)">复制</el-button></dd>
        </template>
        <template v-if="credential.lpa">
          <dt>完整激活信息</dt>
          <dd><span class="credential-lpa">{{ credential.lpa }}</span><el-button link type="primary" @click="copy(credential.lpa)">复制</el-button></dd>
        </template>
      </dl>
      <p class="credential-tip">激活码仅限本人使用，请勿截图外传；一张 eSIM 通常只能安装一次。</p>
    </div>
  </el-dialog>
</template>

<script setup>
import { computed, ref } from "vue";
import vueQr from "vue-qr";
import { Message } from "@/utils/message";
import { getCardCredential, getCardTracking } from "@/api/liroam";
import { normalizeCredential, failureMessage, shipmentOf } from "@/utils/liroam";

defineOptions({ name: "LiroamCredentialDialog" });

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  cardId: { type: [String, Number], required: true },
  physical: { type: Boolean, default: false },
});
const emit = defineEmits(["update:modelValue"]);

const loading = ref(false);
const credential = ref(null);
const shipment = ref(null);
const track = ref(null);
const errorText = ref("");
const isLpa = computed(() => /^LPA:/i.test(credential.value?.lpa || ""));

async function load() {
  loading.value = true;
  credential.value = null;
  shipment.value = null;
  track.value = null;
  errorText.value = "";
  try {
    const res = await getCardCredential(props.cardId);
    if (res && res.success) {
      shipment.value = shipmentOf(res.result);
      credential.value = shipment.value ? null : normalizeCredential(res.result);
      if (shipment.value) loadTrack();
      if (!credential.value && !shipment.value) errorText.value = props.physical ? "实体卡尚未寄出，请稍后再看" : "激活码尚未发放，请稍后再看";
    } else {
      errorText.value = failureMessage(res, "暂时无法查看激活码");
    }
  } catch {
    errorText.value = "暂时无法查看激活码，请稍后重试";
  } finally {
    loading.value = false;
  }
}

async function loadTrack() {
  try {
    const res = await getCardTracking(props.cardId);
    track.value = res && res.success && res.result ? { message: res.result.message, events: res.result.events || [] } : null;
  } catch {
    track.value = null;
  }
}

function copy(text) {
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(text).then(() => Message.success("已复制"), () => Message.warning("复制失败，请手动选择复制"));
  } else {
    Message.warning("当前浏览器不支持一键复制，请手动选择复制");
  }
}
</script>

<style scoped lang="scss">
.credential-qr {
  text-align: center;
  p { margin: 12px 0 0; font-size: 12px; color: #909399; }
}
.credential-fields {
  margin: 20px 0 0;
  dt { margin-top: 12px; font-size: 12px; color: #909399; }
  dd { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin: 4px 0 0; font-size: 14px; color: #303133; word-break: break-all; }
}
.credential-lpa { font-family: monospace; font-size: 12px; }
.credential-track { margin: 20px 0 0; padding-left: 2px; }
.credential-track-empty { margin: 16px 0 0; font-size: 13px; color: #606266; }
.credential-tip { margin: 20px 0 0; font-size: 12px; color: #e6a23c; }
.credential-empty { padding: 32px 0; text-align: center; color: #909399; }
</style>
