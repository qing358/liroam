import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

// 品牌闸：四个前端的源码与静态资源里不再出现上游 Lilishop 的名称、公司与官网。
// 允许的只有：上游公开 OSS 上的装修演示素材（非 logo）、上游移动端仓名（开发注释）、内部错误标记、
// 以及上线前按 launch-checklist 替换的部署地址。
const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const uiRoot = path.join(repoRoot, 'shop/lilishop-ui');
const APPS = ['buyer', 'seller', 'manager', 'im'];
const TEXT = /\.(vue|js|mjs|cjs|ts|html|scss|less|css|json)$/;
const BRAND = /lilishop|pickmall|宏业汇成|lili ?im|lili-shop|lili商城/i;
const ALLOWED = [
  /lilishop-(pc|wechat)\.oss-cn-beijing\.aliyuncs\.com\/(?!static\/logo\/)/gi,
  /lilishop-uniapp/g,
  /lilishop\/docs\/requirements/g,
  /lilishop\.buyer\.handledRequestError/g,
  /(https?|wss?):\/\/(admin-api|buyer-api|im-api|pc-b2b2c|m-b2b2c|store-b2b2c)\.pickmall\.cn/g,
];
const BRAND_IMAGES = ['logo-lilishop.png', 'lili.png'];

function walk(dir, out = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (entry.name === 'node_modules' || entry.name === 'tinymce' || entry.name === 'dist') continue;
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(full, out);
    else out.push(full);
  }
  return out;
}

function brandHits(file) {
  return fs.readFileSync(file, 'utf8').split('\n').flatMap((line, i) => {
    const rest = ALLOWED.reduce((s, re) => s.replace(re, ''), line);
    return BRAND.test(rest) ? [`${path.relative(repoRoot, file)}:${i + 1}: ${line.trim().slice(0, 120)}`] : [];
  });
}

const appFiles = APPS.flatMap((app) => ['src', 'public'].flatMap((d) => walk(path.join(uiRoot, app, d))));

test('前端源码与静态资源不出现上游品牌名、公司名与官网', () => {
  const hits = appFiles.filter((f) => TEXT.test(f) && !f.endsWith('config.local.js')).flatMap(brandHits);
  assert.deepEqual(hits, []);
});

test('上游 logo 图片已移除', () => {
  assert.deepEqual(appFiles.filter((f) => BRAND_IMAGES.includes(path.basename(f))).map((f) => path.relative(repoRoot, f)), []);
});

test('favicon 统一为轻游 favicon（不随主题或站点设置变化）', () => {
  const favicon = fs.readFileSync(path.join(repoRoot, 'logo/png/favicon.ico'));
  for (const app of APPS) {
    const html = fs.readFileSync(path.join(uiRoot, app, 'public/index.html'), 'utf8');
    const href = html.match(/<link rel="icon" href="(?:<%= BASE_URL %>|\.\/)?([^"]+)"/);
    assert.ok(href, `${app} index.html 缺 favicon`);
    assert.ok(fs.readFileSync(path.join(uiRoot, app, 'public', href[1])).equals(favicon), `${app} favicon 不是轻游 favicon`);
  }
});

// 上游自带的定位语、页脚承诺与装修样例宣传语（含与旅行卡售后不符的「7天无理由」「顺丰」等）。
// 装修「预设」整页模板（presets/）是运营可选的活动样例，不在此列。
const UPSTREAM_COPY = [
  '企业级电商运营管理平台', '为平台运营团队提供稳定', '商品、营销、会员一体化运营', '多店铺管理与经营数据分析',
  '在线购物平台', 'content="keywords"', 'content="description"', 'lili-system.oss-cn-beijing.aliyuncs.com/background.jpg',
  '品类齐全', '多仓直发', '正品行货', '畅选无忧', '欢迎来到商城', '购物指南', "'品质优选', desc: '放心购物'",
  '品牌故事 · 匠心传承', '甄选美好生活', '官方商城运营部', '100% 官方正品', '7天无理由', '极速顺丰', '>顺丰速运<',
  '商城秋季大促', '商城系统', '品质生活馆', '官方正品', '镇店爆品', '正品严选', '电商之旅',
  '开源不易', 'vue-antd-pro', "'手机', '数码家电'",
];

