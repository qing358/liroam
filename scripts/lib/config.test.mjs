import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

import {
  backendBuildArgs, backendEnv, backendJvmArgs, buildConfig, ensureSecretsFile, frontendEnv, generateSecrets, HOST_LIMITS,
  limitedCommand, parseEnv, SECRET_KEYS, selectFrontends, validatePorts,
} from './config.mjs';

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');

test('parseEnv 跳过注释与空行，去掉成对引号', () => {
  assert.deepEqual(parseEnv('# c\n\nA=1\nB="x y"\nC=\'z\'\n'), { A: '1', B: 'x y', C: 'z' });
});

test('parseEnv 遇到无等号的行直接报错，不静默吞掉', () => {
  assert.throws(() => parseEnv('A=1\noops\n'), /无法解析/);
});

test('validatePorts 拒绝重复端口', () => {
  assert.throws(() => validatePorts({ LIROAM_A_PORT: '39001', LIROAM_B_PORT: '39001' }), /同时登记/);
});

test('validatePorts 拒绝非法端口与空登记', () => {
  assert.throws(() => validatePorts({ LIROAM_A_PORT: 'abc' }), /不是合法端口/);
  assert.throws(() => validatePorts({ LIROAM_A_PORT: '80' }), /不是合法端口/);
  assert.throws(() => validatePorts({ OTHER: '1' }), /未登记/);
});

test('仓内 deploy/ports.env 合法，且不占用本机其它项目的 3306 / 6379', () => {
  const env = parseEnv(fs.readFileSync(path.join(repoRoot, 'deploy/ports.env'), 'utf8'));
  validatePorts(env);
  const ports = Object.entries(env).filter(([k]) => k.endsWith('_PORT')).map(([, v]) => Number(v));
  assert.ok(!ports.includes(3306) && !ports.includes(6379));
  assert.equal(env.COMPOSE_PROJECT_NAME, 'liroam');
});

test('buildConfig 由端口登记推出各端地址（浏览器侧用 localhost，WSL2 才会转发到 Windows）', () => {
  const config = buildConfig({
    LIROAM_MYSQL_PORT: '39306', LIROAM_REDIS_PORT: '39379',
    LIROAM_MANAGER_API_PORT: '39887', LIROAM_BUYER_API_PORT: '39888',
    LIROAM_BUYER_UI_PORT: '39000', LIROAM_SELLER_UI_PORT: '39002', LIROAM_MANAGER_UI_PORT: '39003',
  });
  assert.equal(config.managerApiUrl, 'http://localhost:39887');
  assert.equal(config.managerUrl, 'http://localhost:39003');
  assert.equal(config.mysqlPort, 39306);
});

test('backendEnv 只用登记端口与 .env 口令覆盖上游示例配置', () => {
  const env = { LIROAM_MYSQL_ROOT_PASSWORD: 'p1', LIROAM_REDIS_PASSWORD: 'p2', LILI_JWT_SECRET: 's' };
  const out = backendEnv(env, { mysqlPort: 39306, redisPort: 39379 });
  assert.match(out.SPRING_DATASOURCE_URL, /127\.0\.0\.1:39306\/lilishop/);
  assert.equal(out.SPRING_DATA_REDIS_PORT, '39379');
  assert.equal(out.SPRING_DATASOURCE_PASSWORD, 'p1');
  assert.equal(out.SPRING_DATA_REDIS_PASSWORD, 'p2');
});

test('backendEnv 透传自研数据密钥；worker 只在显式开启的进程打开', () => {
  const env = { LIROAM_MYSQL_ROOT_PASSWORD: 'p1', LIROAM_REDIS_PASSWORD: 'p2', LILI_JWT_SECRET: 's', LIROAM_DATA_KEY_V1: 'k1', LIROAM_DATA_HASH_KEY: 'h' };
  const config = { mysqlPort: 39306, redisPort: 39379 };
  const plain = backendEnv(env, config);
  assert.equal(plain.LIROAM_DATA_KEY_V1, 'k1');
  assert.equal(plain.LIROAM_DATA_HASH_KEY, 'h');
  assert.equal(plain.LIROAM_WORKER_ENABLED, 'false');
  assert.equal(backendEnv(env, config, { worker: true }).LIROAM_WORKER_ENABLED, 'true');
});

test('数据密钥为 32 字节 base64（AES-256 / HMAC-SHA256）', () => {
  const secrets = generateSecrets();
  for (const key of ['LIROAM_DATA_KEY_V1', 'LIROAM_DATA_HASH_KEY']) {
    assert.equal(Buffer.from(secrets[key], 'base64').length, 32, key);
  }
  assert.notEqual(secrets.LIROAM_DATA_KEY_V1, secrets.LIROAM_DATA_HASH_KEY);
});

test('后端从仓根 reactor 构建，自研模块随 API 进程一起编译', () => {
  assert.deepEqual(backendBuildArgs(['manager-api', 'buyer-api']),
    ['-B', '-q', '-pl', 'shop/lilishop/manager-api,shop/lilishop/buyer-api', '-am', 'package', '-DskipTests']);
});

