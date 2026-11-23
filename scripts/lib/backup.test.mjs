import assert from 'node:assert/strict';
import path from 'node:path';
import test from 'node:test';

import { backupFileName, compareCounts, latestBackup, parseCounts, restoreArgs } from './backup.mjs';

test('backupFileName 用 UTC 时间戳，字典序即时间序', () => {
  assert.equal(backupFileName(new Date('2026-11-24T01:02:03Z')), 'lilishop-20261124T010203Z.sql.gz');
});

test('latestBackup 只认本工具生成的文件，取最新；没有则 null', () => {
  assert.equal(latestBackup(['x.sql.gz', 'lilishop-20261123T000000Z.sql.gz', 'lilishop-20261124T000000Z.sql.gz']),
    'lilishop-20261124T000000Z.sql.gz');
  assert.equal(latestBackup(['readme.txt']), null);
});

test('parseCounts 读 mysql --batch 的 表名\\t行数', () => {
  assert.deepEqual(parseCounts('t\tn\nlr_a\t3\nli_order\t0\n'), { lr_a: 3, li_order: 0 });
});

test('compareCounts 列出缺表与行数不一致；一致为空', () => {
  assert.deepEqual(compareCounts({ lr_a: 3, lr_b: 1 }, { lr_a: 3, lr_b: 1 }), []);
  assert.deepEqual(compareCounts({ lr_a: 3, lr_b: 1, lr_c: 0 }, { lr_a: 2, lr_b: 1 }), ['lr_a：源 3 行，恢复后 2 行', 'lr_c：恢复库缺这张表']);
});

test('restoreArgs 覆盖源库必须显式给文件并带 --yes，不默认取最新备份', () => {
  assert.deepEqual(restoreArgs(['temp/backups/a.sql.gz', '--yes']), { file: path.resolve('temp/backups/a.sql.gz') });
  assert.deepEqual(restoreArgs(['--yes', '/b/a.sql.gz']), { file: '/b/a.sql.gz' });
  assert.throws(() => restoreArgs(['--yes']), /用法/);
  assert.throws(() => restoreArgs(['/b/a.sql.gz']), /--yes/);
});
