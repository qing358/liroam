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

test('闸本身：能抓到品牌名，放过演示素材与部署地址', () => {
  const probe = (line) => BRAND.test(ALLOWED.reduce((s, re) => s.replace(re, ''), line));
  assert.ok(probe("title: 'lilishop'"));
  assert.ok(probe('Copyright © 2026 Lilishop.'));
  assert.ok(probe('https://lilishop-pc.oss-cn-beijing.aliyuncs.com/static/logo/logo.png'));
  assert.ok(probe('<a href="https://pickmall.cn">'));
  assert.ok(!probe('https://lilishop-pc.oss-cn-beijing.aliyuncs.com/static/goods/goods_1.png'));
  assert.ok(!probe('API_PROD: { buyer: "https://buyer-api.pickmall.cn" }'));
});
