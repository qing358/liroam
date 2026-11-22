import assert from 'node:assert/strict';
import test from 'node:test';

import { backupFileName, compareCounts, latestBackup, parseCounts } from './backup.mjs';

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
