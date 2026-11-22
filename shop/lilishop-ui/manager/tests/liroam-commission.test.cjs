/**
 * 佣金 / 提现 / 对账页面的纯函数：策略摘要、提现按钮显隐、对账差异说明、按元填写的比例与金额换算。
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

function load() {
  const source = fs.readFileSync(path.join(__dirname, '../src/utils/liroam.js'), 'utf8').replace(/^export /gm, '');
  return new Function(source + '; return { policySummary, withdrawalActions, reconDiffText, percentToRate, labelOf, '
    + 'reconScopeText, COMMISSION_SCOPES, SETTLE_ANCHORS, WITHDRAWAL_STATUSES, RECON_KINDS, RECON_DIFF_KINDS };')();
}

const {
  policySummary, withdrawalActions, reconDiffText, reconScopeText, percentToRate, labelOf,
  COMMISSION_SCOPES, SETTLE_ANCHORS, WITHDRAWAL_STATUSES, RECON_KINDS, RECON_DIFF_KINDS,
} = load();

const MODES = [{ mode: 'PAID_RATE', title: '实付抽成' }, { mode: 'FLOOR_DIFF', title: '底价差价' }, { mode: 'NONE', title: '不计佣' }];

test('策略摘要：模式名 + 抽成比例 + 两级拆分 + 结算窗口 + 审批', () => {
  assert.equal(policySummary({
    mode: 'PAID_RATE', poolRate: 0.1, directRate: 0.8, inviteRate: 0.2, settleDays: 1, settleAnchor: 'DELIVERED', withdrawApproval: true,
  }, MODES), '实付抽成 10%｜一级 80% / 二级 20%｜T+1（交码起算）｜提现需审批');
  assert.equal(policySummary({
    mode: 'FLOOR_DIFF', directRate: 1, inviteRate: 0, settleDays: 0, settleAnchor: 'PAID', withdrawApproval: false,
  }, MODES), '底价差价｜一级 100% / 二级 0%｜T+0（支付起算）｜提现免审批');
  assert.equal(policySummary({ mode: 'NONE' }, MODES), '不计佣');
  assert.equal(policySummary({ mode: 'X', directRate: 1, inviteRate: 0, settleDays: 2, settleAnchor: 'PAID' }, []), 'X｜一级 100% / 二级 0%｜T+2（支付起算）｜提现需审批');
  assert.equal(policySummary(null, MODES), '');
});

test('佣金比例按百分比填写，复用通用换算', () => {
  assert.equal(percentToRate(10), 0.1);
  assert.equal(percentToRate('12.5'), 0.125);
});

test('提现按钮：待审核可批/驳，待打款可登记/驳，终态无操作', () => {
  assert.deepEqual(withdrawalActions({ status: 'APPLIED' }), { approve: true, reject: true, paid: false });
  assert.deepEqual(withdrawalActions({ status: 'APPROVED' }), { approve: false, reject: true, paid: true });
  assert.deepEqual(withdrawalActions({ status: 'PAID' }), { approve: false, reject: false, paid: false });
  assert.deepEqual(withdrawalActions({ status: 'REJECTED' }), { approve: false, reject: false, paid: false });
});

test('对账差异说明：按类型给中文，金额按币种两位小数', () => {
  assert.equal(reconDiffText({ diffKind: 'AMOUNT', ourRef: 'O2', currency: 'CNY', ourAmountMinor: 5000, theirAmountMinor: 4900 }),
    'O2：我方 50.00 CNY，对方 49.00 CNY');
  assert.equal(reconDiffText({ diffKind: 'MISSING_THEIRS', ourRef: 'card:7', currency: 'HKD', ourAmountMinor: 800 }), 'card:7：对方账单没有（我方 8.00 HKD）');
  assert.equal(reconDiffText({ diffKind: 'MISSING_OURS', theirRef: 'ICCID ****9999', currency: 'HKD', theirAmountMinor: 500 }),
    'ICCID ****9999：我方没有（对方 5.00 HKD）');
  assert.equal(reconDiffText({ diffKind: 'STATUS', ourRef: 'JD-1', currency: 'CNY', ourAmountMinor: 4900, theirAmountMinor: 4900 }),
    'JD-1：对方已结算，我方未履约完成（49.00 CNY）');
});

test('对账范围：供应商 / 渠道按类型说明，支付对账无范围', () => {
  assert.equal(reconScopeText({ kind: 'SUPPLIER_DAILY', scopeRef: '3' }), '供应商 3');
  assert.equal(reconScopeText({ kind: 'CHANNEL_DAILY', scopeRef: 'JD_DEMO' }), '渠道 JD_DEMO');
  assert.equal(reconScopeText({ kind: 'PAYMENT_DAILY', scopeRef: null }), '—');
});

test('枚举标签', () => {
  assert.equal(labelOf(COMMISSION_SCOPES, 'AGENT_GRADE'), '按代理等级');
  assert.equal(labelOf(SETTLE_ANCHORS, 'DELIVERED'), '交码起算');
  assert.equal(labelOf(WITHDRAWAL_STATUSES, 'APPROVED'), '待打款');
  assert.equal(labelOf(RECON_KINDS, 'SUPPLIER_DAILY'), '供应商日对账');
  assert.equal(labelOf(RECON_DIFF_KINDS, 'MISSING_OURS'), '我方缺失');
  assert.equal(labelOf(RECON_KINDS, 'CHANNEL_DAILY'), '渠道日对账');
  assert.equal(labelOf(RECON_DIFF_KINDS, 'STATUS'), '未履约完成');
});
