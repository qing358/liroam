#!/usr/bin/env node
// liroam 本机一键编排：专用中间件（deploy/compose.yaml）+ Lilishop 后端 + 管理端/商家端/买家 PC 前端；新买家站 buyer-web 只在 --ui 点名时起。
// 用法：node scripts/dev.mjs up [--build] [--ui=manager,seller,buyer,buyer-web|none] [--backend-from=<checkout>] | stop-backends | down | status | health | demo | backup | restore-drill [文件] | restore <文件> --yes | rotate-data-key | retire-data-key <版本号>
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawn, spawnSync } from 'node:child_process';
import { pipeline } from 'node:stream/promises';
import { fileURLToPath } from 'node:url';
import zlib from 'node:zlib';

import {
  backendBuildArgs, backendCheckout, backendEnv, buildConfig, buyerWebEnv, devAssetsEnv, devSeedVars, ensureSecretsFile, frontendInstallCommand, parseEnv, readSdkmanJava, selectFrontends, sqlSetVars,
} from './lib/config.mjs';
import { backupFileName, compareCounts, latestBackup, parseCounts, restoreArgs } from './lib/backup.mjs';
import { DATA_KEY_PREFIX, dataKeyVersions, nextDataKey, removeEnvLine, retireBlockers } from './lib/data-keys.mjs';
import { renderPcConfig } from '../shop/docker/scripts/dev-env-lib.mjs';

const rootDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const uiDir = path.join(rootDir, 'shop/lilishop-ui');
const portsPath = path.join(rootDir, 'deploy/ports.env');
const secretsPath = path.join(rootDir, '.env');
const composeFile = path.join(rootDir, 'deploy/compose.yaml');
const runDir = path.join(rootDir, '.run');
const logsDir = path.join(runDir, 'logs');
const pidPath = path.join(runDir, 'pids.json');
const backupDir = path.join(rootDir, 'temp/backups');

