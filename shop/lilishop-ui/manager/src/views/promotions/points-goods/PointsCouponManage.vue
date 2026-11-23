<!-- 积分兑券活动配置，复用活动券选择页。作者 Bulbasaur，2026-11-14。 -->
<template>
  <div>
    <el-card>
      <ListActionBar>
        <el-button type="primary" @click="edit()">新增</el-button>
      </ListActionBar>
      <el-alert v-if="failed" class="notice" title="加载失败，请点击重试" type="error" :closable="false">
        <el-button link type="primary" @click="load">重试</el-button>
      </el-alert>
      <el-table
        v-loading="loading"
        :data="rows"
        row-key="id"
        border
        empty-text="暂无积分兑券活动"
        style="width: 100%"
      >
        <el-table-column prop="name" label="活动名称" min-width="160" show-overflow-tooltip />
        <el-table-column prop="couponName" label="优惠券" min-width="160" show-overflow-tooltip />
        <el-table-column prop="points" label="兑换积分" width="110" />
        <el-table-column label="已兑 / 总量" width="130">
          <template #default="{ row }">{{ row.exchanged }} / {{ row.total }}</template>
        </el-table-column>
        <el-table-column prop="memberLimit" label="每人限兑" width="100" />
        <el-table-column label="兑换时间" min-width="180">
          <template #default="{ row }">
            <div>{{ row.startTime }}</div>
            <div>{{ row.endTime }}</div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row)">{{ statusText(row) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" align="center" fixed="right">
          <template #default="{ row }">
            <template v-if="canOperate(row)">
              <a class="link-text" @click="edit(row)">编辑</a>
              <span class="op-split">|</span>
              <a class="link-text" :class="{ disabled: statusBusy }" @click="toggle(row)">
                {{ row.enabled ? '下架' : '上架' }}
              </a>
            </template>
            <span v-else>-</span>
          </template>
        </el-table-column>
      </el-table>
      <div class="pagination mt_10">
        <el-pagination
          v-model:current-page="pageNumber"
          v-model:page-size="pageSize"
          :page-sizes="[20, 50, 100]"
          :total="total"
          layout="total, sizes, prev, pager, next, jumper"
          size="small"
          @current-change="load"
          @size-change="resetPage"
        />
      </div>
    </el-card>
    <el-drawer v-model="drawer" :title="form.id ? '编辑积分兑券' : '新增积分兑换券'" size="min(620px, 100%)" append-to-body :close-on-click-modal="false">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="110px">
        <el-form-item label="活动名称" prop="name"><el-input v-model="form.name" maxlength="100" /></el-form-item>
        <el-form-item label="优惠券" prop="couponId">
          <span>{{ form.couponName || '尚未选择' }}</span>
          <el-button v-if="!form.id" link type="primary" @click="picker = true">选择平台活动券</el-button>
        </el-form-item>
        <el-form-item label="兑换积分" prop="points"><el-input-number v-model="form.points" :min="1" :max="9007199254740991" :precision="0" :controls="false" /></el-form-item>
        <el-form-item label="总兑换数量" prop="total"><el-input-number v-model="form.total" :min="Math.max(1, form.exchanged || 0)" :max="2147483647" :precision="0" :controls="false" /></el-form-item>
        <el-form-item label="每人限兑次数" prop="memberLimit"><el-input-number v-model="form.memberLimit" :min="1" :max="2147483647" :precision="0" :controls="false" /></el-form-item>
        <el-form-item label="开始时间" prop="startTime"><el-date-picker v-model="form.startTime" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" :disabled-date="disablePastDate" placeholder="开始时间" /></el-form-item>
        <el-form-item label="结束时间" prop="endTime"><el-date-picker v-model="form.endTime" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" :disabled-date="disablePastDate" placeholder="结束时间" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="drawer = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template>
    </el-drawer>
    <el-dialog v-model="picker" title="选择一张平台活动券" width="90%" append-to-body destroy-on-close :close-on-click-modal="false">
      <CouponTemplate v-if="picker" ref="couponPicker" manualConfirm getType="ACTIVITY" promotionStatus="START" :selectedList="selected" />
      <template #footer><el-button @click="picker = false">取消</el-button><el-button type="primary" @click="confirmCoupon">确定</el-button></template>
    </el-dialog>
  </div>
