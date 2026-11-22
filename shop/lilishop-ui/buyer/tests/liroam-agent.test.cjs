/**
 * 我的佣金 / 到期提醒的纯函数：提现表单校验、未读到期提醒条。
 */
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

function load() {
  const source = fs.readFileSync(path.join(__dirname, '../src/utils/liroam.js'), 'utf8').replace(/^export /gm, '');
  return new Function(source + '; return { withdrawFormError, expiryNotices, yuanToFen };')();
}

const { withdrawFormError, expiryNotices, yuanToFen } = load();

const summary = { availableFen: 5000, minWithdrawFen: 100 };
const form = { amountYuan: '20', method: 'ALIPAY', name: '张三', account: '13800001111' };

test('元→分：两位小数，非法为 null', () => {
  assert.equal(yuanToFen('12.34'), 1234);
  assert.equal(yuanToFen(0.1), 10);
  assert.equal(yuanToFen(''), null);
  assert.equal(yuanToFen('abc'), null);
});

test('提现表单：合法为空串；金额、上下限、收款信息逐项提示', () => {
  assert.equal(withdrawFormError(form, summary), '');
  assert.equal(withdrawFormError({ ...form, amountYuan: '' }, summary), '请填写提现金额');
  assert.equal(withdrawFormError({ ...form, amountYuan: '0.5' }, summary), '最低提现 1.00 元');
  assert.equal(withdrawFormError({ ...form, amountYuan: '50.01' }, summary), '最多可提 50.00 元');
  assert.equal(withdrawFormError({ ...form, amountYuan: '1.234' }, summary), '金额最多两位小数');
  assert.equal(withdrawFormError({ ...form, method: '' }, summary), '请选择收款方式');
  assert.equal(withdrawFormError({ ...form, name: ' ' }, summary), '请填写收款人姓名');
  assert.equal(withdrawFormError({ ...form, account: '' }, summary), '请填写收款账号');
});

test('到期提醒条：只取未读的到期提醒，按卡去重保留最新一条', () => {
  const inbox = {
    unread: 3,
    items: [
      { id: 9, kind: 'EXPIRY_REMIND', cardAssetId: 7, title: '旅行卡即将到期', body: '尾号 1111 24 小时内到期', read: false },
      { id: 8, kind: 'EXPIRY_REMIND', cardAssetId: 7, title: '旅行卡即将到期', body: '尾号 1111 72 小时内到期', read: false },
      { id: 6, kind: 'EXPIRY_REMIND', cardAssetId: 5, title: '旅行卡即将到期', body: '尾号 2222', read: true },
      { id: 5, kind: 'OTHER', cardAssetId: 3, title: 'x', body: 'y', read: false },
    ],
  };
  assert.deepEqual(expiryNotices(inbox), [{ id: 9, cardAssetId: 7, text: '尾号 1111 24 小时内到期' }]);
  assert.deepEqual(expiryNotices(null), []);
  assert.deepEqual(expiryNotices({ items: null }), []);
});
