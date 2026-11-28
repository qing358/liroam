import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

import {
  backendBuildArgs, backendCheckout, backendEnv, buildConfig, devAssetsEnv, devSeedVars, ensureSecretsFile, frontendInstallCommand, generateSecrets, parseEnv, SECRET_KEYS, selectFrontends, sqlSetVars, validatePorts,
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

test('仓内 deploy/ports.env 登记新买家站端口与 worktree 临时前端端口段', () => {
  const env = parseEnv(fs.readFileSync(path.join(repoRoot, 'deploy/ports.env'), 'utf8'));
  assert.equal(env.LIROAM_BUYER_WEB_PORT, '39010');
  assert.match(env.LIROAM_WORKTREE_UI_PORTS, /^\d+-\d+$/);
});

test('validatePorts 校验端口段：格式、上下界、与单端口或其它段重叠', () => {
  validatePorts({ LIROAM_A_PORT: '39000', LIROAM_X_PORTS: '39100-39199' });
  assert.throws(() => validatePorts({ LIROAM_A_PORT: '39000', LIROAM_X_PORTS: '39100' }), /端口段/);
  assert.throws(() => validatePorts({ LIROAM_A_PORT: '39000', LIROAM_X_PORTS: '39199-39100' }), /端口段/);
  assert.throws(() => validatePorts({ LIROAM_A_PORT: '39000', LIROAM_X_PORTS: '1000-1010' }), /端口段/);
  assert.throws(() => validatePorts({ LIROAM_A_PORT: '39150', LIROAM_X_PORTS: '39100-39199' }), /落在/);
  assert.throws(() => validatePorts({ LIROAM_A_PORT: '39000', LIROAM_X_PORTS: '39100-39199', LIROAM_Y_PORTS: '39190-39210' }), /重叠/);
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

test('backendEnv 透传全部数据密钥版本（轮换后 V1 可下线）', () => {
  const env = { LIROAM_DATA_KEY_V2: 'k2', LIROAM_DATA_KEY_V3: 'k3', LIROAM_DATA_HASH_KEY: 'h' };
  const out = backendEnv(env, { mysqlPort: 39306, redisPort: 39379 });
  assert.equal(out.LIROAM_DATA_KEY_V2, 'k2');
  assert.equal(out.LIROAM_DATA_KEY_V3, 'k3');
  assert.ok(!('LIROAM_DATA_KEY_V1' in out));
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

test('ensureSecretsFile 在 V1 已下线、只剩更高版本数据密钥时不再补 V1', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'liroam-secrets-'));
  const file = path.join(dir, '.env');
  ensureSecretsFile(file);
  const text = fs.readFileSync(file, 'utf8').replace(/^LIROAM_DATA_KEY_V1=.*$/m, 'LIROAM_DATA_KEY_V2=' + Buffer.alloc(32, 2).toString('base64'));
  fs.writeFileSync(file, text);
  const again = ensureSecretsFile(file);
  assert.deepEqual(again.created, []);
  assert.ok(!('LIROAM_DATA_KEY_V1' in again.values));
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

const UIS = [{ name: 'manager-ui', app: 'manager' }, { name: 'seller-ui', app: 'seller' }, { name: 'buyer-ui', app: 'buyer' }];

test('selectFrontends 默认起全部前端；--ui 按需只起列出的；--ui=none 一个不起', () => {
  assert.deepEqual(selectFrontends(['up'], UIS).map((f) => f.app), ['manager', 'seller', 'buyer']);
  assert.deepEqual(selectFrontends(['up', '--ui=buyer,manager'], UIS).map((f) => f.app), ['manager', 'buyer']);
  assert.deepEqual(selectFrontends(['up', '--ui=none'], UIS), []);
});

test('frontendInstallCommand 按锁文件选包管理器并冻结安装：pnpm 优先，其次 yarn，都没有才用 npm', () => {
  const pnpm = ['pnpm', 'install', '--frozen-lockfile'];
  const yarn = ['corepack', 'yarn@1.22.22', 'install', '--frozen-lockfile'];
  assert.deepEqual(frontendInstallCommand(['package.json', 'pnpm-lock.yaml']), pnpm);
  assert.deepEqual(frontendInstallCommand(['package.json', 'yarn.lock']), yarn);
  assert.deepEqual(frontendInstallCommand(['package.json', 'pnpm-lock.yaml', 'yarn.lock']), pnpm);
  assert.deepEqual(frontendInstallCommand(['package.json']), ['npm', 'install', '--no-audit', '--no-fund']);
});

test('仓内各前端按锁文件选到预期的包管理器', () => {
  const uiDir = path.join(repoRoot, 'shop/lilishop-ui');
  assert.equal(frontendInstallCommand(fs.readdirSync(path.join(uiDir, 'manager')))[0], 'pnpm');
  assert.equal(frontendInstallCommand(fs.readdirSync(path.join(uiDir, 'seller')))[1], 'yarn@1.22.22');
});

test('backendCheckout 默认用本仓；--backend-from 指向另一份 checkout（回滚演练起上一版 jar）', () => {
  assert.equal(backendCheckout(['up'], '/repo'), '/repo');
  assert.equal(backendCheckout(['up', '--ui=none', '--backend-from=/wt/old'], '/repo'), '/wt/old');
  assert.equal(backendCheckout(['up', '--backend-from=../old'], '/repo/x'), path.resolve('../old'));
  assert.throws(() => backendCheckout(['up', '--backend-from='], '/repo'), /--backend-from/);
  assert.throws(() => backendCheckout(['up', '--build', '--backend-from=/wt/old'], '/repo'), /--build/);
});

test('selectFrontends 遇到未知前端名或空列表直接报错', () => {
  assert.throws(() => selectFrontends(['up', '--ui=admin'], UIS), /未知前端 admin/);
  assert.throws(() => selectFrontends(['up', '--ui='], UIS), /未知前端/);
});

test('devSeedVars 给 dev-seed 本机地址与滑块素材目录，地址取自端口登记', () => {
  const env = parseEnv(fs.readFileSync(path.join(repoRoot, 'deploy/ports.env'), 'utf8'));
  const vars = devSeedVars(buildConfig(env));
  assert.equal(vars.liroam_buyer_pc, `http://localhost:${env.LIROAM_BUYER_UI_PORT}`);
  assert.equal(vars.liroam_buyer_api, `http://localhost:${env.LIROAM_BUYER_API_PORT}`);
  assert.equal(vars.liroam_dev_assets, `http://localhost:${env.LIROAM_MANAGER_API_PORT}/static`);
});

test('devAssetsEnv 让 manager-api 把仓内 deploy/dev-assets 挂在免鉴权的 /static 下', () => {
  assert.deepEqual(devAssetsEnv('/work/liroam'), {
    SPRING_WEB_RESOURCES_STATIC_LOCATIONS: 'file:/work/liroam/deploy/dev-assets/',
    SPRING_MVC_STATIC_PATH_PATTERN: '/static/**',
  });
  const yml = fs.readFileSync(path.join(repoRoot, 'shop/lilishop/manager-api/src/main/resources/application.yml'), 'utf8');
  assert.match(yml, /^\s+- \/static\/\*\*/m, 'manager-api 的 ignored.urls 须放行 /static/**');
});

test('种子与迁移（含 dev-seed 会话变量）不得出现 file:// 与本机绝对路径', () => {
  const local = /file:\/\/|(?:^|['"\s(,=])(?:\/root\/|\/home\/[a-z][\w-]*\/|\/Users\/|\/mnt\/[a-z]\/|\/tmp\/|[A-Za-z]:[\\/]|\\\\wsl)/m;
  const sqlFiles = [
    ...fs.readdirSync(path.join(repoRoot, 'deploy')).filter((f) => f.endsWith('.sql')).map((f) => path.join(repoRoot, 'deploy', f)),
    ...fs.globSync('modules/**/src/main/resources/**/*.sql', { cwd: repoRoot }).map((f) => path.join(repoRoot, f)),
  ];
  assert.ok(sqlFiles.length > 5, '应扫到 deploy 种子与 Flyway 迁移');
  for (const file of sqlFiles) {
    const hit = fs.readFileSync(file, 'utf8').split('\n').findIndex((line) => local.test(line));
    assert.equal(hit, -1, `${path.relative(repoRoot, file)}:${hit + 1}`);
  }
  const vars = devSeedVars(buildConfig(parseEnv(fs.readFileSync(path.join(repoRoot, 'deploy/ports.env'), 'utf8'))));
  for (const [k, v] of Object.entries(vars)) {
    assert.doesNotMatch(` ${v}`, local, k);
    assert.match(v, /^https?:\/\//, k);
  }
});

test('dev-seed 引用的变量都由 devSeedVars 提供，滑块素材文件都在仓里', () => {
  const sql = fs.readFileSync(path.join(repoRoot, 'deploy/dev-seed.sql'), 'utf8');
  const vars = devSeedVars(buildConfig(parseEnv(fs.readFileSync(path.join(repoRoot, 'deploy/ports.env'), 'utf8'))));
  const used = new Set([...sql.matchAll(/@(liroam_[a-z_]+)/g)].map((m) => m[1]));
  assert.deepEqual([...used].sort(), Object.keys(vars).sort());
  const files = [...sql.matchAll(/@liroam_dev_assets, '([^']+)'/g)].map((m) => m[1]);
  assert.ok(files.length >= 3, '至少 1 张背景、2 张滑块');
  for (const f of files) assert.ok(fs.existsSync(path.join(repoRoot, 'deploy/dev-assets', f)), f);
});

test('sqlSetVars 生成会话变量并转义引号与反斜杠', () => {
  assert.equal(sqlSetVars({ a: "x'y", b: 'c\\d' }), "SET @a := 'x''y';\nSET @b := 'c\\\\d';\n");
});