</template>
<script setup>
import { onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import CouponTemplate from '@/views/promotions/coupon/coupon.vue';
import ListActionBar from '@/components/list-action-bar.vue';
import { listPointsCoupons, savePointsCoupon, setPointsCouponStatus } from '@/api/pointsCoupon';
const rows = ref([]), total = ref(0), pageNumber = ref(1), pageSize = ref(20);
const loading = ref(false), failed = ref(false), drawer = ref(false), saving = ref(false), picker = ref(false), statusBusy = ref(false);
const form = ref({}), formRef = ref(), couponPicker = ref(), selected = ref([]), originalStartTime = ref('');
let version = 0;
const rules = Object.fromEntries(['name', 'couponId', 'points', 'total', 'memberLimit', 'startTime', 'endTime'].map(key => [key, [{ required: true, message: '请填写此项', trigger: 'change' }]]));
/** 加载分页并丢弃旧筛选请求。 */
async function load() {
  const current = ++version;
  loading.value = true; failed.value = false;
  try {
    const res = await listPointsCoupons({ pageNumber: pageNumber.value, pageSize: pageSize.value });
    if (current !== version) return;
    if (!res.success) throw new Error(res.message);
    rows.value = res.result.records || []; total.value = res.result.total || 0;
  } catch { if (current === version) { failed.value = true; rows.value = []; total.value = 0; } }
  finally { if (current === version) loading.value = false; }
}
/** 调整每页条数时重置分页。 */
function resetPage() { rows.value = []; pageNumber.value = 1; load(); }
/** 编辑使用副本，取消不改变列表。 */
function edit(row) {
  form.value = row ? { ...row } : { name: '', couponId: '', points: 100, total: 100, memberLimit: 1, startTime: '', endTime: '' };
  originalStartTime.value = row ? row.startTime : '';
  selected.value = row ? [{ id: row.couponId, couponName: row.couponName }] : [];
  drawer.value = true;
}
/** 禁用今天之前的日期，避免误选历史日期。 */
function disablePastDate(time) {
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  return time.getTime() < today.getTime();
}
/** 复用既有多选组件，在本场景确认时限制一张平台券。 */
function confirmCoupon() {
  const list = couponPicker.value?.getSelection() || [];
  if (list.length !== 1 || list[0].storeId !== '0') return ElMessage.warning('请选择一张平台发布的活动优惠券');
  selected.value = [...list]; form.value.couponId = list[0].id; form.value.couponName = list[0].couponName; picker.value = false;
}
/** 校验后保存配置，上架状态单独修改。 */
async function save() {
  if (saving.value || !(await formRef.value.validate().catch(() => false))) return;
  if ((!form.value.id || form.value.startTime !== originalStartTime.value)
    && Date.parse(form.value.startTime.replace(' ', 'T') + '+08:00') < Date.now()) {
    return ElMessage.warning('开始时间需要大于等于当前时间');
  }
  if (form.value.startTime >= form.value.endTime) return ElMessage.warning('结束时间必须晚于开始时间');
  saving.value = true;
  try { const res = await savePointsCoupon(form.value); if (res.success) { drawer.value = false; ElMessage.success('保存成功'); await load(); } }
  catch { /* 请求封装展示失败原因，保留表单供重试。 */ }
  finally { saving.value = false; }
}
/** 确认上下架，避免快速点击重复提交。 */
async function toggle(row) {
  if (statusBusy.value) return;
  try { await ElMessageBox.confirm(`确认${row.enabled ? '下架' : '上架'}“${row.name}”？`, '积分兑券'); }
  catch { return; }
  statusBusy.value = true;
  try { const res = await setPointsCouponStatus(row.id, !row.enabled); if (res.success) await load(); }
  catch { /* 请求封装展示错误。 */ }
  finally { statusBusy.value = false; }
}
/** 区分上下架与实际兑换时间状态；已下架后不可编辑或再次上架。 */
function statusText(row) {
  if (!row.enabled) return row.published ? '已下架' : '未上架';
  const now = new Date();
  if (now < new Date(row.startTime.replace(' ', 'T') + '+08:00')) return '未开始';
  if (now >= new Date(row.endTime.replace(' ', 'T') + '+08:00')) return '已结束';
  return row.exchanged >= row.total ? '已兑完' : '兑换中';
}
/** 仅未上架草稿或上架中活动允许编辑与上下架。 */
function canOperate(row) {
  return Boolean(row.enabled || !row.published);
}
/** 与现有营销列表一致，用状态标签区分兑换中、待开始和停止活动。 */
function statusTagType(row) {
  const status = statusText(row);
  return status === '兑换中' ? 'success' : status === '未开始' ? 'warning' : 'info';
}
onMounted(load);
</script>
<style scoped>
.notice {
  margin-bottom: 16px;
}
.pagination {
  display: flex;
  justify-content: flex-end;
}
.mt_10 {
  margin-top: 10px;
}
.link-text {
  color: #0b7a75;
  cursor: pointer;
  text-decoration: none;
}
.link-text.disabled {
  pointer-events: none;
  opacity: 0.5;
}
.op-split {
  margin: 0 8px;
  color: #dcdee2;
}
</style>
