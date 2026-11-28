#!/usr/bin/env node
// 重活排队：mvn verify、dev.mjs up --build 等吃内存的命令经这里跑。所有 worktree 共用 git 公共目录下同一组 flock 槽位，
// 默认同时最多 2 个；可用内存低于门槛时不开新活，等内存回来再排。
// 用法：node scripts/heavy.mjs [--] <命令> [参数...]
// 可调：LIROAM_HEAVY_SLOTS（默认 2）、LIROAM_HEAVY_MIN_MEM_GB（默认 8）、LIROAM_HEAVY_POLL_MS（默认 5000）、LIROAM_HEAVY_LOCK_DIR
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawn, spawnSync } from 'node:child_process';
import { setTimeout as sleep } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

import { parseEnv, readSdkmanJava } from './lib/config.mjs';
import { availableMemGb, heavyEnv } from './lib/heavy.mjs';

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
const slots = Number(process.env.LIROAM_HEAVY_SLOTS || 2);
const minMemGb = Number(process.env.LIROAM_HEAVY_MIN_MEM_GB ?? 8);
const pollMs = Number(process.env.LIROAM_HEAVY_POLL_MS || 5000);

const secretsPath = path.join(mainCheckout, '.env');
const sdkJava = readSdkmanJava(checkout);
const sdkHome = sdkJava && path.join(os.homedir(), '.sdkman/candidates/java', sdkJava);
const env = heavyEnv(process.env, {
  checkout,
  mainCheckout,
  mainSecrets: fs.existsSync(secretsPath) ? parseEnv(fs.readFileSync(secretsPath, 'utf8')) : {},
  javaHome: process.env.LIROAM_JAVA_HOME || (sdkHome && fs.existsSync(sdkHome) ? sdkHome : null),
});

fs.mkdirSync(lockDir, { recursive: true });

function runInSlot(slot) {
  return new Promise((resolve, reject) => {
    const child = spawn('flock', ['-n', '-E', String(BUSY), path.join(lockDir, `slot-${slot}.lock`), ...command], { stdio: 'inherit', env, cwd: process.cwd() });
    child.on('error', reject);
    child.on('close', (code, signal) => resolve(code ?? (signal ? 128 + (os.constants.signals[signal] ?? 0) : 1)));
  });
}

let lastNotice = '';
function notice(text) {
  if (text === lastNotice) return;
  lastNotice = text;
  console.error(`[heavy] ${text}`);
}

for (;;) {
  const mem = availableMemGb();
  if (mem < minMemGb) {
    notice(`可用内存 ${mem.toFixed(1)}G 低于 ${minMemGb}G，暂不开跑，等待中（Ctrl-C 放弃）`);
  } else {
    for (let slot = 1; slot <= slots; slot++) {
      const code = await runInSlot(slot);
      if (code !== BUSY) process.exit(code);
    }
    notice(`${slots} 个重活槽位都在用，排队等待中（锁目录 ${lockDir}）`);
  }
  await sleep(pollMs);
}
