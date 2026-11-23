// 备份 / 恢复演练的纯逻辑（文件名、行数比对）；docker 与 mysql 调用留在 dev.mjs。
import path from 'node:path';

const PREFIX = 'lilishop-';
const SUFFIX = '.sql.gz';

export function backupFileName(date) {
  return `${PREFIX}${date.toISOString().replace(/[-:]/g, '').replace(/\.\d{3}Z$/, 'Z')}${SUFFIX}`;
}

export function latestBackup(names) {
  const ours = names.filter((n) => n.startsWith(PREFIX) && n.endsWith(SUFFIX)).sort();
  return ours.length ? ours[ours.length - 1] : null;
}

export function parseCounts(batchOutput) {
  const lines = batchOutput.trim().split('\n').slice(1).filter(Boolean);
  return Object.fromEntries(lines.map((line) => {
    const [table, n] = line.split('\t');
    return [table, Number(n)];
  }));
}

export function compareCounts(source, restored) {
  return Object.keys(source).sort().flatMap((table) => {
    if (!(table in restored)) return [`${table}：恢复库缺这张表`];
    if (source[table] !== restored[table]) return [`${table}：源 ${source[table]} 行，恢复后 ${restored[table]} 行`];
    return [];
  });
}

// restore 会覆盖源库：文件必须显式给出（不默认取最新），并带 --yes
export function restoreArgs(args) {
  const file = args.find((a) => !a.startsWith('--'));
  if (!file) throw new Error('用法：node scripts/dev.mjs restore <备份文件> --yes');
  if (!args.includes('--yes')) throw new Error(`restore 会用 ${file} 覆盖库 lilishop（先自动备份当前库）；确认后加 --yes`);
  return { file: path.resolve(file) };
}