test('ensureSecretsFile 首次生成全部口令，再次运行不覆盖已有值、只补缺', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'liroam-secrets-'));
  const file = path.join(dir, '.env');
  const first = ensureSecretsFile(file);
  assert.deepEqual(first.created, SECRET_KEYS);
  assert.equal(fs.statSync(file).mode & 0o777, 0o600);

  const second = ensureSecretsFile(file);
  assert.deepEqual(second.created, []);
  assert.deepEqual(second.values, first.values);

  fs.writeFileSync(file, `LIROAM_MYSQL_ROOT_PASSWORD=keep\n`);
  const third = ensureSecretsFile(file);
  assert.deepEqual(third.created, ['LIROAM_REDIS_PASSWORD', 'LILI_JWT_SECRET', 'LIROAM_DATA_KEY_V1', 'LIROAM_DATA_HASH_KEY']);
  assert.equal(third.values.LIROAM_MYSQL_ROOT_PASSWORD, 'keep');
});

test('宿主进程上限只登记一处：构建、后端、前端都有 CPU 核数与内存上限', () => {
  for (const kind of ['build', 'backend', 'frontend']) {
    assert.ok(Number.isInteger(HOST_LIMITS[kind].cpus) && HOST_LIMITS[kind].cpus >= 1, kind);
    assert.match(HOST_LIMITS[kind].memory, /^\d+[MG]$/, kind);
  }
});

test('后端 JVM 堆与可见核数由上限推出，不随机器核数膨胀', () => {
  const args = backendJvmArgs();
  assert.ok(args.includes(`-XX:ActiveProcessorCount=${HOST_LIMITS.backend.cpus}`));
  assert.ok(args.includes('-Xmx768m'));
});

test('前端 dev server 的 Node 堆上限小于其内存上限，先触发 GC 而不是被 cgroup 杀掉', () => {
  const env = frontendEnv();
  assert.equal(env.BROWSER, 'none');
  const heapMb = Number(env.NODE_OPTIONS.match(/--max-old-space-size=(\d+)/)[1]);
  const limitMb = parseInt(HOST_LIMITS.frontend.memory, 10) * (HOST_LIMITS.frontend.memory.endsWith('G') ? 1024 : 1);
  assert.ok(heapMb < limitMb, `${heapMb} < ${limitMb}`);
});

test('limitedCommand 有 systemd 时进 liroam.slice 并加 CPU/内存硬上限，再降调度优先级', () => {
  const [cmd, args] = limitedCommand('backend', '/jdk/bin/java', ['-jar', 'a.jar'], { systemd: true });
  assert.equal(cmd, 'systemd-run');
  assert.deepEqual(args, [
    '--scope', '--quiet', '--slice=liroam.slice',
    '-p', `CPUQuota=${HOST_LIMITS.backend.cpus * 100}%`, '-p', `MemoryMax=${HOST_LIMITS.backend.memory}`,
    'nice', '-n', '10', 'ionice', '-c2', '-n7', '/jdk/bin/java', '-jar', 'a.jar',
  ]);
});

test('limitedCommand 没有 systemd 时退回只降优先级（nice / ionice），命令原样执行', () => {
  const [cmd, args] = limitedCommand('frontend', 'vue-cli-service', ['serve'], { systemd: false });
  assert.equal(cmd, 'nice');
  assert.deepEqual(args, ['-n', '10', 'ionice', '-c2', '-n7', 'vue-cli-service', 'serve']);
});

test('limitedCommand 拒绝未登记的进程类别', () => {
  assert.throws(() => limitedCommand('other', 'x', [], { systemd: true }), /未登记/);
});

const UIS = [{ name: 'manager-ui', app: 'manager' }, { name: 'seller-ui', app: 'seller' }, { name: 'buyer-ui', app: 'buyer' }];

test('selectFrontends 默认起全部前端；--ui 按需只起列出的；--ui=none 一个不起', () => {
  assert.deepEqual(selectFrontends(['up'], UIS).map((f) => f.app), ['manager', 'seller', 'buyer']);
  assert.deepEqual(selectFrontends(['up', '--ui=buyer,manager'], UIS).map((f) => f.app), ['manager', 'buyer']);
  assert.deepEqual(selectFrontends(['up', '--ui=none'], UIS), []);
});

test('selectFrontends 遇到未知前端名或空列表直接报错', () => {
  assert.throws(() => selectFrontends(['up', '--ui=admin'], UIS), /未知前端 admin/);
  assert.throws(() => selectFrontends(['up', '--ui='], UIS), /未知前端/);
});

test('compose 里每个中间件都声明 cpus 与 mem_limit', () => {
  const text = fs.readFileSync(path.join(repoRoot, 'deploy/compose.yaml'), 'utf8');
  const body = text.slice(text.indexOf('\nservices:\n'), text.indexOf('\nvolumes:\n'));
  const services = body.split(/^(?=  [\w-]+:$)/m).slice(1);
  assert.deepEqual(services.map((b) => b.trim().split(':')[0]), ['mysql', 'redis']);
  for (const block of services) {
    assert.match(block, /^    cpus: "?[\d.]+"?$/m, block.trim().split(':')[0]);
    assert.match(block, /^    mem_limit: \d+[mg]$/m, block.trim().split(':')[0]);
  }
});

test('Maven 本体与 surefire 测试进程都限定可见核数与堆，不按整机核数开线程', () => {
  const jvmConfig = fs.readFileSync(path.join(repoRoot, '.mvn/jvm.config'), 'utf8');
  assert.match(jvmConfig, /-XX:ActiveProcessorCount=\d+/);
  assert.match(jvmConfig, /-Xmx\d+[mg]/);
  const modulesPom = fs.readFileSync(path.join(repoRoot, 'modules/pom.xml'), 'utf8');
  assert.match(modulesPom, /<argLine>[^<]*-XX:ActiveProcessorCount=\d+[^<]*-Xmx\d+[mg][^<]*<\/argLine>/);
  assert.match(modulesPom, /<forkCount>1<\/forkCount>/);
});
