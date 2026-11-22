import assert from 'node:assert/strict';
import test from 'node:test';

import { dataKeyEntries, dataKeyVersions, nextDataKey, removeEnvLine, retireBlockers } from './data-keys.mjs';

test('dataKeyVersions 只认 LIROAM_DATA_KEY_V<n>，按版本号数值排序', () => {
  const env = { LIROAM_DATA_KEY_V10: 'c', LIROAM_DATA_KEY_V2: 'b', LIROAM_DATA_KEY_V1: 'a', LIROAM_DATA_HASH_KEY: 'h', LIROAM_DATA_KEY_VX: 'x', LIROAM_DATA_KEY_V3: '' };
  assert.deepEqual(dataKeyVersions(env), [1, 2, 10]);
  assert.deepEqual(dataKeyEntries(env), { LIROAM_DATA_KEY_V1: 'a', LIROAM_DATA_KEY_V2: 'b', LIROAM_DATA_KEY_V10: 'c' });
});

test('nextDataKey 取最大版本 +1，值为 32 字节 base64；没有任何版本时报错', () => {
  const { name, value } = nextDataKey({ LIROAM_DATA_KEY_V1: 'a', LIROAM_DATA_KEY_V3: 'c' });
  assert.equal(name, 'LIROAM_DATA_KEY_V4');
  assert.equal(Buffer.from(value, 'base64').length, 32);
  assert.throws(() => nextDataKey({}), /没有数据密钥/);
});

test('nextDataKey 到上限 V32 拒绝', () => {
  assert.throws(() => nextDataKey({ LIROAM_DATA_KEY_V32: 'a' }), /V32/);
});

test('retireBlockers：当前版本、不存在的版本、仍有该版本数据的表都拦下', () => {
  const env = { LIROAM_DATA_KEY_V1: 'a', LIROAM_DATA_KEY_V2: 'b' };
  const clean = { tables: [{ table: 'lr_a', versions: [{ keyVersion: 2, rows: 5 }] }] };
  const dirty = { tables: [{ table: 'lr_a', versions: [{ keyVersion: 1, rows: 3 }, { keyVersion: 2, rows: 5 }] }, { table: 'lr_b', versions: [{ keyVersion: 1, rows: 0 }] }] };
  assert.deepEqual(retireBlockers(env, 1, clean), []);
  assert.match(retireBlockers(env, 2, clean).join(), /当前加密版本/);
  assert.match(retireBlockers(env, 5, clean).join(), /没有 LIROAM_DATA_KEY_V5/);
  assert.deepEqual(retireBlockers(env, 1, dirty), ['lr_a 仍有 3 行用 V1']);
});

test('removeEnvLine 只删该变量那一行，保留注释与其它行', () => {
  const text = '# c\nLIROAM_DATA_KEY_V1=a\nLIROAM_DATA_KEY_V10=b\nX=1\n';
  assert.equal(removeEnvLine(text, 'LIROAM_DATA_KEY_V1'), '# c\nLIROAM_DATA_KEY_V10=b\nX=1\n');
});
