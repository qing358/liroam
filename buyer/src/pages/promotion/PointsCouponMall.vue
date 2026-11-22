<!-- 积分商城兑换平台优惠券。作者 Bulbasaur，2026-11-14。 -->
<template>
  <section class="coupon-mall" v-loading="loading">
    <div class="toolbar">
      <p v-if="balance !== null">可用积分：<strong class="balance">{{ balance }}</strong></p>
      <span v-else />
      <router-link to="/home/Coupons">我的优惠券</router-link>
    </div>
    <el-alert v-if="failed" title="加载失败，请重试" type="error" :closable="false"><el-button @click="load">重试</el-button></el-alert>
    <template v-else>
      <el-empty v-if="!loading && !rows.length" description="暂无可兑换优惠券" />
      <div class="coupon-grid">
        <el-card v-for="item in rows" :key="item.id">
          <h3>{{ item.couponName || item.name }}</h3>
          <strong>{{ item.couponType === 'DISCOUNT' ? `${item.couponDiscount}折` : `¥${item.price}` }}</strong>
          <p>{{ item.consumeThreshold ? `满${item.consumeThreshold}元可用` : '无门槛使用' }}</p>
          <p>适用范围：{{ scopeText(item.scopeType) }}</p>
          <p>{{ item.rangeDayType === 'DYNAMICTIME' ? `领取当日起${item.effectiveDays}天有效` : `券有效至${item.couponEndTime || ''}` }}</p>
          <p>{{ item.description }}</p>
          <p>剩余 {{ item.remaining }} 张 · 每人限兑 {{ item.memberLimit }} 次</p>
          <el-button type="primary" :disabled="!!busy || item.remaining === 0" :loading="busy === item.id" @click="exchange(item)">{{ item.points }}积分兑换</el-button>
        </el-card>
      </div>
    </template>
    <el-pagination v-if="total > 0" class="pagination" v-model:current-page="pageNumber" :page-size="12" :total="total" layout="total, prev, pager, next" @current-change="load" />
  </section>
</template>
<script setup>
import { onMounted, onBeforeUnmount, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { v4 as uuid } from 'uuid';
import Storage from '@/plugins/storage';
import { getMemberMsg } from '@/api/login';
import { listPointsCoupons, exchangePointsCoupon } from '@/api/pointsCoupon';
const rows = ref([]), pageNumber = ref(1), total = ref(0), loading = ref(false), failed = ref(false), busy = ref('');
const balance = ref(null);
let version = 0, alive = true;
/** 账户标识用于隔离异步结果和重试请求号。 */
function account() {
  try { return String(JSON.parse(Storage.getItem('userInfo') || '{}').id || ''); }
  catch { return ''; }
}
/** 分页加载可兑换活动，旧请求或切换账户后的响应不写入页面。 */
async function load() {
  const current = ++version, owner = account();
  loading.value = true; failed.value = false;
  try {
    const res = await listPointsCoupons({ pageNumber: pageNumber.value, pageSize: 12 });
    if (!alive || current !== version || owner !== account()) return;
    if (!res.success) throw new Error(res.message);
    rows.value = res.result.records || []; total.value = res.result.total || 0;
  } catch { if (alive && current === version && owner === account()) { rows.value = []; total.value = 0; failed.value = true; } }
  finally { if (alive && current === version) loading.value = false; }
}
/** 展示券模板限定的适用范围，具体可用商品由结算服务校验。 */
function scopeText(type) { return ({ ALL: '全品类', PORTION_GOODS: '指定商品', PORTION_GOODS_CATEGORY: '指定商品分类', PORTION_SHOP_CATEGORY: '指定店铺分类' })[type] || '以优惠券规则为准'; }
/** 读取服务端当前余额，切换账户时不回写旧会员数据。 */
async function refreshBalance() {
  const owner = account();
  balance.value = null;
  if (!owner) return;
  try { const res = await getMemberMsg(); if (alive && owner === account() && res.success) balance.value = res.result.point || 0; }
  catch { /* 余额不可用时仍由兑换接口校验。 */ }
}
/** 确认积分后兑换；不确定结果保留sessionStorage请求号，刷新或重试也不会再次扣款。 */
async function exchange(item) {
  if (busy.value) return;
  const owner = account();
  if (!owner) return ElMessage.warning('请先登录后兑换');
  busy.value = item.id;
  try {
    await ElMessageBox.confirm(`确认消耗${item.points}积分兑换“${item.couponName}”一张？兑换成功后不支持主动退兑。`, '积分兑券');
    if (owner !== account()) return;
    const key = `points-coupon:${owner}:${item.id}`;
    let pending = JSON.parse(sessionStorage.getItem(key) || 'null');
    if (!pending) { pending = { requestId: uuid(), points: item.points }; sessionStorage.setItem(key, JSON.stringify(pending)); }
    const res = await exchangePointsCoupon(item.id, pending);
    if (!alive || owner !== account()) return;
    if (res.success) {
      sessionStorage.removeItem(key); ElMessage.success('兑换成功，已放入我的优惠券'); await Promise.all([load(), refreshBalance()]);
    } else { sessionStorage.removeItem(key); ElMessage.warning(res.message || '兑换失败，请重试'); await load(); }
  } catch (error) {
    // 服务端明确拒绝可结束本请求；超时或网络失败保留请求号，下一次先查询原结果。
    if (error?.response?.data?.success === false && owner === account()) sessionStorage.removeItem(`points-coupon:${owner}:${item.id}`);
  } finally { if (alive) busy.value = ''; }
}
onMounted(() => { load(); refreshBalance(); });
onBeforeUnmount(() => { alive = false; version++; });
</script>
<style scoped>
.coupon-mall { padding-top: 4px; }
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 14px; }
.coupon-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(250px, 1fr)); gap: 18px; margin-top: 16px; }
.coupon-grid :deep(.el-card__body) { display: flex; flex-direction: column; height: 100%; box-sizing: border-box; }
.coupon-grid h3 { margin: 0 0 8px; font-size: 16px; color: #303133; }
strong { color: #e74646; font-size: 28px; display: block; margin-bottom: 6px; }
.balance { font-size: 18px; display: inline; margin-bottom: 0; }
p { color: #666; line-height: 1.7; margin: 4px 0; }
.coupon-grid .el-button { margin-top: auto; width: 100%; }
.pagination { margin-top: 20px; justify-content: flex-end; }
</style>
