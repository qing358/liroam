import fs from 'node:fs';

// 集成测试库按槽位分开：同一槽位的重活串行、不同槽位的并发，所以任意时刻不会有两个 verify 共用同名库互删（含主检出）。
// 前缀须形如 liroam_it_xxx_（ItDatabase.prefix 校验）；调用方手设的前缀优先。
export function slotEnv(env, slot) {
  return env.LIROAM_IT_DB_PREFIX ? env : { ...env, LIROAM_IT_DB_PREFIX: `liroam_it_slot${slot}_` };
}

// worktree 里没有 .env：口令取主检出那份
export function heavyEnv(base, { mainSecrets, javaHome }) {
  const env = { ...base };
  if (!env.LIROAM_MYSQL_ROOT_PASSWORD && mainSecrets.LIROAM_MYSQL_ROOT_PASSWORD) {
    env.LIROAM_MYSQL_ROOT_PASSWORD = mainSecrets.LIROAM_MYSQL_ROOT_PASSWORD;
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

// 按命令估一个任务的内存预留（GB）：整仓 verify 进程树峰值实测约 4.4G（mvn + 多个测试 fork），受影响模块测试约 0.8G 起、按 1.5G 留（2026-11-28 实测，见 project-context §6）
export function reserveGb(command, env = process.env) {
  if (env.LIROAM_HEAVY_RESERVE_GB !== undefined && env.LIROAM_HEAVY_RESERVE_GB !== '') return Number(env.LIROAM_HEAVY_RESERVE_GB);
  return command.includes('verify') || command.includes('-Pupstream-tests') || command.includes('package') ? 4.5 : 1.5;
}

// 可用内存扣掉本任务预留后仍须保留余量，才放行
export function admits(availGb, minGb, reserve) {
  return availGb >= minGb + reserve;
}
