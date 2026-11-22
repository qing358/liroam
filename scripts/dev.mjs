#!/usr/bin/env node
// liroam 本机一键编排：专用中间件（deploy/compose.yaml）+ Lilishop 后端 + 管理端/商家端/买家 PC 前端。
// 用法：node scripts/dev.mjs up [--build] [--ui=manager,seller,buyer|none] | down | status | health
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawn, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

import {
  backendBuildArgs, backendEnv, backendJvmArgs, buildConfig, ensureSecretsFile, frontendEnv, limitedCommand, parseEnv, readSdkmanJava, selectFrontends,
} from './lib/config.mjs';
import { renderPcConfig } from '../shop/docker/scripts/dev-env-lib.mjs';

const rootDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const backendDir = path.join(rootDir, 'shop/lilishop');
const uiDir = path.join(rootDir, 'shop/lilishop-ui');
const portsPath = path.join(rootDir, 'deploy/ports.env');
const secretsPath = path.join(rootDir, '.env');
const composeFile = path.join(rootDir, 'deploy/compose.yaml');
const runDir = path.join(rootDir, '.run');
const logsDir = path.join(runDir, 'logs');
const pidPath = path.join(runDir, 'pids.json');

const BACKENDS = [
  { name: 'manager-api', module: 'manager-api', urlKey: 'managerApiUrl', worker: true },
  { name: 'buyer-api', module: 'buyer-api', urlKey: 'buyerApiUrl', worker: false },
];
const FRONTENDS = [
  { name: 'manager-ui', app: 'manager', urlKey: 'managerUrl', prefix: '/manager' },
  { name: 'seller-ui', app: 'seller', urlKey: 'sellerUrl', prefix: '/store' },
  { name: 'buyer-ui', app: 'buyer', urlKey: 'buyerPcUrl', prefix: '' },
];

function loadEnv() {
  const ports = parseEnv(fs.readFileSync(portsPath, 'utf8'));
  const secrets = fs.existsSync(secretsPath) ? parseEnv(fs.readFileSync(secretsPath, 'utf8')) : {};
  return { ...ports, ...secrets };
}

function compose(args, { capture = false } = {}) {
  const result = spawnSync('docker', ['compose', '--env-file', portsPath, '--env-file', secretsPath, '-f', composeFile, ...args], {
    cwd: rootDir,
    stdio: capture ? ['ignore', 'pipe', 'pipe'] : 'inherit',
    encoding: 'utf8',
  });
  if (result.status !== 0 && !capture) throw new Error(`docker compose ${args.join(' ')} 失败`);
  return result;
}

