import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

import { heavyEnv, itDbPrefix } from './lib/heavy.mjs';

const heavy = path.join(path.dirname(fileURLToPath(import.meta.url)), 'heavy.mjs');

function run(args, env) {
  return new Promise((resolve) => {
    const child = spawn(process.execPath, [heavy, ...args], { env: { ...process.env, ...env }, stdio: ['ignore', 'pipe', 'pipe'] });
    let out = '';
    child.stdout.on('data', (d) => { out += d; });
    child.stderr.on('data', (d) => { out += d; });
    child.on('close', (code) => resolve({ code, out }));
  });
}

function queueEnv(dir, extra = {}) {
  return { LIROAM_HEAVY_LOCK_DIR: dir, LIROAM_HEAVY_POLL_MS: '50', LIROAM_HEAVY_MIN_MEM_GB: '0', ...extra };
}

test('同时最多 2 个重活在跑，第 3 个排队等槽位，全部最终完成', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'heavy-'));
  const log = path.join(dir, 'events.log');
  const job = `echo start >> ${log}; sleep 0.6; echo end >> ${log}`;
  const results = await Promise.all([1, 2, 3, 4].map(() => run(['sh', '-c', job], queueEnv(dir))));
  assert.deepEqual(results.map((r) => r.code), [0, 0, 0, 0]);
  let running = 0;
  let peak = 0;
  for (const event of fs.readFileSync(log, 'utf8').trim().split('\n')) {
    running += event === 'start' ? 1 : -1;
    peak = Math.max(peak, running);
  }
  assert.equal(peak, 2);
  assert.ok(results.some((r) => r.out.includes('排队')));
});

test('槽位数可配；命令退出码原样透传', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'heavy-'));
  assert.equal((await run(['sh', '-c', 'exit 3'], queueEnv(dir, { LIROAM_HEAVY_SLOTS: '1' }))).code, 3);
  assert.equal((await run(['--', 'true'], queueEnv(dir))).code, 0);
});

test('没给命令时报用法并失败', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'heavy-'));
  const r = await run([], queueEnv(dir));
  assert.notEqual(r.code, 0);
  assert.match(r.out, /用法/);
});

test('可用内存低于门槛时不开跑，等待并提示', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'heavy-'));
  const child = spawn(process.execPath, [heavy, 'sh', '-c', `touch ${dir}/ran`], {
    env: { ...process.env, ...queueEnv(dir, { LIROAM_HEAVY_MIN_MEM_GB: '100000' }) }, stdio: ['ignore', 'pipe', 'pipe'],
  });
  let out = '';
  child.stdout.on('data', (d) => { out += d; });
  child.stderr.on('data', (d) => { out += d; });
  await new Promise((r) => setTimeout(r, 400));
  child.kill('SIGTERM');
  await new Promise((r) => child.on('close', r));
  assert.ok(!fs.existsSync(path.join(dir, 'ran')));
  assert.match(out, /可用内存/);
});

test('itDbPrefix 由 worktree 目录名推出合法的集成测试库前缀', () => {
  assert.equal(itDbPrefix('ws8-design'), 'liroam_it_ws8_design_');
  assert.equal(itDbPrefix('WS10'), 'liroam_it_ws10_');
});

test('heavyEnv：worktree 里补口令、库前缀与 JDK；主检出不改库前缀；已设的变量不覆盖', () => {
  const base = { PATH: '/usr/bin' };
  const wt = heavyEnv(base, { checkout: '/w/ws4', mainCheckout: '/m', mainSecrets: { LIROAM_MYSQL_ROOT_PASSWORD: 'pw' }, javaHome: '/jdk21' });
  assert.equal(wt.LIROAM_MYSQL_ROOT_PASSWORD, 'pw');
  assert.equal(wt.LIROAM_IT_DB_PREFIX, 'liroam_it_ws4_');
  assert.equal(wt.JAVA_HOME, '/jdk21');
  assert.equal(wt.PATH, '/jdk21/bin:/usr/bin');

  const main = heavyEnv(base, { checkout: '/m', mainCheckout: '/m', mainSecrets: { LIROAM_MYSQL_ROOT_PASSWORD: 'pw' }, javaHome: null });
  assert.ok(!('LIROAM_IT_DB_PREFIX' in main));
  assert.ok(!('JAVA_HOME' in main));

  const preset = heavyEnv({ ...base, LIROAM_MYSQL_ROOT_PASSWORD: 'mine', LIROAM_IT_DB_PREFIX: 'liroam_it_x_' },
    { checkout: '/w/ws4', mainCheckout: '/m', mainSecrets: { LIROAM_MYSQL_ROOT_PASSWORD: 'pw' }, javaHome: null });
  assert.equal(preset.LIROAM_MYSQL_ROOT_PASSWORD, 'mine');
  assert.equal(preset.LIROAM_IT_DB_PREFIX, 'liroam_it_x_');
});
