import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { dataKeyEntries, dataKeyVersions } from './data-keys.mjs';

export function parseEnv(text) {
  const result = {};
  for (const rawLine of text.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith('#')) continue;
    const eq = line.indexOf('=');
    if (eq <= 0) throw new Error(`无法解析的配置行：${rawLine}`);
    const key = line.slice(0, eq).trim();
    let value = line.slice(eq + 1).trim();
    if (/^(['"]).*\1$/.test(value)) value = value.slice(1, -1);
    result[key] = value;
  }
  return result;
}

export function validatePorts(env) {
  const seen = new Map();
  for (const [key, value] of Object.entries(env)) {
    if (!/^LIROAM_.*_PORT$/.test(key)) continue;
    const port = Number(value);
    if (!Number.isInteger(port) || port < 1024 || port > 65535) {
      throw new Error(`${key}=${value} 不是合法端口（1024-65535）`);
    }
    if (seen.has(port)) throw new Error(`端口 ${port} 同时登记给 ${seen.get(port)} 和 ${key}`);
    seen.set(port, key);
  }
  if (seen.size === 0) throw new Error('ports.env 未登记任何 LIROAM_*_PORT');
}

// LIROAM_DATA_KEY_V1 / LIROAM_DATA_HASH_KEY（V1 只在还没有任何数据密钥版本时生成；轮换见 data-keys.mjs）：ICCID、交码内容等敏感字段的 AES-256-GCM 密钥与查重用 HMAC 密钥（liroam-common SensitiveCipher）
export const SECRET_KEYS = ['LIROAM_MYSQL_ROOT_PASSWORD', 'LIROAM_REDIS_PASSWORD', 'LILI_JWT_SECRET', 'LIROAM_DATA_KEY_V1', 'LIROAM_DATA_HASH_KEY'];

export function generateSecrets() {
  return {
    LIROAM_MYSQL_ROOT_PASSWORD: crypto.randomBytes(18).toString('base64url'),
    LIROAM_REDIS_PASSWORD: crypto.randomBytes(18).toString('base64url'),
    LILI_JWT_SECRET: crypto.randomBytes(32).toString('base64'),
    LIROAM_DATA_KEY_V1: crypto.randomBytes(32).toString('base64'),
    LIROAM_DATA_HASH_KEY: crypto.randomBytes(32).toString('base64'),
  };
}

// 仓根 pom 聚合上游与自研 modules；API 进程依赖 liroam-shell-bridge，必须从仓根构建
export function backendBuildArgs(modules) {
  return ['-B', '-q', '-pl', modules.map((m) => `shop/lilishop/${m}`).join(','), '-am', 'package', '-DskipTests'];
}

export function selectFrontends(argv, frontends) {
  const flag = argv.find((a) => a.startsWith('--ui='));
  if (!flag) return frontends;
  const value = flag.slice('--ui='.length);
  if (value === 'none') return [];
  const wanted = value.split(',');
  for (const app of wanted) {
    if (!frontends.some((f) => f.app === app)) throw new Error(`未知前端 ${app}；可用：${frontends.map((f) => f.app).join(',')} 或 none`);
  }
  return frontends.filter((f) => wanted.includes(f.app));
}

export function ensureSecretsFile(secretsPath) {
  const existing = fs.existsSync(secretsPath) ? parseEnv(fs.readFileSync(secretsPath, 'utf8')) : {};
  const hasDataKey = dataKeyVersions(existing).length > 0;
  const missing = SECRET_KEYS.filter((key) => !existing[key] && !(key === 'LIROAM_DATA_KEY_V1' && hasDataKey));
  if (missing.length === 0) return { created: [], values: existing };
  const generated = generateSecrets();
  const lines = missing.map((key) => `${key}=${generated[key]}`);
  const prefix = fs.existsSync(secretsPath) ? '\n' : '# 本机开发口令，由 scripts/dev.mjs 生成；不入库\n';
  fs.appendFileSync(secretsPath, prefix + lines.join('\n') + '\n', { mode: 0o600 });
  return { created: missing, values: { ...existing, ...Object.fromEntries(missing.map((k) => [k, generated[k]])) } };
}

// 写进前端 config 的地址由浏览器访问；Windows 侧浏览器访问 WSL2 时，只有 localhost 会转发到 Java 的 IPv6 通配监听
export function buildConfig(env, host = 'localhost') {
  validatePorts(env);
  const url = (key) => `http://${host}:${env[key]}`;
  return {
    host,
    mysqlPort: Number(env.LIROAM_MYSQL_PORT),
    redisPort: Number(env.LIROAM_REDIS_PORT),
    managerApiUrl: url('LIROAM_MANAGER_API_PORT'),
    buyerApiUrl: url('LIROAM_BUYER_API_PORT'),
    buyerPcUrl: url('LIROAM_BUYER_UI_PORT'),
    sellerUrl: url('LIROAM_SELLER_UI_PORT'),
    managerUrl: url('LIROAM_MANAGER_UI_PORT'),
  };
}

// worker（补偿扫描等定时任务）只在 manager-api 进程开（domain-design §5 H12）
export function backendEnv(env, config, { worker = false } = {}) {
  return {
    ...dataKeyEntries(env),
    LIROAM_DATA_HASH_KEY: env.LIROAM_DATA_HASH_KEY,
    LIROAM_WORKER_ENABLED: String(worker),
    SPRING_DATASOURCE_URL: `jdbc:mysql://127.0.0.1:${config.mysqlPort}/lilishop?useUnicode=true&characterEncoding=utf-8&useSSL=false&rewriteBatchedStatements=true&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai`,
    SPRING_DATASOURCE_USERNAME: 'root',
    SPRING_DATASOURCE_PASSWORD: env.LIROAM_MYSQL_ROOT_PASSWORD,
    SPRING_DATA_REDIS_HOST: '127.0.0.1',
    SPRING_DATA_REDIS_PORT: String(config.redisPort),
    SPRING_DATA_REDIS_PASSWORD: env.LIROAM_REDIS_PASSWORD,
    LILI_JWT_SECRET: env.LILI_JWT_SECRET,
  };
}

export function readSdkmanJava(rootDir) {
  const rcPath = path.join(rootDir, '.sdkmanrc');
  if (!fs.existsSync(rcPath)) return null;
  return parseEnv(fs.readFileSync(rcPath, 'utf8')).java || null;
}
