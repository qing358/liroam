import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

import { backendBuildArgs, backendEnv, buildConfig, ensureSecretsFile, generateSecrets, parseEnv, SECRET_KEYS, validatePorts } from './config.mjs';

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
