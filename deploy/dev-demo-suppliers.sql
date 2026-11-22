-- 本机演示：列出演示供应商（只读，供 `node scripts/dev.mjs demo` 导入资费表）。
SELECT id, code FROM lr_supplier WHERE code IN ('SUP_A', 'SUP_B') ORDER BY code;
