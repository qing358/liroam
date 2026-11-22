/**
 * 旅行卡后台纯函数：金额分→元、比率、分页与字段容错。
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

function load() {
  const source = fs.readFileSync(path.join(__dirname, '../src/utils/liroam.js'), 'utf8').replace(/^export /gm, '');
  return new Function(source + '; return { fenToYuan, yuanToFen, rateToPercent, listOf, pageOf, pick, cardFormText, labelOf, '
    + 'REPRICE_STATUSES, REPRICE_TRIGGERS, TARIFF_SHEET_STATUSES, FX_RATE_STATUSES, describeTariffImport, fxSyncOutcome };')();
}

const {
  fenToYuan, yuanToFen, rateToPercent, listOf, pageOf, pick, cardFormText, labelOf,
  REPRICE_STATUSES, REPRICE_TRIGGERS, TARIFF_SHEET_STATUSES, FX_RATE_STATUSES, describeTariffImport, fxSyncOutcome,
} = load();

test('枚举标签：已知值翻译，未知原样，缺失为空串', () => {
  assert.equal(labelOf(REPRICE_STATUSES, 'PENDING'), '待确认');
  assert.equal(labelOf(REPRICE_STATUSES, 'SUPERSEDED'), '已失效');
  assert.equal(labelOf(REPRICE_TRIGGERS, 'FX'), '汇率变动');
  assert.equal(labelOf(REPRICE_TRIGGERS, 'TARIFF'), '资费表变动');
  assert.equal(labelOf(TARIFF_SHEET_STATUSES, 'DRAFT'), '待启用');
  assert.equal(labelOf(TARIFF_SHEET_STATUSES, 'ACTIVE'), '生效中');
  assert.equal(labelOf(TARIFF_SHEET_STATUSES, 'SUPERSEDED'), '已替换');
  assert.equal(labelOf(FX_RATE_STATUSES, 'ACTIVE'), '当前');
  assert.equal(labelOf(REPRICE_STATUSES, 'NEW_ONE'), 'NEW_ONE');
  assert.equal(labelOf(REPRICE_STATUSES, null), '');
});

test('资费表导入结果：文件、状态、行数分类、资源区域数、未匹配国家逐行列出', () => {
  assert.deepEqual(describeTariffImport({
    sheetId: 7, supplierId: 2, fileName: 'rates.xlsx', status: 'DRAFT', effectiveFromText: '2026-12-01', lineCount: 12,
    linesByKind: { DATA: 9, VOICE: 2, CARD_FEE: 1 }, unmatchedCountries: ['Atlantis', 'Lemuria'],
    resources: [{ resourceCode: 'R1', regionCount: 8 }, { resourceCode: 'R2', regionCount: 0 }],
  }), [
    '资费表 #7：rates.xlsx（待启用）',
    '生效日期：2026-12-01',
    '共 12 行：流量 9、语音 2、卡费 1',
    '资源 R1：匹配 8 个国家/地区',
    '资源 R2：匹配 0 个国家/地区',
    '未匹配国家/地区 2 个（已原样入库，不进可用区域）：Atlantis、Lemuria',
  ]);
});

test('资费表导入结果：缺生效日、无未匹配、未知行类原样；空结果兜底', () => {
  assert.deepEqual(describeTariffImport({
    sheetId: 1, fileName: 'a.xlsx', status: 'ACTIVE', lineCount: 1, linesByKind: { SMS: 1, OTHER: 2 }, unmatchedCountries: [], resources: [],
  }), ['资费表 #1：a.xlsx（生效中）', '共 1 行：短信 1、OTHER 2']);
  assert.deepEqual(describeTariffImport(null), ['导入完成']);
});

test('汇率同步：ok 才算成功并带汇率与来源；ok=false 给后端原因；无原因兜底', () => {
  assert.deepEqual(fxSyncOutcome({ ok: true, versionId: 3, rate: 0.9213, sourceKey: 'mock', error: null }),
    { ok: true, message: '已同步：1 HKD = 0.9213 CNY（来源 mock）' });
  assert.deepEqual(fxSyncOutcome({ ok: false, versionId: null, rate: null, sourceKey: 'mock', error: '来源不可用' }),
    { ok: false, message: '同步失败（来源 mock）：来源不可用' });
  assert.deepEqual(fxSyncOutcome({ ok: false }), { ok: false, message: '同步失败：原因未知' });
  assert.deepEqual(fxSyncOutcome(null), { ok: false, message: '同步失败：原因未知' });
});

test('卡形态：formText 优先，已知形态翻译，未知原样', () => {
  assert.equal(cardFormText({ form: 'ESIM', formText: '电子卡' }), '电子卡');
  assert.equal(cardFormText({ form: 'esim' }), 'eSIM');
  assert.equal(cardFormText({ form: 'PHYSICAL' }), '实体卡');
  assert.equal(cardFormText({ form: 'X' }), 'X');
  assert.equal(cardFormText(null), '');
});

test('分→元：两位小数，缺失为空串', () => {
  assert.equal(fenToYuan(1234), '12.34');
  assert.equal(fenToYuan('7'), '0.07');
  assert.equal(fenToYuan(-150), '-1.50');
  assert.equal(fenToYuan(null), '');
  assert.equal(fenToYuan('n/a'), '');
});

test('元→分：四舍五入到分，非法为 null', () => {
  assert.equal(yuanToFen('12.345'), 1235);
  assert.equal(yuanToFen(0.1 + 0.2), 30);
  assert.equal(yuanToFen(''), null);
  assert.equal(yuanToFen('x'), null);
});

test('比率→百分比：小数比率乘 100，最多两位小数', () => {
  assert.equal(rateToPercent(0.05), '5%');
  assert.equal(rateToPercent('0.1234'), '12.34%');
  assert.equal(rateToPercent(-0.015), '-1.5%');
  assert.equal(rateToPercent(null), '');
  assert.equal(rateToPercent(''), '');
});

test('列表与分页容错', () => {
  assert.deepEqual(listOf([1]), [1]);
  assert.deepEqual(listOf({ records: [2] }), [2]);
  assert.deepEqual(listOf(undefined), []);
  assert.deepEqual(pageOf({ records: [1, 2], total: 9 }), { records: [1, 2], total: 9 });
  assert.deepEqual(pageOf({ records: [1, 2] }), { records: [1, 2], total: 2 });
  assert.deepEqual(pageOf([1]), { records: [1], total: 1 });
  assert.deepEqual(pageOf(null), { records: [], total: 0 });
});

test('取字段：按顺序取第一个非空值，都没有为空串', () => {
  assert.equal(pick({ a: '', b: 0, c: 1 }, 'a', 'b', 'c'), 0);
  assert.equal(pick({ statusText: '使用中', status: 'IN_USE' }, 'statusText', 'status'), '使用中');
  assert.equal(pick({}, 'x'), '');
  assert.equal(pick(null, 'x'), '');
});
