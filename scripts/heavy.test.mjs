import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

import { heavyEnv, slotEnv } from './lib/heavy.mjs';

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

test('LIROAM_HEAVY_SLOTS=2 时同时最多 2 个重活在跑，第 3 个排队等槽位，全部最终完成', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'heavy-'));
  const log = path.join(dir, 'events.log');
  const job = `echo start >> ${log}; sleep 0.6; echo end >> ${log}`;
  const results = await Promise.all([1, 2, 3, 4].map(() => run(['sh', '-c', job], queueEnv(dir, { LIROAM_HEAVY_SLOTS: '2' }))));
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

test('默认同时最多 4 个重活（本机停掉 qianba-dev 四个容器后内存充裕），第 5 个排队', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'heavy-'));
  const log = path.join(dir, 'events.log');
  const job = `echo start >> ${log}; sleep 0.8; echo end >> ${log}`;
  const results = await Promise.all([1, 2, 3, 4, 5, 6].map(() => run(['sh', '-c', job], queueEnv(dir))));
  assert.deepEqual(results.map((r) => r.code), [0, 0, 0, 0, 0, 0]);
  let running = 0;
  let peak = 0;
  for (const event of fs.readFileSync(log, 'utf8').trim().split('\n')) {
    running += event === 'start' ? 1 : -1;
    peak = Math.max(peak, running);
  }
  assert.equal(peak, 4);
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

test('heavyEnv：补主检出口令与 JDK；已设的口令不覆盖；不设库前缀（库前缀按槽位设，见 slotEnv）', () => {
  const base = { PATH: '/usr/bin' };
  const env = heavyEnv(base, { mainSecrets: { LIROAM_MYSQL_ROOT_PASSWORD: 'pw' }, javaHome: '/jdk21' });
  assert.equal(env.LIROAM_MYSQL_ROOT_PASSWORD, 'pw');
  assert.equal(env.JAVA_HOME, '/jdk21');
  assert.equal(env.PATH, '/jdk21/bin:/usr/bin');
  assert.ok(!('LIROAM_IT_DB_PREFIX' in env));
  assert.ok(!('JAVA_HOME' in heavyEnv(base, { mainSecrets: {}, javaHome: null })));
  assert.equal(heavyEnv({ ...base, LIROAM_MYSQL_ROOT_PASSWORD: 'mine' }, { mainSecrets: { LIROAM_MYSQL_ROOT_PASSWORD: 'pw' }, javaHome: null }).LIROAM_MYSQL_ROOT_PASSWORD, 'mine');
});

test('slotEnv：未手设库前缀时按槽位设前缀，不同槽位互不共库；手设的优先', () => {
  assert.equal(slotEnv({}, 1).LIROAM_IT_DB_PREFIX, 'liroam_it_slot1_');
  assert.equal(slotEnv({}, 2).LIROAM_IT_DB_PREFIX, 'liroam_it_slot2_');
  assert.equal(slotEnv({ LIROAM_IT_DB_PREFIX: 'liroam_it_mine_' }, 1).LIROAM_IT_DB_PREFIX, 'liroam_it_mine_');
});

test('经 heavy 跑的命令拿到槽位库前缀', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'heavy-'));
  const out = path.join(dir, 'prefix');
  const r = await run(['sh', '-c', `printf %s "$LIROAM_IT_DB_PREFIX" > ${out}`], queueEnv(dir, { LIROAM_IT_DB_PREFIX: '' }));
  assert.equal(r.code, 0);
  assert.match(fs.readFileSync(out, 'utf8'), /^liroam_it_slot[12]_$/);
});