const BACKENDS = [
  { name: 'manager-api', module: 'manager-api', urlKey: 'managerApiUrl', worker: true, devAssets: true },
  { name: 'buyer-api', module: 'buyer-api', urlKey: 'buyerApiUrl', worker: false, devAssets: false },
];
// kind：vue-cli = 上游四端（API 地址写 public/config.local.js）；nuxt = 新买家站（API 地址经运行时配置环境变量注入）
const FRONTENDS = [
  { name: 'manager-ui', app: 'manager', dir: path.join(uiDir, 'manager'), kind: 'vue-cli', urlKey: 'managerUrl', apiKey: 'managerApiUrl', prefix: '/manager' },
  { name: 'seller-ui', app: 'seller', dir: path.join(uiDir, 'seller'), kind: 'vue-cli', urlKey: 'sellerUrl', apiKey: 'managerApiUrl', prefix: '/store' },
  { name: 'buyer-ui', app: 'buyer', dir: path.join(uiDir, 'buyer'), kind: 'vue-cli', urlKey: 'buyerPcUrl', apiKey: 'buyerApiUrl', prefix: '' },
  { name: 'buyer-web', app: 'buyer-web', dir: path.join(rootDir, 'apps/buyer-web'), kind: 'nuxt', urlKey: 'buyerWebUrl', apiKey: 'buyerApiUrl', optIn: true },
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

function runSql(file, vars = {}) {
  const sql = sqlSetVars(vars) + fs.readFileSync(path.join(rootDir, file), 'utf8');
  const result = spawnSync('docker', ['compose', '--env-file', portsPath, '--env-file', secretsPath, '-f', composeFile,
    'exec', '-T', 'mysql', 'sh', '-c', 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --batch lilishop'], { cwd: rootDir, input: sql, encoding: 'utf8' });
  if (result.status !== 0) throw new Error(`${file} 执行失败：${result.stderr}`);
  return result.stdout;
}

function applyDevSeed(config) {
  runSql('deploy/dev-seed.sql', devSeedVars(config));
  compose(['exec', '-T', 'redis', 'redis-cli', 'DEL', '{setting}::VERIFICATION_SETTING', '{VERIFICATION}_'], { capture: true });
  console.log('已应用本机演示配置 deploy/dev-seed.sql');
}

// 文章分类树缓存在 Redis 且不过期；迁移或演示 SQL 直接写库后要删掉，帮助中心才看得到新分类
function clearArticleCategoryCache() {
  compose(['exec', '-T', 'redis', 'redis-cli', 'DEL', '{ARTICLE_CATEGORY}_'], { capture: true });
}

function backendJar(module, checkout = rootDir) {
  const targetDir = path.join(checkout, 'shop/lilishop', module, 'target');
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

function installFrontend(fe) {
  const cwd = fe.dir;
  if (fs.existsSync(path.join(cwd, 'node_modules'))) return;
  const [cmd, ...args] = frontendInstallCommand(fs.readdirSync(cwd));
  console.log(`\n$ ${cmd} ${args.join(' ')}（${fe.app}）`);
  const result = spawnSync(cmd, args, { cwd, stdio: 'inherit', env: { ...process.env, COREPACK_ENABLE_DOWNLOAD_PROMPT: '0' } });
  if (result.status !== 0) throw new Error(`${fe.app} 依赖安装失败`);
}

function writeFrontendConfig(fe, config) {
  if (fe.kind !== 'vue-cli') return;
  // 上游 index.html 在开发模式加载 public/config.local.js 覆盖 config.js（挂接点见 docs/upstream.md）
  const content = renderPcConfig({ ...config, uniappH5Url: '', prefix: fe.prefix });
  fs.writeFileSync(path.join(fe.dir, 'public', 'config.local.js'), content);
}

function frontendCommand(fe, config) {
  const port = String(portOf(config[fe.urlKey]));
  if (fe.kind === 'nuxt') {
    return [path.join(fe.dir, 'node_modules/.bin/nuxt'), ['dev', '--host', '0.0.0.0', '--port', port], { ...buyerWebEnv(config), NUXT_TELEMETRY_DISABLED: '1' }];
  }
  return [path.join(fe.dir, 'node_modules/.bin/vue-cli-service'), ['serve', '--host', '0.0.0.0', '--port', port], { BROWSER: 'none' }];
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

// Nuxt 把 runtimeConfig.public 序列化进页面，页面里有 API 地址即说明注入生效
async function frontendUp(fe, config) {
  const url = config[fe.urlKey];
  const apiUrl = config[fe.apiKey];
  const page = await httpGet(url);
  if (page.status !== 200) return false;
  if (fe.kind === 'nuxt') return page.body.includes(apiUrl);
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
  const checkout = backendCheckout(process.argv, rootDir);

  console.log('\n$ docker compose up -d --wait（liroam 专用 MySQL / Redis）');
  compose(['up', '-d', '--wait']);
  applyDevSeed(config);

  if (checkout !== rootDir) {
    const missing = BACKENDS.filter((b) => !backendJar(b.module, checkout)).map((b) => b.module);
    if (missing.length) throw new Error(`${checkout} 里没有构建好的 ${missing.join('、')}：先在那份 checkout 执行 mvn ${backendBuildArgs(BACKENDS.map((b) => b.module)).join(' ')}`);
    console.log(`后端用 ${checkout} 的 jar（回滚演练 / 上一版）`);
  } else if (process.argv.includes('--build') || BACKENDS.some((b) => !backendJar(b.module))) buildBackend(javaHome);
  for (const fe of frontends) {
    installFrontend(fe);
    writeFrontendConfig(fe, config);
  }

  const pids = readPids();
  for (const be of BACKENDS) {
    const javaEnv = { ...backendEnv(env, config, { worker: be.worker }), ...(be.devAssets ? devAssetsEnv(rootDir) : {}) };
    const workDir = path.join(runDir, be.name);
    fs.mkdirSync(workDir, { recursive: true });
    startProcess(be.name, path.join(javaHome, 'bin/java'),
      ['-Xms256m', '-Xmx1g', '-jar', backendJar(be.module, checkout), '--spring.profiles.active=dev', `--server.port=${portOf(config[be.urlKey])}`],
      { cwd: workDir, env: javaEnv, port: portOf(config[be.urlKey]) }, pids);
  }
  for (const fe of frontends) {
    const [cmd, args, feEnv] = frontendCommand(fe, config);
    startProcess(fe.name, cmd, args, { cwd: fe.dir, env: feEnv, port: portOf(config[fe.urlKey]) }, pids);
  }

  for (const be of BACKENDS) await waitFor(be.name, () => backendUp(config[be.urlKey]), 300000);
  clearArticleCategoryCache();
  for (const fe of frontends) await waitFor(fe.name, () => frontendUp(fe, config), 300000);
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
  console.log(`- 新买家站    ${config.buyerWebUrl}（--ui=buyer-web 时启动）`);
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
    const apiUrl = config[fe.apiKey];
    await check(fe.name, async () => expect(await frontendUp(fe, config), `${config[fe.urlKey]} 200，API 指向 ${apiUrl}`, `${config[fe.urlKey]} 未就绪或未指向 ${apiUrl}`));
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


// 本机演示目录：两家模拟供应商、资费表导入启用、全卡型旅行卡 SKU 绑定（纯流量 / 语音短信 / 实名 / 实体卡 / 纯软卡）与定价、池卡，
// 物流轨迹来源切到模拟快递、模拟渠道 JD / XHS 与 SKU 映射、演示分销员与佣金策略。幂等，可重复执行；要求 up 已起好后端。
const DEMO_SHEET = 'docs/media/202611-资费报价-想象智能-202611(20261117）.xlsx';
const DEMO_SOFT = { supplier: 'SUP_B', cardType: '45412_C' };
const DEMO_POOL_SIZE = 20;
const DEMO_COMMISSION_POLICY = {
  code: 'DEFAULT', mode: 'PAID_RATE', scopeType: 'GLOBAL', poolRate: 0.1, directRate: 0.8, inviteRate: 0.2,
  includeChannelOrders: false, settleDays: 0, settleAnchor: 'DELIVERED', withdrawApproval: true,
};

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
    const sheets = await api.get(`/manager/liroam/tariff-sheets?supplierId=${sup.id}&pageNumber=1&pageSize=50`);
    if ((sheets.records || []).some((r) => r.status === 'ACTIVE')) continue;
    const form = new FormData();
    form.append('file', new Blob([fs.readFileSync(path.join(rootDir, DEMO_SHEET))]), path.basename(DEMO_SHEET));
    const report = await api.post(`/manager/liroam/tariff-sheets?supplierId=${sup.id}`, form);
    await api.post(`/manager/liroam/tariff-sheets/${report.sheetId}/activate`);
    console.log(`- ${sup.code}：资费表已导入并启用（#${report.sheetId}）`);
  }
  const softProduct = (await api.get('/manager/liroam/catalog/card-products'))
    .find((p) => p.supplierCode === DEMO_SOFT.supplier && p.name === DEMO_SOFT.cardType);
  if (softProduct && !softProduct.allowedForms.split(',').includes('SOFT')) {
    await api.put(`/manager/liroam/catalog/card-products/${softProduct.id}/soft`, { enabled: true });
    console.log(`- ${DEMO_SOFT.supplier} 卡产品 ${DEMO_SOFT.cardType} 已开卖软卡`);
  }
  const shipping = await api.get('/manager/liroam/cards/settings/shipping');
  if (shipping.trackingSource !== 'mock') {
    await api.put('/manager/liroam/cards/settings/shipping', { multiWaybillMode: shipping.multiWaybillMode, trackingSource: 'mock' });
    console.log('- 物流轨迹来源切到模拟快递（mock）');
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
    const report = await api.post(`/manager/liroam/cards/pool/import?supplierId=${sku.supplier_id}&cardProductId=${sku.card_product_id}`, form);
    console.log(`- SUP_A 卡产品 #${sku.card_product_id} 池卡导入：${JSON.stringify(report)}`);
  }
  const channel = parseTsv(runSql('deploy/dev-demo-channel.sql'));
  for (const row of channel) console.log(`- 渠道 ${row.code}（${row.adapter_key}）：${row.external_sku_id} → SKU ${row.li_goods_sku_id}`);
  const [agent] = parseTsv(runSql('deploy/dev-demo-commission.sql'));
  console.log(agent && agent.agent !== 'NULL' ? `- 分销：演示买家为分销员 ${agent.agent}（邀请人 ${agent.inviter}）` : '- 分销：演示买家没有分销员记录，跳过');
  if (!(await api.get('/manager/liroam/commission/policies')).length) {
    await api.post('/manager/liroam/commission/policies', DEMO_COMMISSION_POLICY);
    console.log('- 佣金策略 DEFAULT：实付 10%，一级 80% / 二级 20%，交码当时可提现（演示值，正式比例向客户要）');
  }
  const help = parseTsv(runSql('deploy/dev-demo-help.sql'));
  clearArticleCategoryCache();
  console.log(`- 帮助中心演示文章 ${help.length} 篇（设备兼容为演示品牌/机型，正式清单由运营发布）`);
  compose(['exec', '-T', 'redis', 'sh', '-c', 'redis-cli --scan --pattern "*GOODS*" | xargs -r redis-cli DEL'], { capture: true });
  console.log('\n演示目录就绪。买家端商品页：');
  for (const [label, n] of [['纯流量 eSIM', 1], ['语音短信 eSIM', 6], ['实名后交付 eSIM', 7], ['实体卡（寄送）', 8], ['纯软卡', 9]]) {
    console.log(`- ${label}：${config.buyerPcUrl}/goodsDetail?skuId=910000000000000100${n}&goodsId=910000000000000000${n}`);
  }
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
    put: (url, body) => call('PUT', url, body),
  };
}

// 备份与恢复演练（§3.6）：本机演练用逻辑全量备份；生产按上线清单由 DBA 做全量 + binlog 增量，同一套演练口径。
const DB = 'lilishop';
const DRILL_DB = 'lilishop_restore_drill';
// 与 compose 初始化的 lilishop 一致（my.cnf 的 utf8mb4_unicode_ci）；不写则 MySQL 8 默认 utf8mb4_0900_ai_ci
const DB_CHARSET = 'DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci';
// 不带 --databases：备份里没有 CREATE DATABASE / USE，恢复目标库由执行人指定，演练不会误写源库
const DUMP_ARGS = '--single-transaction --routines --triggers --events --hex-blob --no-tablespaces --set-gtid-purged=OFF';

function mysqlSh(command, { stdin = 'ignore' } = {}) {
  return spawn('docker', ['compose', '--env-file', portsPath, '--env-file', secretsPath, '-f', composeFile,
    'exec', '-T', 'mysql', 'sh', '-c', `MYSQL_PWD="$MYSQL_ROOT_PASSWORD" ${command}`], { cwd: rootDir, stdio: [stdin, 'pipe', 'pipe'] });
}

function finished(child, label) {
  let stderr = '';
  child.stderr.on('data', (d) => { stderr += d; });
  return new Promise((resolve, reject) => child.on('close', (code) => (code === 0 ? resolve() : reject(new Error(`${label} 失败：${stderr.trim()}`)))));
}

function query(db, sql) {
  const result = spawnSync('docker', ['compose', '--env-file', portsPath, '--env-file', secretsPath, '-f', composeFile,
    'exec', '-T', 'mysql', 'sh', '-c', `MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --batch ${db}`], { cwd: rootDir, input: sql, encoding: 'utf8' });
  if (result.status !== 0) throw new Error(`${db} 查询失败：${result.stderr}`);
  return result.stdout;
}

function tableCounts(db) {
  const tables = query(db, `SELECT table_name FROM information_schema.tables WHERE table_schema = '${db}' AND table_type = 'BASE TABLE' ORDER BY table_name;`)
    .trim().split('\n').slice(1).filter(Boolean);
  if (!tables.length) return {};
  return parseCounts(query(db, `${tables.map((t) => `SELECT '${t}' AS t, COUNT(*) AS n FROM \`${t}\``).join(' UNION ALL ')};`));
}

async function backup() {
  fs.mkdirSync(backupDir, { recursive: true });
  const file = path.join(backupDir, backupFileName(new Date()));
  const dump = mysqlSh(`mysqldump -uroot ${DUMP_ARGS} ${DB}`);
  const done = finished(dump, 'mysqldump');
  try {
    await Promise.all([pipeline(dump.stdout, zlib.createGzip(), fs.createWriteStream(file)), done]);
  } catch (error) {
    fs.rmSync(file, { force: true });
    throw error;
  }
  console.log(`备份完成：${path.relative(rootDir, file)}（${(fs.statSync(file).size / 1024).toFixed(0)} KB）`);
  return file;
}

async function restoreDrill(arg = process.argv[3]) {
  const name = arg ? path.resolve(arg) : latestBackup(fs.existsSync(backupDir) ? fs.readdirSync(backupDir) : []);
  if (!name) throw new Error('temp/backups 下没有备份，先执行 node scripts/dev.mjs backup');
  const file = arg ? name : path.join(backupDir, name);
  console.log(`演练：把 ${path.relative(rootDir, file)} 恢复到临时库 ${DRILL_DB}，逐表比对行数`);
  query('', `DROP DATABASE IF EXISTS ${DRILL_DB}; CREATE DATABASE ${DRILL_DB} ${DB_CHARSET};`);
  try {
    const started = Date.now();
    const restore = mysqlSh(`mysql -uroot ${DRILL_DB}`, { stdin: 'pipe' });
    const done = finished(restore, '恢复');
    await Promise.all([pipeline(fs.createReadStream(file), zlib.createGunzip(), restore.stdin), done]);
    const source = tableCounts(DB);
    const restored = tableCounts(DRILL_DB);
    const diffs = compareCounts(source, restored);
    const triggers = (db) => query(db, `SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema = '${db}';`).trim().split('\n')[1];
    console.log(`恢复用时 ${((Date.now() - started) / 1000).toFixed(1)} 秒；表 ${Object.keys(restored).length} 张；触发器 源 ${triggers(DB)} / 恢复 ${triggers(DRILL_DB)}`);
    if (triggers(DB) !== triggers(DRILL_DB)) diffs.push('触发器数量不一致');
    if (diffs.length) {
      console.log('行数差异（备份之后源库有写入时属正常，需人工判断）：');
      for (const d of diffs) console.log(`- ${d}`);
      process.exitCode = 1;
    } else {
      console.log('演练通过：逐表行数一致');
    }
  } finally {
    query('', `DROP DATABASE IF EXISTS ${DRILL_DB};`);
  }
}

// 数据回滚（本机）：后端必须已停；先自动备份当前库，再整库重建为备份内容，最后用 restore-drill 口径逐表核对
async function restore() {
  const { file } = restoreArgs(process.argv.slice(3));
  if (!fs.existsSync(file)) throw new Error(`找不到备份文件 ${file}`);
  const config = buildConfig(loadEnv());
  const pids = readPids();
  const running = BACKENDS.filter((be) => (pids[be.name] && isAlive(pids[be.name].pid)) || portInUse(portOf(config[be.urlKey])));
  if (running.length) throw new Error(`${running.map((b) => b.name).join('、')} 仍在运行：先 node scripts/dev.mjs stop-backends，避免恢复途中还有写入`);
  console.log('恢复前先备份当前库（回滚的回滚）：');
  await backup();
  const started = Date.now();
  query('', `DROP DATABASE IF EXISTS ${DB}; CREATE DATABASE ${DB} ${DB_CHARSET};`);
  const load = mysqlSh(`mysql -uroot ${DB}`, { stdin: 'pipe' });
  const done = finished(load, '恢复');
  await Promise.all([pipeline(fs.createReadStream(file), zlib.createGunzip(), load.stdin), done]);
  console.log(`已用 ${path.relative(rootDir, file)} 重建库 ${DB}，用时 ${((Date.now() - started) / 1000).toFixed(1)} 秒；核对：`);
  await restoreDrill(file);
  console.log('下一步：node scripts/dev.mjs up --ui=none 起后端，看「运营告警」「运维概览」；备份时点之后的渠道单 / 供应商开卡按运维手册「数据回滚」补对账');
}

// 数据密钥轮换演练（只动本机 .env 的假密钥）：新增版本 → 重启后端 → 后台「运维概览」重加密 → 旧版本数据清零后下线
async function stopBackends() {
  const pids = readPids();
  const config = buildConfig(loadEnv());
  const stopping = {};
  for (const be of BACKENDS) {
    const meta = pids[be.name];
    if (meta && isAlive(meta.pid)) {
      killTree(meta.pid);
      stopping[be.name] = meta.pid;
      console.log(`已停止 ${be.name}（pid ${meta.pid}）`);
    }
    delete pids[be.name];
  }
  fs.writeFileSync(pidPath, JSON.stringify(pids, null, 2));
  // 端口先于进程释放：manager-api 优雅停机期间 worker 仍可能写库，进程退出才算停完
  for (const be of BACKENDS) {
    await waitFor(`${be.name} 端口释放`, async () => !portInUse(portOf(config[be.urlKey])), 60000);
    if (stopping[be.name]) await waitFor(`${be.name} 进程退出`, async () => !isAlive(stopping[be.name]), 120000);
  }
}

async function rotateDataKey() {
  const { name, value } = nextDataKey(loadEnv());
  fs.appendFileSync(secretsPath, `${name}=${value}\n`, { mode: 0o600 });
  console.log(`已在本机 .env 追加 ${name}（新写入将用它加密，旧版本仍可解密）`);
  await stopBackends();
  console.log('下一步：node scripts/dev.mjs up --ui=none 以新密钥启动后端，再到后台「运维 › 运维概览」分批重加密');
}

async function retireDataKey() {
  const version = Number(process.argv[3]);
  if (!Number.isInteger(version) || version < 1) throw new Error('用法：node scripts/dev.mjs retire-data-key <版本号>');
  const env = loadEnv();
  const api = managerClient(buildConfig(env).managerApiUrl);
  await api.login();
  const blockers = retireBlockers(env, version, await api.get('/manager/liroam/ops/data-keys'));
  if (blockers.length) throw new Error(`不能下线 V${version}：${blockers.join('；')}`);
  fs.writeFileSync(secretsPath, removeEnvLine(fs.readFileSync(secretsPath, 'utf8'), DATA_KEY_PREFIX + version), { mode: 0o600 });
  console.log(`已从本机 .env 删除 ${DATA_KEY_PREFIX}${version}；剩余版本 ${dataKeyVersions(loadEnv()).map((v) => `V${v}`).join('、')}`);
  await stopBackends();
  console.log('下一步：node scripts/dev.mjs up --ui=none 重启后端，「运维概览」应无无法解密行');
}

const command = process.argv[2] || 'status';
const handlers = { up, 'stop-backends': stopBackends, down, status, health, demo, backup, 'restore-drill': () => restoreDrill(), restore, 'rotate-data-key': rotateDataKey, 'retire-data-key': retireDataKey };
if (!handlers[command]) {
  console.error(`未知命令 ${command}；可用：up [--build] [--ui=manager,seller,buyer,buyer-web|none] [--backend-from=<checkout>] | stop-backends | down | status | health | demo | backup | restore-drill [文件] | restore <文件> --yes | rotate-data-key | retire-data-key <版本号>`);
  process.exit(2);
}
try {
  await handlers[command]();
} catch (error) {
  console.error(`\n失败：${error.message}`);
  process.exit(1);
}
