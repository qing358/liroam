/**
 * 旅行卡页面纯函数：金额分→元、接口结果容错（列表、激活码）。
 */
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

function load() {
  const source = fs.readFileSync(path.join(__dirname, '../src/utils/liroam.js'), 'utf8').replace(/^export /gm, '');
  return new Function(source + '; return { fenToYuan, listOf, normalizeCredential, cardFormText, failureMessage };')();
}

const { fenToYuan, listOf, normalizeCredential, cardFormText, failureMessage } = load();

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

test('激活码：对象字段优先，缺的从 lpa 补', () => {
  assert.deepEqual(normalizeCredential({ lpa: 'LPA:1$a.b$XYZ', activationCode: 'OVERRIDE' }), {
    lpa: 'LPA:1$a.b$XYZ', smdpAddress: 'a.b', activationCode: 'OVERRIDE', confirmationCode: '',
  });
  assert.deepEqual(normalizeCredential({ smdpAddress: 's.c', activationCode: 'K1', confirmationCode: '9' }), {
    lpa: '', smdpAddress: 's.c', activationCode: 'K1', confirmationCode: '9',
  });
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
