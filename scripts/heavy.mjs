#!/usr/bin/env node
// 重活排队：mvn verify、dev.mjs up --build 等吃内存的命令经这里跑。所有 worktree 共用 git 公共目录下同一组 flock 槽位，
// 按内存动态放行：最多 LIROAM_HEAVY_SLOTS（默认 8）个并发，且可用内存 ≥ 余量（默认 5G）+ 本任务预留（整仓 verify 约 4.5G，其它约 1.5G）才开新活；
// 任意两个任务的放行至少隔一个爬坡窗口（默认 30s），免得刚放行的进程还没吃满内存、又放行下一个。
// 用法：node scripts/heavy.mjs [--] <命令> [参数...]
// 可调：LIROAM_HEAVY_SLOTS（上限，默认 8）、LIROAM_HEAVY_MIN_MEM_GB（余量，默认 5）、LIROAM_HEAVY_RESERVE_GB（覆盖本任务预留）、LIROAM_HEAVY_RAMP_MS（默认 30000）、LIROAM_HEAVY_POLL_MS（默认 5000）、LIROAM_HEAVY_LOCK_DIR
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawn, spawnSync } from 'node:child_process';
import { setTimeout as sleep } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

import { parseEnv, readSdkmanJava } from './lib/config.mjs';
import { admits, availableMemGb, heavyEnv, reserveGb, slotEnv } from './lib/heavy.mjs';

// flock 拿不到锁时的退出码，与被包命令的退出码区分开
const BUSY = 199;

const argv = process.argv.slice(2);
const command = argv[0] === '--' ? argv.slice(1) : argv;
if (command.length === 0) {
  console.error('用法：node scripts/heavy.mjs [--] <命令> [参数...]，例如 node scripts/heavy.mjs mvn -B -pl ... -am verify');
  process.exit(2);
}

const checkout = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const commonDir = spawnSync('git', ['-C', checkout, 'rev-parse', '--path-format=absolute', '--git-common-dir'], { encoding: 'utf8' }).stdout.trim();
if (!commonDir) throw new Error(`${checkout} 不在 git 仓库里`);
const mainCheckout = path.dirname(commonDir);
const lockDir = process.env.LIROAM_HEAVY_LOCK_DIR || path.join(commonDir, 'liroam-heavy');
const slots = Number(process.env.LIROAM_HEAVY_SLOTS || 8);
const minMemGb = Number(process.env.LIROAM_HEAVY_MIN_MEM_GB ?? 5);
const pollMs = Number(process.env.LIROAM_HEAVY_POLL_MS || 5000);
const rampMs = Number(process.env.LIROAM_HEAVY_RAMP_MS ?? 30000);
const reserve = reserveGb(command);
const admitDir = path.join(lockDir, 'admit');

const secretsPath = path.join(mainCheckout, '.env');
const sdkJava = readSdkmanJava(checkout);
const sdkHome = sdkJava && path.join(os.homedir(), '.sdkman/candidates/java', sdkJava);
const env = heavyEnv(process.env, {
  mainSecrets: fs.existsSync(secretsPath) ? parseEnv(fs.readFileSync(secretsPath, 'utf8')) : {},
  javaHome: process.env.LIROAM_JAVA_HOME || (sdkHome && fs.existsSync(sdkHome) ? sdkHome : null),
});

fs.mkdirSync(lockDir, { recursive: true });

function runInSlot(slot) {
  return new Promise((resolve, reject) => {
    const child = spawn('flock', ['-n', '-E', String(BUSY), path.join(lockDir, `slot-${slot}.lock`), ...command], { stdio: 'inherit', env: slotEnv(env, slot), cwd: process.cwd() });
    child.on('error', reject);
    child.on('close', (code, signal) => resolve(code ?? (signal ? 128 + (os.constants.signals[signal] ?? 0) : 1)));
  });
}

// 放行闸：admit 目录的存在时间 = 距上次放行多久；mkdir 原子，只有一个等待者能抢到
function claimAdmit() {
  try {
    if (Date.now() - fs.statSync(admitDir).mtimeMs < rampMs) return false;
    fs.rmSync(admitDir, { recursive: true, force: true });
  } catch (e) {
    if (e.code !== 'ENOENT') throw e;
  }
  try {
    fs.mkdirSync(admitDir);
    return true;
  } catch (e) {
    if (e.code === 'EEXIST') return false;
    throw e;
  }
}

let lastNotice = '';
function notice(text) {
  if (text === lastNotice) return;
  lastNotice = text;
  console.error(`[heavy] ${text}`);
}

for (;;) {
  const mem = availableMemGb();
  if (!admits(mem, minMemGb, reserve)) {
    notice(`可用内存 ${mem.toFixed(1)}G 不够（余量 ${minMemGb}G + 本任务预留 ${reserve}G），暂不开跑，等待中（Ctrl-C 放弃）`);
  } else if (!claimAdmit()) {
    notice('前一个重活刚放行，等爬坡窗口过去再放行，排队等待中');
  } else {
    for (let slot = 1; slot <= slots; slot++) {
      const code = await runInSlot(slot);
      if (code !== BUSY) process.exit(code);
    }
    fs.rmSync(admitDir, { recursive: true, force: true });
    notice(`${slots} 个重活槽位都在用，排队等待中（锁目录 ${lockDir}）`);
  }
  await sleep(pollMs);
}