function copyHits(file) {
  return fs.readFileSync(file, 'utf8').split('\n').flatMap((line, i) =>
    UPSTREAM_COPY.filter((s) => line.includes(s)).map((s) => `${path.relative(repoRoot, file)}:${i + 1}: ${s}`));
}

test('前端不再出现上游定位语、页脚承诺与装修样例宣传语', () => {
  const hits = appFiles.filter((f) => TEXT.test(f) && !f.includes(`${path.sep}presets${path.sep}`)).flatMap(copyHits);
  assert.deepEqual(hits, []);
});

test('三端页面 meta 描述轻游出境旅行通信卡业务', () => {
  for (const app of ['buyer', 'seller', 'manager']) {
    const html = fs.readFileSync(path.join(uiRoot, app, 'public/index.html'), 'utf8');
    const descriptions = [...html.matchAll(/<meta name=['"]description['"] content=['"]([^'"]*)['"]/g)].map((m) => m[1]);
    const keywords = [...html.matchAll(/<meta name=['"]keywords['"] content=['"]([^'"]*)['"]/g)].map((m) => m[1]);
    assert.equal(descriptions.length, 1, `${app} 应只有一条 description`);
    assert.match(descriptions[0], /轻游/, `${app} description 缺品牌`);
    assert.match(descriptions[0], /旅行通信卡/, `${app} description 缺业务定位`);
    assert.equal(keywords.length, 1, `${app} 应有一条 keywords`);
    assert.match(keywords[0], /轻游/);
  }
});

test('三端登录页带各自角色的轻游定位语', () => {
  const login = (rel) => fs.readFileSync(path.join(uiRoot, rel), 'utf8');
  assert.match(login('manager/src/views/login.vue'), /class="brand-title">[^<]*通信卡[^<]*</);
  assert.match(login('seller/src/views/login.vue'), /class="brand-title">[^<]*商家[^<]*</);
  for (const rel of ['buyer/src/pages/Login.vue', 'buyer/src/pages/SignUp.vue']) {
    assert.match(login(rel), /class="brand-title">[^<]*出境[^<]*</, rel);
  }
});

// 上游主题色：管理 / 商家 / 买家的主题红、Element 默认蓝及其派生色、装修模板与面板里的强调红 / 蓝（含 rgb 写法）。
// Element 的状态色（成功 / 警告 / 危险 #f56c6c）是语义色，不在此列；装修「预设」整页模板（presets/）是运营可选的活动样例，也不在此列。
const UPSTREAM_COLORS = new RegExp('#(' + [
  '409eff', '2d8cf0', '2b85e4', '1890ff', '1677ff', '66b1ff', '5cadff', '79bbff', 'a0cfff', 'c6e2ff', 'd9ecff', 'ecf5ff', '3a8ee6', '337ecc',
  '3b82f6', '2563eb', '1d4ed8', '1e40af', '438cde', '57a3f3', '508afe', '315efb', '005cc5', '3a6fd8',
  'ff5c58', 'f31947', 'ff3c2a', 'f5222d', 'ef4444', 'e1251b', 'e4393c', 'e23a3a', 'ed3f14', 'ff4d4f', 'ff7875', 'ff6b35', 'ff9f28',
  'e11d48', 'dc2626', 'f5483a', 'e1212b', 'e93323', 'fa2c19', 'ff1e1e', 'e52d27', 'ed4014', 'dd4b39', 'ff2200', 'e31414',
].join('|') + ')(?![0-9a-f])|rgba?\\( *(64, *158, *255|255, *92, *88|243, *25, *71|255, *60, *42|239, *68, *68)\\b', 'i');
const STYLE = /\.(vue|js|scss|less|css|html)$/;

function colorHits(file) {
  return fs.readFileSync(file, 'utf8').split('\n').flatMap((line, i) =>
    UPSTREAM_COLORS.test(line) ? [`${path.relative(repoRoot, file)}:${i + 1}: ${line.trim().slice(0, 120)}`] : []);
}

test('前端不再出现上游主题红 / 蓝色值', () => {
  const hits = appFiles
    .filter((f) => STYLE.test(f) && !f.endsWith('config.local.js') && !f.includes(`${path.sep}presets${path.sep}`))
    .flatMap(colorHits);
  assert.deepEqual(hits, []);
});

const palette = Object.fromEntries(
  [...fs.readFileSync(path.join(repoRoot, 'logo/README.md'), 'utf8').matchAll(/^\| (漫游青|夜航墨|日出橙|日光黄|雾青) \|[^|]*\| `(#[0-9A-F]{6})`/gm)]
    .map((m) => [m[1], m[2].toLowerCase()]));
const tokens = (rel) => fs.readFileSync(path.join(uiRoot, rel), 'utf8');

test('主题色板与 logo/README.md 一致（scss / less / css 三份令牌同值）', () => {
  assert.equal(Object.keys(palette).length, 5, 'logo/README.md 色值表');
  const expect = { primary: palette['漫游青'], ink: palette['夜航墨'], accent: palette['日出橙'], mist: palette['雾青'] };
  const read = {
    scss: (k) => tokens('brand/tokens.scss').match(new RegExp(`^\\$lr-${k}: (#[0-9a-f]{6});`, 'mi'))?.[1].toLowerCase(),
    less: (k) => tokens('brand/tokens.less').match(new RegExp(`^@lr-${k}: (#[0-9a-f]{6});`, 'mi'))?.[1].toLowerCase(),
    css: (k) => tokens('brand/theme.css').match(new RegExp(`--lr-${k}: (#[0-9a-f]{6});`, 'i'))?.[1].toLowerCase(),
  };
  for (const [k, v] of Object.entries(expect)) for (const [kind, get] of Object.entries(read)) assert.equal(get(k), v, `${kind} ${k}`);
  for (const k of ['primary-hover', 'primary-active', 'primary-light-3', 'primary-light-5', 'primary-light-7', 'primary-light-8', 'accent-text']) {
    assert.ok(read.scss(k), `scss 缺 ${k}`);
    assert.equal(read.less(k), read.scss(k), `less ${k}`);
    assert.equal(read.css(k), read.scss(k), `css ${k}`);
  }
  assert.match(tokens('brand/theme.css'), /--el-color-primary: var\(--lr-primary\);/);
  assert.match(tokens('buyer/src/utils/theme.js'), new RegExp(`themeColor: "${expect.primary}"`, 'i'));
});

test('四端都加载轻游主题', () => {
  for (const app of APPS) {
    assert.match(fs.readFileSync(path.join(uiRoot, app, 'src/main.js'), 'utf8'), /import ["'](\.\.\/)+brand\/theme\.css["']/, app);
  }
});

test('闸本身：拦上游主色，放过状态色', () => {
  assert.ok(UPSTREAM_COLORS.test('color: #409EFF;'));
  assert.ok(UPSTREAM_COLORS.test('background: rgba(64, 158, 255, 0.1)'));
  assert.ok(UPSTREAM_COLORS.test("mainColor:'#ff5c58'"));
  assert.ok(UPSTREAM_COLORS.test('border-color: #E11D48'));
  assert.ok(!UPSTREAM_COLORS.test('color: #f56c6c;'));
  assert.ok(!UPSTREAM_COLORS.test('color: #409eff0;'));
  assert.ok(!UPSTREAM_COLORS.test('color: #0b7a75;'));
});

test('闸本身：能抓到品牌名，放过演示素材与部署地址', () => {
  const probe = (line) => BRAND.test(ALLOWED.reduce((s, re) => s.replace(re, ''), line));
  assert.ok(probe("title: 'lilishop'"));
  assert.ok(probe('Copyright © 2026 Lilishop.'));
  assert.ok(probe('https://lilishop-pc.oss-cn-beijing.aliyuncs.com/static/logo/logo.png'));
  assert.ok(probe('<a href="https://pickmall.cn">'));
  assert.ok(!probe('https://lilishop-pc.oss-cn-beijing.aliyuncs.com/static/goods/goods_1.png'));
  assert.ok(!probe('API_PROD: { buyer: "https://buyer-api.pickmall.cn" }'));
});
