import fs from 'node:fs';
import path from 'node:path';

// 集成测试库前缀须形如 liroam_it_xxx_（ItDatabase.prefix 校验）
export function itDbPrefix(dirName) {
  return `liroam_it_${dirName.toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '')}_`;
}

// worktree 里没有 .env：口令取主检出那份；库前缀按 worktree 目录名分开，避免同名测试类互删库
export function heavyEnv(base, { checkout, mainCheckout, mainSecrets, javaHome }) {
  const env = { ...base };
  if (!env.LIROAM_MYSQL_ROOT_PASSWORD && mainSecrets.LIROAM_MYSQL_ROOT_PASSWORD) {
    env.LIROAM_MYSQL_ROOT_PASSWORD = mainSecrets.LIROAM_MYSQL_ROOT_PASSWORD;
  }
  if (!env.LIROAM_IT_DB_PREFIX && path.resolve(checkout) !== path.resolve(mainCheckout)) {
    env.LIROAM_IT_DB_PREFIX = itDbPrefix(path.basename(checkout));
  }
  if (javaHome) {
    env.JAVA_HOME = javaHome;
    env.PATH = `${javaHome}/bin:${base.PATH ?? ''}`;
  }
  return env;
}

export function availableMemGb(meminfo = fs.readFileSync('/proc/meminfo', 'utf8')) {
  const kb = Number(/^MemAvailable:\s+(\d+)/m.exec(meminfo)?.[1]);
  return Number.isFinite(kb) ? kb / 1024 / 1024 : Infinity;
}
