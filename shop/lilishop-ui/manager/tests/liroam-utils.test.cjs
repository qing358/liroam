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
    + 'REPRICE_STATUSES, REPRICE_TRIGGERS, TARIFF_SHEET_STATUSES, FX_RATE_STATUSES, describeTariffImport, fxSyncOutcome, '
    + 'KYC_GATE_MODES, LISTING_STATES, planQuotaText, skuActions, cardActions, alertLevelTag, percentToRate, describeBatchDecision, '
    + 'REPRICE_ADJUST_MODES, channelOrderActions, reconTagType, writebackTagType };')();
}

const {
  fenToYuan, yuanToFen, rateToPercent, listOf, pageOf, pick, cardFormText, labelOf,
  REPRICE_STATUSES, REPRICE_TRIGGERS, TARIFF_SHEET_STATUSES, FX_RATE_STATUSES, describeTariffImport, fxSyncOutcome,
  KYC_GATE_MODES, LISTING_STATES, planQuotaText, skuActions, cardActions, alertLevelTag, percentToRate, describeBatchDecision,
  REPRICE_ADJUST_MODES, channelOrderActions, reconTagType, writebackTagType,
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

test('旅行卡商品枚举：实名闸与上架状态', () => {
  assert.equal(labelOf(KYC_GATE_MODES, 'NONE'), '不要求');
  assert.equal(labelOf(KYC_GATE_MODES, 'PROMPT'), '提示实名');
  assert.equal(labelOf(KYC_GATE_MODES, 'BLOCK_DELIVERY'), '实名后交付');
  assert.equal(labelOf(LISTING_STATES, 'DRAFT'), '草稿');
  assert.equal(labelOf(LISTING_STATES, 'LISTED'), '已上架');
  assert.equal(labelOf(LISTING_STATES, 'DELISTED'), '已下架');
});

test('套餐模板额度：只列有的项，流量按 GB，语音按分钟取整', () => {
  assert.equal(planQuotaText({ dataQuotaBytes: 3 * 2 ** 30, voiceSeconds: 6000, smsCount: 50, validityDays: 30 }), '3 GB · 100 分钟 · 50 条 · 30 天');
  assert.equal(planQuotaText({ dataQuotaBytes: 2 ** 29, voiceSeconds: null, smsCount: null, validityDays: 7 }), '0.5 GB · 7 天');
  assert.equal(planQuotaText({ voiceSeconds: 90, validityDays: 1 }), '1 分钟 · 1 天');
  assert.equal(planQuotaText(null), '');
});

test('SKU 操作：草稿/已下架有售价才可上架，已上架只能下架，只有从未上架的草稿可删', () => {
  assert.deepEqual(skuActions({ listingState: 'DRAFT', priceFen: null, locked: false }), { list: false, delist: false, remove: true, needPrice: true });
  assert.deepEqual(skuActions({ listingState: 'DRAFT', priceFen: 3900, locked: false }), { list: true, delist: false, remove: true, needPrice: false });
  assert.deepEqual(skuActions({ listingState: 'LISTED', priceFen: 3900, locked: true }), { list: false, delist: true, remove: false, needPrice: false });
  assert.deepEqual(skuActions({ listingState: 'DELISTED', priceFen: 3900, locked: true }), { list: true, delist: false, remove: false, needPrice: false });
});

test('卡台账操作：非实体卡录激活码、实体卡录物流（含交付后更正，状态口径同后端）；实名待审/已提交/驳回时可核验', () => {
  assert.deepEqual(cardActions({ form: 'ESIM', status: 'PROVISIONED', kycStatus: 'NOT_REQUIRED' }), { credential: true, shipment: false, kycReview: false });
  assert.deepEqual(cardActions({ form: 'PHYSICAL', status: 'PROVISIONED', kycStatus: 'NOT_REQUIRED' }), { credential: false, shipment: true, kycReview: false });
  assert.deepEqual(cardActions({ form: 'PHYSICAL', status: 'DELIVERED', kycStatus: 'USER_DECLARED' }), { credential: false, shipment: true, kycReview: true });
  assert.deepEqual(cardActions({ form: 'ESIM', status: 'PROVISIONED', kycStatus: 'PENDING' }), { credential: true, shipment: false, kycReview: true });
  assert.deepEqual(cardActions({ form: 'ESIM', status: 'CREATED', kycStatus: 'PENDING' }), { credential: false, shipment: false, kycReview: true });
  assert.deepEqual(cardActions({ form: 'ESIM', status: 'CANCELLED', kycStatus: 'REJECTED' }), { credential: false, shipment: false, kycReview: false });
  assert.deepEqual(cardActions({ form: 'ESIM', status: 'IN_USE', kycStatus: 'VERIFIED' }), { credential: true, shipment: false, kycReview: false });
  assert.deepEqual(cardActions({ form: 'ESIM', status: 'EXCEPTION', kycStatus: 'NOT_REQUIRED' }), { credential: true, shipment: false, kycReview: false });
});

test('告警级别映射到标签颜色', () => {
  assert.equal(alertLevelTag('CRITICAL'), 'danger');
  assert.equal(alertLevelTag('WARN'), 'warning');
  assert.equal(alertLevelTag('OTHER'), 'info');
});

test('百分比输入→比率：5 → 0.05、-10 → -0.1，避免浮点尾差；空或非法为 null', () => {
  assert.equal(percentToRate(5), 0.05);
  assert.equal(percentToRate('-10'), -0.1);
  assert.equal(percentToRate(33.3), 0.333);
  assert.equal(percentToRate(0.1), 0.001);
  assert.equal(percentToRate(''), null);
  assert.equal(percentToRate(null), null);
  assert.equal(percentToRate('abc'), null);
  assert.equal(labelOf(REPRICE_ADJUST_MODES, 'PERCENT'), '按涨跌幅');
  assert.equal(labelOf(REPRICE_ADJUST_MODES, 'TARGET_MARGIN'), '按目标毛利率');
});

test('批量确认 / 驳回结果：全部成功、部分失败逐条列原因、空结果兜底', () => {
  assert.equal(describeBatchDecision({ done: [1, 2], failed: [] }, '确认'), '已确认 2 条');
  assert.equal(
    describeBatchDecision({ done: [1], failed: [{ id: 3, message: '调价建议 #3 已是 REJECTED，不能再确认' }, { id: 9, message: '不存在' }] }, '确认'),
    '已确认 1 条，2 条未处理：#3 调价建议 #3 已是 REJECTED，不能再确认；#9 不存在',
  );
  assert.equal(describeBatchDecision({ done: [], failed: [{ id: 3, message: 'x' }] }, '驳回'), '已驳回 0 条，1 条未处理：#3 x');
  assert.equal(describeBatchDecision(null, '驳回'), '已驳回 0 条');
});

test('渠道订单操作：待人工的单可重放，回传转人工的行可重试', () => {
  assert.deepEqual(channelOrderActions({ status: 'MANUAL', lines: [{ id: 1, writebackStatus: 'PENDING' }] }), { replay: true, retryLineIds: [] });
  assert.deepEqual(channelOrderActions({ status: 'FULFILLING', lines: [{ id: 1, writebackStatus: 'MANUAL' }, { id: 2, writebackStatus: 'DONE' }] }),
    { replay: false, retryLineIds: [1] });
  assert.deepEqual(channelOrderActions({}), { replay: false, retryLineIds: [] });
  assert.deepEqual(channelOrderActions(null), { replay: false, retryLineIds: [] });
});

test('渠道订单标记颜色：对账与回传状态', () => {
  assert.equal(reconTagType('BALANCED'), 'success');
  assert.equal(reconTagType('DIFF'), 'danger');
  assert.equal(reconTagType(null), 'info');
  assert.equal(writebackTagType('DONE'), 'success');
  assert.equal(writebackTagType('MANUAL'), 'danger');
  assert.equal(writebackTagType('FAILED'), 'warning');
  assert.equal(writebackTagType('PENDING'), 'info');
  assert.equal(writebackTagType('READY'), 'info');
});
