/**
 * 第三方分包命名回归：npm 平铺与 pnpm（node_modules/.pnpm/…/node_modules/包）两种布局都按包名分 npm.<包> 块。
 */
const { test } = require('node:test');
const assert = require('node:assert/strict');

const { vendor } = require('../vue.config.js').configureWebpack.optimization.splitChunks.cacheGroups;

test('分包名取包名，不受 pnpm 布局影响', () => {
  const cases = [
    ['/app/node_modules/axios/lib/core', 'npm.axios'],
    ['/app/node_modules/.pnpm/axios@1.20.0/node_modules/axios/lib/core', 'npm.axios'],
    ['/app/node_modules/@vue/runtime-core/dist', 'npm.vue'],
    ['/app/node_modules/.pnpm/@vue+runtime-core@3.5.43/node_modules/@vue/runtime-core/dist', 'npm.vue'],
    ['/app/node_modules/.pnpm/element-plus@2.14.1_vue@3.5.43/node_modules/element-plus/es', 'npm.element-plus'],
  ];
  for (const [context, name] of cases) assert.equal(vendor.name({ context }), name, context);
});

test('vue-qr 两种布局都不进 vendor 分包', () => {
  assert.equal(vendor.test({ context: '/app/node_modules/vue-qr/src' }), false);
  assert.equal(vendor.test({ context: '/app/node_modules/.pnpm/vue-qr@5.0.3/node_modules/vue-qr/src' }), false);
  assert.equal(vendor.test({ context: '/app/node_modules/.pnpm/qs@6.17.1/node_modules/qs/lib' }), true);
});
