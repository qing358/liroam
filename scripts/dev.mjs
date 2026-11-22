#!/usr/bin/env node
// liroam 本机一键编排：专用中间件（deploy/compose.yaml）+ Lilishop 后端 + 管理端/商家端/买家 PC 前端。
// 用法：node scripts/dev.mjs up [--build] [--ui=manager,seller,buyer|none] | down | status | health | demo
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawn, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

import {
  backendBuildArgs, backendEnv, buildConfig, ensureSecretsFile, parseEnv, readSdkmanJava, selectFrontends,
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

function runSql(file) {
  const sql = fs.readFileSync(path.join(rootDir, file), 'utf8');
  const result = spawnSync('docker', ['compose', '--env-file', portsPath, '--env-file', secretsPath, '-f', composeFile,
    'exec', '-T', 'mysql', 'sh', '-c', 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --batch lilishop'], { cwd: rootDir, input: sql, encoding: 'utf8' });
  if (result.status !== 0) throw new Error(`${file} 执行失败：${result.stderr}`);
  return result.stdout;
}

function applyDevSeed() {
  runSql('deploy/dev-seed.sql');
  compose(['exec', '-T', 'redis', 'redis-cli', 'DEL', '{setting}::VERIFICATION_SETTING', '{VERIFICATION}_'], { capture: true });
  console.log('已应用本机演示配置 deploy/dev-seed.sql');
}

function backendJar(module) {
  const targetDir = path.join(backendDir, module, 'target');
  if (!fs.existsSync(targetDir)) return null;
  const jar = fs.readdirSync(targetDir).find((f) => f.startsWith(`${module}-`) && f.endsWith('.jar'));
  return jar ? path.join(targetDir, jar) : null;
}

function buildBackend(javaHome) {
  console.log('\n$ mvn package（仓根 reactor：buyer-api、manager-api 及自研依赖，跳过测试）');
  const result = spawnSync('mvn', backendBuildArgs(BACKENDS.map((b) => b.module)), {
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

function startProcess(name, command, args, { cwd, env, port }, pids) {
  if (pids[name] && isAlive(pids[name].pid)) {
    console.log(`- ${name} 已在运行（pid ${pids[name].pid}）`);
    return;
  }
  if (portInUse(port)) throw new Error(`${name} 的端口 ${port} 已被其它进程占用；改 deploy/ports.env 或先释放该端口`);
  const logPath = path.join(logsDir, `${name}.log`);
  const fd = fs.openSync(logPath, 'a');
  const child = spawn(command, args, { cwd, env: { ...process.env, ...env }, detached: true, stdio: ['ignore', fd, fd] });
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
    startProcess(be.name, path.join(javaHome, 'bin/java'),
      ['-Xms256m', '-Xmx1g', '-jar', backendJar(be.module), '--spring.profiles.active=dev', `--server.port=${portOf(config[be.urlKey])}`],
      { cwd: workDir, env: javaEnv, port: portOf(config[be.urlKey]) }, pids);
  }
  for (const fe of frontends) {
    startProcess(fe.name, path.join(uiDir, fe.app, 'node_modules/.bin/vue-cli-service'), ['serve', '--host', '0.0.0.0', '--port', String(portOf(config[fe.urlKey]))],
      { cwd: path.join(uiDir, fe.app), env: { BROWSER: 'none' }, port: portOf(config[fe.urlKey]) }, pids);
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


// 本机演示目录（P2 竖切）：两家模拟供应商、资费表导入启用、旅行卡 SKU 绑定与定价、池卡。幂等，可重复执行；要求 up 已起好后端。
const DEMO_SHEET = 'docs/media/202611-资费报价-想象智能-202611(20261117）.xlsx';
const DEMO_POOL_SIZE = 20;

async function demo() {
  const config = buildConfig(loadEnv());
  for (const be of BACKENDS) {
    if (!(await backendUp(config[be.urlKey]).catch(() => false))) throw new Error(`${be.name} 未就绪，先执行 node scripts/dev.mjs up`);
  }
  const api = managerClient(config.managerApiUrl);
  await api.login();

  runSql('deploy/dev-demo.sql');
  const suppliers = parseTsv(runSql('deploy/dev-demo-suppliers.sql'));
  for (const sup of suppliers) {
    const sheets = await api.get(`/manager/liroam/tariff-sheets?supplierId=${sup.id}&pageSize=50`);
    if ((sheets.records || []).some((r) => r.status === 'ACTIVE')) continue;
    const form = new FormData();
    form.append('file', new Blob([fs.readFileSync(path.join(rootDir, DEMO_SHEET))]), path.basename(DEMO_SHEET));
    const report = await api.post(`/manager/liroam/tariff-sheets?supplierId=${sup.id}`, form);
    await api.post(`/manager/liroam/tariff-sheets/${report.sheetId}/activate`);
    console.log(`- ${sup.code}：资费表已导入并启用（#${report.sheetId}）`);
  }

  const skus = parseTsv(runSql('deploy/dev-demo.sql'));
  if (!skus.length) throw new Error('旅行卡 SKU 绑定为空：资费表里没有资源 45412_C 或缺日本/中国区域');
  await api.post('/manager/liroam/fx/sync');
  for (const sku of skus) {
    const price = await api.post(`/manager/liroam/sku-bindings/${sku.binding_id}/price`, { priceFen: Math.round(Number(sku.price) * 100) });
    console.log(`- SKU ${sku.li_goods_sku_id}（${sku.supplier}）售价 ${price.priceFen / 100} 元`);
  }
  const poolProducts = [...new Map(skus.filter((s) => s.supplier === 'SUP_A').map((s) => [s.card_product_id, s])).values()];
  for (const sku of poolProducts) {
    const csv = ['iccid'];
    for (let i = 1; i <= DEMO_POOL_SIZE; i += 1) csv.push(`89852${String(sku.card_product_id).padStart(6, '0')}${String(i).padStart(8, '0')}`);
    const form = new FormData();
    form.append('file', new Blob([csv.join('\n')]), 'demo-pool.csv');
    const report = await api.post(`/manager/liroam/pool/import?supplierId=${sku.supplier_id}&cardProductId=${sku.card_product_id}`, form);
    console.log(`- SUP_A 卡产品 #${sku.card_product_id} 池卡导入：${JSON.stringify(report)}`);
  }
  compose(['exec', '-T', 'redis', 'sh', '-c', 'redis-cli --scan --pattern "*GOODS*" | xargs -r redis-cli DEL'], { capture: true });
  console.log(`\n演示目录就绪。买家端商品页：${config.buyerPcUrl}/goodsDetail?skuId=9100000000000001001&goodsId=9100000000000000001`);
}

function parseTsv(out) {
  const lines = out.trim().split('\n').filter(Boolean);
  if (lines.length < 2) return [];
  const head = lines[0].split('\t');
  return lines.slice(1).map((line) => Object.fromEntries(line.split('\t').map((v, i) => [head[i], v])));
}

// 上游基线演示管理员（admin / 123456，见上游 lilishop.sql）；只在本机演示库使用
function managerClient(baseUrl) {
  let token = '';
  const call = async (method, url, body) => {
    const headers = { accessToken: token };
    let payload = body;
    if (body && !(body instanceof FormData)) {
      headers['Content-Type'] = 'application/json';
      payload = JSON.stringify(body);
    }
    const res = await fetch(`${baseUrl}${url}`, { method, headers, body: payload });
    const json = await res.json().catch(() => ({}));
    if (!json.success) throw new Error(`${method} ${url} 失败：${json.message ?? res.status}`);
    return json.result;
  };
  return {
    async login() {
      const form = new FormData();
      form.append('username', process.env.LIROAM_DEMO_ADMIN_USER || 'admin');
      form.append('password', crypto.createHash('md5').update(process.env.LIROAM_DEMO_ADMIN_PASSWORD || '123456').digest('hex'));
      const res = await fetch(`${baseUrl}/manager/passport/user/login`, { method: 'POST', headers: { uuid: `demo-${Date.now()}` }, body: form });
      const json = await res.json();
      if (!json.success) throw new Error(`管理端登录失败：${json.message}`);
      token = json.result.accessToken;
    },
    get: (url) => call('GET', url),
    post: (url, body) => call('POST', url, body),
  };
}

const command = process.argv[2] || 'status';
const handlers = { up, down, status, health, demo };
if (!handlers[command]) {
  console.error(`未知命令 ${command}；可用：up [--build] [--ui=manager,seller,buyer|none] | down | status | health | demo`);
  process.exit(2);
}
try {
  await handlers[command]();
} catch (error) {
  console.error(`\n失败：${error.message}`);
  process.exit(1);
}
