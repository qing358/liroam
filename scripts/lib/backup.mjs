// 备份 / 恢复演练的纯逻辑（文件名、行数比对）；docker 与 mysql 调用留在 dev.mjs。
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
