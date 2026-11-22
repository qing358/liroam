/**
 * 旅行卡页面用到的 Element Plus 组件必须已注册：买家端按需注册（src/plugins/element.js），
 * 未注册的 <el-*> 不报错，只会渲染成无样式的裸内容。
 */
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const src = path.join(__dirname, '../src');
const kebab = (name) => name.replace(/^El/, 'el').replace(/[A-Z]/g, (c) => `-${c.toLowerCase()}`);
// ElContainer 的 install 一并注册布局组件
const CONTAINER_FAMILY = ['el-aside', 'el-header', 'el-main', 'el-footer'];
// ElTimeline 的 install 一并注册 ElTimelineItem
const TIMELINE_FAMILY = ['el-timeline-item'];

function namesIn(block) {
  return [...block.matchAll(/\bEl[A-Z]\w*/g)].map((m) => kebab(m[0]));
}

function globallyRegistered() {
  const plugin = fs.readFileSync(path.join(src, 'plugins/element.js'), 'utf8');
  const list = plugin.match(/const components = \[([\s\S]*?)\];/)[1].replace(/\/\/.*$/gm, '');
  const names = new Set(namesIn(list));
  if (names.has('el-container')) CONTAINER_FAMILY.forEach((n) => names.add(n));
  if (names.has('el-timeline')) TIMELINE_FAMILY.forEach((n) => names.add(n));
  return names;
}

function vueFiles(dir) {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((e) => {
    const p = path.join(dir, e.name);
    return e.isDirectory() ? vueFiles(p) : p.endsWith('.vue') ? [p] : [];
  });
}

test('旅行卡页面的 <el-*> 组件均已全局注册或在本文件内引入', () => {
  const global = globallyRegistered();
  const missing = [];
  for (const file of vueFiles(path.join(src, 'pages/liroam'))) {
    const source = fs.readFileSync(file, 'utf8');
    const local = new Set(
      [...source.matchAll(/import\s*\{([^}]*)\}\s*from\s*["']element-plus["']/g)].flatMap((m) => namesIn(m[1])),
    );
    const used = new Set([...source.matchAll(/<(el-[a-z-]+)/g)].map((m) => m[1]));
    for (const tag of used) {
      if (!global.has(tag) && !local.has(tag)) missing.push(`${path.relative(src, file)}: <${tag}>`);
    }
  }
  assert.deepEqual(missing, []);
});
