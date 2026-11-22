/**
 * 旅行卡页面纯函数：金额分→元、接口结果容错（列表、激活码）。
 */
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

function load() {
  const source = fs.readFileSync(path.join(__dirname, '../src/utils/liroam.js'), 'utf8').replace(/^export /gm, '');
  return new Function(source + '; return { fenToYuan, listOf, normalizeCredential, cardFormText, failureMessage, checkoutCartType };')();
}

const { fenToYuan, listOf, normalizeCredential, cardFormText, failureMessage, checkoutCartType } = load();

test('结算方式：旅行卡一律立即购买（普通订单），其它虚拟商品沿用壳的核销型 VIRTUAL，实物为 BUY_NOW', () => {
  assert.equal(checkoutCartType({ goodsType: 'VIRTUAL_GOODS', travelCard: true }), 'BUY_NOW');
  assert.equal(checkoutCartType({ goodsType: 'PHYSICAL_GOODS', travelCard: true }), 'BUY_NOW');
  assert.equal(checkoutCartType({ goodsType: 'VIRTUAL_GOODS', travelCard: false }), 'VIRTUAL');
  assert.equal(checkoutCartType({ goodsType: 'PHYSICAL_GOODS', travelCard: false }), 'BUY_NOW');
  assert.equal(checkoutCartType({}), 'BUY_NOW');
});

test('卡形态：formText 优先，已知形态翻译，未知原样，缺失为空串', () => {
  assert.equal(cardFormText({ form: 'ESIM', formText: '电子卡' }), '电子卡');
  assert.equal(cardFormText({ form: 'esim' }), 'eSIM');
  assert.equal(cardFormText({ form: 'PHYSICAL' }), '实体卡');
  assert.equal(cardFormText({ form: 'WATCH' }), 'WATCH');
  assert.equal(cardFormText({}), '');
  assert.equal(cardFormText(null), '');
});

test('分→元：整数分保留两位小数，缺失或非数字为空串', () => {
  assert.equal(fenToYuan(1234), '12.34');
  assert.equal(fenToYuan(5), '0.05');
  assert.equal(fenToYuan(0), '0.00');
  assert.equal(fenToYuan('990'), '9.90');
  assert.equal(fenToYuan(-150), '-1.50');
  assert.equal(fenToYuan(null), '');
  assert.equal(fenToYuan(undefined), '');
  assert.equal(fenToYuan(''), '');
  assert.equal(fenToYuan('abc'), '');
});

test('列表容错：数组原样、分页取 records、其余为空数组', () => {
  assert.deepEqual(listOf([1, 2]), [1, 2]);
  assert.deepEqual(listOf({ records: [3] }), [3]);
  assert.deepEqual(listOf(null), []);
  assert.deepEqual(listOf({}), []);
  assert.deepEqual(listOf('x'), []);
});

test('激活码：字符串按 LPA 原文并拆出地址与激活码', () => {
  assert.deepEqual(normalizeCredential('LPA:1$smdp.example.com$ABC-123'), {
    lpa: 'LPA:1$smdp.example.com$ABC-123', smdpAddress: 'smdp.example.com', activationCode: 'ABC-123', confirmationCode: '',
  });
});

test('激活码：接口 CredentialView 按 kind 解读 payload（LPA / QR 为完整激活信息，ACTIVATION_CODE 为裸激活码）', () => {
  const view = (kind, payload) => ({ kind, payload, channel: 'MANUAL', deliveredText: '2026-11-23 00:00' });
  assert.deepEqual(normalizeCredential(view('LPA', 'LPA:1$a.b$XYZ')), {
    lpa: 'LPA:1$a.b$XYZ', smdpAddress: 'a.b', activationCode: 'XYZ', confirmationCode: '',
  });
  assert.deepEqual(normalizeCredential(view('QR', ' LPA:1$q.r$QR1 ')), {
    lpa: 'LPA:1$q.r$QR1', smdpAddress: 'q.r', activationCode: 'QR1', confirmationCode: '',
  });
  assert.deepEqual(normalizeCredential(view('ACTIVATION_CODE', 'K1')), {
    lpa: '', smdpAddress: '', activationCode: 'K1', confirmationCode: '',
  });
  assert.equal(normalizeCredential(view('LPA', '')), null);
});

test('激活码：非 LPA 文本只保留原文；空结果为 null', () => {
  assert.deepEqual(normalizeCredential('  PLAIN-CODE '), { lpa: 'PLAIN-CODE', smdpAddress: '', activationCode: '', confirmationCode: '' });
  assert.equal(normalizeCredential(null), null);
  assert.equal(normalizeCredential(''), null);
  assert.equal(normalizeCredential({}), null);
});

test('失败提示：优先后端 message，其次兜底文案', () => {
  assert.equal(failureMessage({ success: false, message: '不能跨供应商加包' }, '操作失败'), '不能跨供应商加包');
  assert.equal(failureMessage({ success: false }, '操作失败'), '操作失败');
  assert.equal(failureMessage(undefined, '操作失败'), '操作失败');
});