function resolveJavaHome() {
  const fromEnv = process.env.LIROAM_JAVA_HOME;
  const version = readSdkmanJava(rootDir);
  const candidates = [fromEnv, version && path.join(os.homedir(), '.sdkman/candidates/java', version)].filter(Boolean);
  for (const home of candidates) {
    const java = path.join(home, 'bin/java');
    if (!fs.existsSync(java)) continue;
    const out = spawnSync(java, ['-version'], { encoding: 'utf8' });
    if (/version "21\./.test(out.stderr)) return home;
    throw new Error(`${home} 不是 JDK 21（${out.stderr.split('\n')[0]}）。Lilishop v5 需要 Java 21，不在旧 JDK 上运行。`);
  }
  throw new Error(`未找到 JDK 21：按 .sdkmanrc 执行 sdk install java ${version ?? '21.x-tem'}，或设置 LIROAM_JAVA_HOME。`);
}

function applyDevSeed() {
  const sql = fs.readFileSync(path.join(rootDir, 'deploy/dev-seed.sql'), 'utf8');
  const result = spawnSync('docker', ['compose', '--env-file', portsPath, '--env-file', secretsPath, '-f', composeFile,
    'exec', '-T', 'mysql', 'sh', '-c', 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot lilishop'], { cwd: rootDir, input: sql, encoding: 'utf8' });
  if (result.status !== 0) throw new Error(`本机演示配置 deploy/dev-seed.sql 执行失败：${result.stderr}`);
  compose(['exec', '-T', 'redis', 'redis-cli', 'DEL', '{setting}::VERIFICATION_SETTING', '{VERIFICATION}_'], { capture: true });
  console.log('已应用本机演示配置 deploy/dev-seed.sql');
}

function backendJar(module) {
  const targetDir = path.join(backendDir, module, 'target');
  if (!fs.existsSync(targetDir)) return null;
  const jar = fs.readdirSync(targetDir).find((f) => f.startsWith(`${module}-`) && f.endsWith('.jar'));
  return jar ? path.join(targetDir, jar) : null;
}

// systemd-run --scope 需要 systemd 为 1 号进程且有 root 权限；否则只降优先级，不设硬上限
const systemd = fs.existsSync('/run/systemd/system') && process.getuid?.() === 0;

function buildBackend(javaHome) {
  console.log('\n$ mvn package（仓根 reactor：buyer-api、manager-api 及自研依赖，跳过测试）');
  const [cmd, args] = limitedCommand('build', 'mvn', backendBuildArgs(BACKENDS.map((b) => b.module)), { systemd });
  const result = spawnSync(cmd, args, {
    cwd: rootDir,
    stdio: 'inherit',
    env: { ...process.env, JAVA_HOME: javaHome, PATH: `${javaHome}/bin:${process.env.PATH}` },
  });
  if (result.status !== 0) throw new Error('后端构建失败');
}

// 有上游 yarn.lock（seller）就按锁冻结安装；其余跟上游 docker 仓 dev-env.mjs 一致用 npm install——
// 上游 pnpm-lock.yaml 与 package.json 不同步（v5.0.6 manager 锁里仍是 vuex），冻结安装不可用
function installFrontend(app) {
  const cwd = path.join(uiDir, app);
  if (fs.existsSync(path.join(cwd, 'node_modules'))) return;
  const [cmd, ...args] = fs.existsSync(path.join(cwd, 'yarn.lock'))
    ? ['corepack', 'yarn@1.22.22', 'install', '--frozen-lockfile']
    : ['npm', 'install', '--no-audit', '--no-fund'];
  console.log(`\n$ ${cmd} ${args.join(' ')}（${app}）`);
  const result = spawnSync(cmd, args, { cwd, stdio: 'inherit', env: { ...process.env, COREPACK_ENABLE_DOWNLOAD_PROMPT: '0' } });
  if (result.status !== 0) throw new Error(`${app} 依赖安装失败`);
}

function writeFrontendConfig(app, prefix, config) {
  // 上游 index.html 在开发模式加载 public/config.local.js 覆盖 config.js（挂接点见 docs/upstream.md）
  const content = renderPcConfig({ ...config, uniappH5Url: '', prefix });
  fs.writeFileSync(path.join(uiDir, app, 'public', 'config.local.js'), content);
}

function readPids() {
  return fs.existsSync(pidPath) ? JSON.parse(fs.readFileSync(pidPath, 'utf8')) : {};
}

function isAlive(pid) {
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
}

function portOf(url) {
  return Number(new URL(url).port);
}

function portInUse(port) {
  const out = spawnSync('ss', ['-Hlnt', `sport = :${port}`], { encoding: 'utf8' });
  return out.stdout.trim().length > 0;
}

function startProcess(name, kind, command, args, { cwd, env, port }, pids) {
  if (pids[name] && isAlive(pids[name].pid)) {
    console.log(`- ${name} 已在运行（pid ${pids[name].pid}）`);
    return;
  }
  if (portInUse(port)) throw new Error(`${name} 的端口 ${port} 已被其它进程占用；改 deploy/ports.env 或先释放该端口`);
  const logPath = path.join(logsDir, `${name}.log`);
  const fd = fs.openSync(logPath, 'a');
  const [cmd, limitedArgs] = limitedCommand(kind, command, args, { systemd });
  const child = spawn(cmd, limitedArgs, { cwd, env: { ...process.env, ...env }, detached: true, stdio: ['ignore', fd, fd] });
  child.unref();
  fs.closeSync(fd);
  pids[name] = { pid: child.pid, port, log: logPath };
  fs.writeFileSync(pidPath, JSON.stringify(pids, null, 2));
  console.log(`- 启动 ${name}（pid ${child.pid}，端口 ${port}，日志 ${path.relative(rootDir, logPath)}）`);
}

async function httpGet(url, timeoutMs = 5000) {
  const res = await fetch(url, { signal: AbortSignal.timeout(timeoutMs) });
  return { status: res.status, body: await res.text() };
}

async function waitFor(label, probe, timeoutMs) {
  const started = Date.now();
  process.stdout.write(`等待 ${label} `);
  while (Date.now() - started < timeoutMs) {
    if (await probe().catch(() => false)) {
      process.stdout.write(' OK\n');
      return;
    }
    process.stdout.write('.');
    await new Promise((r) => setTimeout(r, 3000));
  }
  process.stdout.write('\n');
  throw new Error(`${label} 在 ${Math.round(timeoutMs / 1000)}s 内未就绪`);
}

async function backendUp(url) {
  const { status, body } = await httpGet(`${url}/actuator/health`);
  return status === 200 && JSON.parse(body).status === 'UP';
}

async function frontendUp(url, apiUrl) {
  const page = await httpGet(url);
  if (page.status !== 200) return false;
  const local = await httpGet(`${url}/config.local.js`);
  return local.status === 200 && local.body.includes(apiUrl);
}

function containerHealth(service) {
  const ps = compose(['ps', '-q', service], { capture: true });
  const id = ps.stdout.trim();
  if (!id) return 'absent';
  const out = spawnSync('docker', ['inspect', '-f', '{{.State.Health.Status}}', id], { encoding: 'utf8' });
  return out.stdout.trim() || 'unknown';
}

async function up() {
  fs.mkdirSync(logsDir, { recursive: true });
  const { created } = ensureSecretsFile(secretsPath);
  if (created.length) console.log(`已生成本机口令到 .env：${created.join('、')}`);
  const env = loadEnv();
  const config = buildConfig(env);
  const javaHome = resolveJavaHome();
  const frontends = selectFrontends(process.argv, FRONTENDS);
  if (!systemd) console.log('未检测到 systemd（或非 root）：进程只降优先级，不设 CPU / 内存硬上限');

  console.log('\n$ docker compose up -d --wait（liroam 专用 MySQL / Redis）');
  compose(['up', '-d', '--wait']);
  applyDevSeed();

  if (process.argv.includes('--build') || BACKENDS.some((b) => !backendJar(b.module))) buildBackend(javaHome);
  for (const fe of frontends) {
    installFrontend(fe.app);
    writeFrontendConfig(fe.app, fe.prefix, config);
  }

  const pids = readPids();
  for (const be of BACKENDS) {
    const javaEnv = backendEnv(env, config, { worker: be.worker });
    const workDir = path.join(runDir, be.name);
    fs.mkdirSync(workDir, { recursive: true });
    startProcess(be.name, 'backend', path.join(javaHome, 'bin/java'),
      [...backendJvmArgs(), '-jar', backendJar(be.module), '--spring.profiles.active=dev', `--server.port=${portOf(config[be.urlKey])}`],
      { cwd: workDir, env: javaEnv, port: portOf(config[be.urlKey]) }, pids);
  }
  for (const fe of frontends) {
    startProcess(fe.name, 'frontend', path.join(uiDir, fe.app, 'node_modules/.bin/vue-cli-service'), ['serve', '--host', '0.0.0.0', '--port', String(portOf(config[fe.urlKey]))],
      { cwd: path.join(uiDir, fe.app), env: frontendEnv(), port: portOf(config[fe.urlKey]) }, pids);
  }

  for (const be of BACKENDS) await waitFor(be.name, () => backendUp(config[be.urlKey]), 300000);
  for (const fe of frontends) {
    const apiUrl = fe.app === 'buyer' ? config.buyerApiUrl : config.managerApiUrl;
    await waitFor(fe.name, () => frontendUp(config[fe.urlKey], apiUrl), 300000);
  }
  printUrls(config);
}

function killTree(pid) {
  try {
    process.kill(-pid, 'SIGTERM');
  } catch {
    try {
      process.kill(pid, 'SIGTERM');
    } catch {
      /* 已退出 */
    }
  }
}

function down() {
  const pids = readPids();
  for (const [name, meta] of Object.entries(pids)) {
    if (isAlive(meta.pid)) {
      killTree(meta.pid);
      console.log(`已停止 ${name}（pid ${meta.pid}）`);
    }
  }
  fs.rmSync(pidPath, { force: true });
  if (fs.existsSync(secretsPath)) {
    console.log('\n$ docker compose stop（保留数据卷）');
    compose(['stop']);
  }
}

function printUrls(config) {
  console.log('\n本机访问地址：');
  console.log(`- 平台管理端  ${config.managerUrl}`);
  console.log(`- 商家端      ${config.sellerUrl}`);
  console.log(`- 买家 PC 端  ${config.buyerPcUrl}`);
  console.log(`- manager-api ${config.managerApiUrl}`);
  console.log(`- buyer-api   ${config.buyerApiUrl}`);
  console.log(`- MySQL       127.0.0.1:${config.mysqlPort}（库 lilishop，口令见 .env）`);
  console.log(`- Redis       127.0.0.1:${config.redisPort}`);
}

function status() {
  const config = buildConfig(loadEnv());
  printUrls(config);
  console.log('\n进程：');
  for (const [name, meta] of Object.entries(readPids())) {
    console.log(`- ${name}: pid=${meta.pid} ${isAlive(meta.pid) ? '运行中' : '已退出'} 端口=${meta.port}`);
  }
}

async function health() {
  const env = loadEnv();
  const config = buildConfig(env);
  const checks = [];
  const check = async (name, fn) => {
    try {
      const detail = await fn();
      checks.push({ name, ok: true, detail });
    } catch (error) {
      checks.push({ name, ok: false, detail: error.message });
    }
  };
  const expect = (cond, okText, failText) => {
    if (!cond) throw new Error(failText);
    return okText;
  };

  for (const service of ['mysql', 'redis']) {
    await check(`${service} 容器`, async () => {
      const h = fs.existsSync(secretsPath) ? containerHealth(service) : 'absent';
      return expect(h === 'healthy', 'healthy', `状态 ${h}`);
    });
  }
  for (const be of BACKENDS) {
    await check(be.name, async () => expect(await backendUp(config[be.urlKey]), `${config[be.urlKey]}/actuator/health UP`, `${config[be.urlKey]} 未就绪`));
  }
  // 只检查 up 实际启动过的前端（--ui 按需启动）；启动后又挂掉的仍在 pids 里，照样判失败
  const pids = readPids();
  for (const fe of FRONTENDS.filter((f) => pids[f.name])) {
    const apiUrl = fe.app === 'buyer' ? config.buyerApiUrl : config.managerApiUrl;
    await check(fe.name, async () => expect(await frontendUp(config[fe.urlKey], apiUrl), `${config[fe.urlKey]} 200，API 指向 ${apiUrl}`, `${config[fe.urlKey]} 未就绪或未指向 ${apiUrl}`));
  }
  await check('登录滑块', async () => {
    const res = await fetch(`${config.managerApiUrl}/manager/common/slider/LOGIN`, { headers: { uuid: `health-${Date.now()}` } });
    const body = await res.json();
    return expect(body.success === true && Boolean(body.result?.backImage), '可生成（滑块素材已就绪）', `生成失败：${body.message ?? res.status}`);
  });

  for (const c of checks) console.log(`${c.ok ? 'PASS' : 'FAIL'}  ${c.name.padEnd(12)} ${c.detail}`);
  const failed = checks.filter((c) => !c.ok).length;
  console.log(failed ? `\n${failed} 项未通过` : '\n全部通过');
  process.exitCode = failed ? 1 : 0;
}

const command = process.argv[2] || 'status';
const handlers = { up, down, status, health };
if (!handlers[command]) {
  console.error(`未知命令 ${command}；可用：up [--build] [--ui=manager,seller,buyer|none] | down | status | health`);
  process.exit(2);
}
try {
  await handlers[command]();
} catch (error) {
  console.error(`\n失败：${error.message}`);
  process.exit(1);
}
