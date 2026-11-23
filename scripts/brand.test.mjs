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

test('闸本身：能抓到品牌名，放过演示素材与部署地址', () => {
  const probe = (line) => BRAND.test(ALLOWED.reduce((s, re) => s.replace(re, ''), line));
  assert.ok(probe("title: 'lilishop'"));
  assert.ok(probe('Copyright © 2026 Lilishop.'));
  assert.ok(probe('https://lilishop-pc.oss-cn-beijing.aliyuncs.com/static/logo/logo.png'));
  assert.ok(probe('<a href="https://pickmall.cn">'));
  assert.ok(!probe('https://lilishop-pc.oss-cn-beijing.aliyuncs.com/static/goods/goods_1.png'));
  assert.ok(!probe('API_PROD: { buyer: "https://buyer-api.pickmall.cn" }'));
});
