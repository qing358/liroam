// 敏感字段加密密钥环（liroam-common SensitiveCipher）：LIROAM_DATA_KEY_V1..V32，新写入用最大版本。
// 本机轮换演练只动本机 .env 的假密钥；生产在密钥管理里做同样的增 / 删，步骤见 docs/ops-runbook.md。
import crypto from 'node:crypto';

export const DATA_KEY_PREFIX = 'LIROAM_DATA_KEY_V';
const MAX_VERSION = 32;
const NAME = /^LIROAM_DATA_KEY_V([1-9][0-9]*)$/;

export function dataKeyVersions(env) {
  return Object.entries(env)
    .map(([k, v]) => [NAME.exec(k), v])
    .filter(([m, v]) => m && v)
    .map(([m]) => Number(m[1]))
    .sort((a, b) => a - b);
}

export function dataKeyEntries(env) {
  return Object.fromEntries(dataKeyVersions(env).map((v) => [DATA_KEY_PREFIX + v, env[DATA_KEY_PREFIX + v]]));
}

export function nextDataKey(env) {
  const versions = dataKeyVersions(env);
  if (versions.length === 0) throw new Error('没有数据密钥，先执行 node scripts/dev.mjs up 生成');
  const next = versions[versions.length - 1] + 1;
  if (next > MAX_VERSION) throw new Error(`已到最大版本 ${DATA_KEY_PREFIX}${MAX_VERSION}，不能再新增`);
  return { name: DATA_KEY_PREFIX + next, value: crypto.randomBytes(32).toString('base64') };
}

/** status 为后台 GET /manager/liroam/ops/data-keys 的结果；返回不能下线的原因，空数组即可下线。 */
export function retireBlockers(env, version, status) {
  const versions = dataKeyVersions(env);
  if (!versions.includes(version)) return [`.env 里没有 ${DATA_KEY_PREFIX}${version}`];
  if (version === versions[versions.length - 1]) return [`V${version} 是当前加密版本，先新增更高版本并重加密`];
  return (status.tables ?? []).flatMap((t) => (t.versions ?? [])
    .filter((v) => v.keyVersion === version && v.rows > 0)
    .map((v) => `${t.table} 仍有 ${v.rows} 行用 V${version}`));
}

export function removeEnvLine(text, name) {
  return text.split('\n').filter((line) => !line.startsWith(`${name}=`)).join('\n');
}
