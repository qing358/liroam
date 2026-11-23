<template>
  <section v-if="info" class="usage-region">
    <div class="usage-region__head">
      <h3>使用区域说明</h3>
      <span v-if="info.headline" class="usage-region__headline">{{ info.headline }}</span>
    </div>
    <table v-if="regions.length" class="usage-region__table">
      <thead>
        <tr><th>国家 / 地区</th><th>所属大洲</th><th>实名要求</th><th>备注</th></tr>
      </thead>
      <tbody>
        <tr v-for="item in regions" :key="item.code || item.name">
          <td>{{ item.name || item.code }}</td>
          <td>{{ item.continent || "—" }}</td>
          <td><span v-if="item.kycRequired" class="usage-region__kyc">需实名</span><span v-else>无需</span></td>
          <td class="usage-region__note">{{ item.note || "—" }}</td>
        </tr>
      </tbody>
    </table>
    <div v-if="info.fullText" class="usage-region__full">{{ info.fullText }}</div>
  </section>
</template>

<script setup>
import { computed, ref, watch } from "vue";
import { getUsageRegion } from "@/api/liroam";
import { listOf } from "@/utils/liroam";

defineOptions({ name: "LiroamUsageRegion" });

const props = defineProps({
  skuId: { type: [String, Number], default: "" },
});

const info = ref(null);
const regions = computed(() => listOf(info.value?.regions));

let requestId = 0;
async function load(skuId) {
  const current = ++requestId;
  info.value = null;
  if (!skuId) return;
  try {
    const res = await getUsageRegion(skuId);
    if (current === requestId && res && res.success && res.result && typeof res.result === "object") {
      info.value = res.result;
    }
  } catch {
    // 说明区块是附加信息，取不到就不显示，不打扰商品主流程
  }
}

watch(() => props.skuId, load, { immediate: true });
</script>

<style scoped lang="scss">
.usage-region {
  width: 1200px;
  margin: 16px auto 0;
  padding: 20px 24px;
  background: #fff;
  border-radius: 8px;
  &__head {
    display: flex;
    flex-wrap: wrap;
    align-items: baseline;
    gap: 12px;
    margin-bottom: 14px;
    h3 { margin: 0; font-size: 16px; font-weight: 600; color: #171a26; }
  }
  &__headline { font-size: 13px; color: #c8461f; }
  &__table {
    width: 100%;
    border-collapse: collapse;
    font-size: 13px;
    th, td { padding: 9px 12px; border: 1px solid #eee; text-align: left; vertical-align: top; }
    th { background: #f7f7f7; font-weight: 500; color: #666; }
    td { color: #333; }
  }
  &__kyc { color: #c8461f; }
  &__note { color: #666; white-space: pre-wrap; }
  &__full { margin-top: 14px; font-size: 13px; line-height: 1.8; color: #666; white-space: pre-wrap; }
}
</style>
