import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import test from 'node:test';

// 品牌闸：四个前端的源码与静态资源里不再出现上游 Lilishop 的名称、公司与官网。
// 允许的只有：上游移动端仓名（开发注释）、内部错误标记，以及上线前按 launch-checklist 替换的部署地址。
// 上游公开 OSS（lilishop-pc / lilishop-wechat）上的图一律不放行，装修示例图用仓内 /liroam/** 资源。
const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const uiRoot = path.join(repoRoot, 'shop/lilishop-ui');
const APPS = ['buyer', 'seller', 'manager', 'im'];
const TEXT = /\.(vue|js|mjs|cjs|ts|html|scss|less|css|json)$/;
const BRAND = /lilishop|pickmall|宏业汇成|lili ?im|lili-shop|lili商城/i;
const ALLOWED = [
  /lilishop-uniapp/g,
  /lilishop\/docs\/requirements/g,
  /lilishop\.buyer\.handledRequestError/g,
  /(https?|wss?):\/\/(admin-api|buyer-api|im-api|pc-b2b2c|m-b2b2c|store-b2b2c)\.pickmall\.cn/g,
  // 新买家站引用仓内品牌令牌的相对路径（nuxt.config 的 css、设计稿 styles.css 的 @import）
  /(\.\.\/)+shop\/lilishop-ui\/brand\//g,
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

// 新买家站 apps/buyer-web（Nuxt）：源码 app/、静态资源 public/、设计稿 design/，另加根目录的 nuxt.config.ts
const buyerWebRoot = path.join(repoRoot, 'apps/buyer-web');
const buyerWebFiles = [
  ...['app', 'public', 'design'].flatMap((d) => walk(path.join(buyerWebRoot, d))),
  path.join(buyerWebRoot, 'nuxt.config.ts'),
];

const appFiles = [
  ...APPS.flatMap((app) => ['src', 'public'].flatMap((d) => walk(path.join(uiRoot, app, d)))),
  ...walk(path.join(uiRoot, 'brand')),
  ...buyerWebFiles,
];

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

// 上游自带的定位语、页脚承诺与装修样例宣传语（含与旅行卡售后不符的「7天无理由」「顺丰」等），
// 以及 PC 装修模板内置的上游示例商品与类目；装修示例统一取 brand/decorate-mock.js。
const UPSTREAM_COPY = [
  '企业级电商运营管理平台', '为平台运营团队提供稳定', '商品、营销、会员一体化运营', '多店铺管理与经营数据分析',
  '在线购物平台', 'content="keywords"', 'content="description"', 'lili-system.oss-cn-beijing.aliyuncs.com/background.jpg',
  '品类齐全', '多仓直发', '正品行货', '畅选无忧', '欢迎来到商城', '购物指南', "'品质优选', desc: '放心购物'",
  '品牌故事 · 匠心传承', '甄选美好生活', '官方商城运营部', '100% 官方正品', '7天无理由', '极速顺丰', '>顺丰速运<',
  '商城秋季大促', '商城系统', '品质生活馆', '官方正品', '镇店爆品', '正品严选', '电商之旅',
  '开源不易', 'vue-antd-pro', "'手机', '数码家电'", "'iPhone 16 Pro', '小棕瓶'", '一家很有品的店', '/demo/', "defaultThumb = 'https://lilishop",
  '智能手机', '咖啡机', '电脑办公', '家用电器', '食品生鲜', '双层收纳', '双11', 'DOUBLE11',
];

function copyHits(file) {
  return fs.readFileSync(file, 'utf8').split('\n').flatMap((line, i) =>
    UPSTREAM_COPY.filter((s) => line.includes(s)).map((s) => `${path.relative(repoRoot, file)}:${i + 1}: ${s}`));
}

test('前端不再出现上游定位语、页脚承诺与装修样例宣传语', () => {
  const hits = appFiles.filter((f) => TEXT.test(f)).flatMap(copyHits);
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

test('管理端登录页版权只在左侧品牌区出现一次', () => {
  const read = (rel) => fs.readFileSync(path.join(uiRoot, 'manager/src', rel), 'utf8');
  const page = read('views/login.vue') + read('views/main-parts/footer.vue');
  assert.equal(page.match(/Copyright ©/g)?.length, 1);
  assert.match(read('views/login.vue'), /class="brand-copyright">Copyright ©/);
});

// 上游主题色：管理 / 商家 / 买家的主题红、Element 默认蓝及其派生色、装修模板与面板里的强调红 / 蓝（含 rgb 写法）、装修优惠券的玫红 / 紫粉皮肤色、
// 上游暖色强调（橙 / 琥珀 / 粉及其浅底与描边，装修组件、编辑器面板、卡券与素材页按钮）。
// slate 等中性色与 Tailwind 浅蓝 / 浅红底在多处作普通中性色，不全局拦；优惠券皮肤块另由下方用例要求只用令牌。
// Element 的状态色（成功 / 警告 / 危险 #f56c6c）是语义色，不在此列；装修「预设」整页模板（presets/）是运营可选的活动样例，也不在此列。
const UPSTREAM_COLORS = new RegExp('#(' + [
  '409eff', '2d8cf0', '2b85e4', '1890ff', '1677ff', '66b1ff', '5cadff', '79bbff', 'a0cfff', 'c6e2ff', 'd9ecff', 'ecf5ff', '3a8ee6', '337ecc',
  '3b82f6', '2563eb', '1d4ed8', '1e40af', '438cde', '57a3f3', '508afe', '315efb', '005cc5', '3a6fd8',
  'ff5c58', 'f31947', 'ff3c2a', 'f5222d', 'ef4444', 'e1251b', 'e4393c', 'e23a3a', 'ed3f14', 'ff4d4f', 'ff7875', 'ff6b35', 'ff9f28',
  'e11d48', 'dc2626', 'f5483a', 'e1212b', 'e93323', 'fa2c19', 'ff1e1e', 'e52d27', 'ed4014', 'dd4b39', 'ff2200', 'e31414',
  'f43f5e', 'ffe4e6', '8b5cf6', 'ec4899',
  'ff6a00', 'f97316', 'ea580c', 'fb923c', 'fdba74', 'fed7aa', 'ffedd5', 'fff7ed', 'fffaf5', 'b35c12', '9a4b0b',
  'f59e0b', 'd97706', 'fde68a', 'fef3c7', 'fffbeb', 'db2777', 'fbcfe8', 'fdf2f8',
].join('|') + ')(?![0-9a-f])|rgba?\\( *(64, *158, *255|255, *92, *88|243, *25, *71|255, *60, *42|239, *68, *68)\\b', 'i');
// 上游散落的纯红（red / #f00 / #ff0000）：只认样式上下文，皮肤名 'red' 之类的标识符不算；报错等语义色改用 var(--el-color-danger)。
const UPSTREAM_RED = /#(f00|ff0000)(?![0-9a-f])|\b(color|background(-color)?|border(-[a-z]+)*|fill|stroke|outline(-color)?|bg-?color)(\.value)?\s*[:=]\s*["']?red\b|\b(solid|dashed|dotted)\s+red\b/i;
const STYLE = /\.(vue|js|scss|less|css|html)$/;

function colorHits(file) {
  return fs.readFileSync(file, 'utf8').split('\n').flatMap((line, i) =>
    UPSTREAM_COLORS.test(line) || UPSTREAM_RED.test(line) ? [`${path.relative(repoRoot, file)}:${i + 1}: ${line.trim().slice(0, 120)}`] : []);
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
  const expect = { primary: palette['漫游青'], ink: palette['夜航墨'], accent: palette['日出橙'], sun: palette['日光黄'], mist: palette['雾青'] };
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

const COUPON_SKINS = ['manager/src/views/page-decoration/pc/template/TplCoupon.vue', 'buyer/src/components/indexDecorate/components/CouponList.vue'];

test('装修优惠券皮肤只用轻游令牌色，编辑器可选皮肤与渲染端一致', () => {
  const panel = tokens('manager/src/views/page-decoration/pc/config-panel/PanelCoupon.vue');
  const offered = [...panel.matchAll(/<el-radio-button label="([a-z]+)">/g)].map((m) => m[1]);
  assert.deepEqual(offered, ['accent', 'ink', 'primary']);
  assert.match(tokens('manager/src/views/page-decoration/pc/config.js'), /skin: 'accent'/);
  for (const rel of COUPON_SKINS) {
    const src = tokens(rel);
    const skins = Object.fromEntries([...src.matchAll(/&\.skin-([a-z]+) \{[\s\S]*?\n {2}\}/g)].map((m) => [m[1], m[0]]));
    assert.deepEqual(Object.keys(skins), offered, rel);
    for (const [name, block] of Object.entries(skins)) {
      assert.doesNotMatch(block, /#[0-9a-f]{3,8}\b|rgba?\(/i, `${rel} skin-${name}`);
      assert.match(block, /var\(--lr-/, `${rel} skin-${name}`);
    }
    assert.match(src, new RegExp(`skin-\\$\\{options\\.skin \\|\\| 'accent'\\}`), rel);
  }
});

test('四端都加载轻游主题', () => {
  for (const app of APPS) {
    assert.match(fs.readFileSync(path.join(uiRoot, app, 'src/main.js'), 'utf8'), /import ["'](\.\.\/)+brand\/theme\.css["']/, app);
  }
});

// 新买家站颜色只有一个来源：brand/theme.css 的 --lr-* 令牌（Nuxt UI 的 --ui-* 变量指向它们），源码里不写任何色值，
// 也不用 Tailwind 自带色板类（bg-blue-500 之类）与任意色值类（text-[#xxx]）。
const ANY_COLOR = /#[0-9a-f]{3,8}\b|\b(rgba?|hsla?|oklch|oklab|lab|lch)\(/i;
const TAILWIND_PALETTE = /\b(bg|text|border|ring|ring-offset|from|via|to|fill|stroke|outline|divide|shadow|accent|caret|decoration|placeholder)-(slate|gray|zinc|neutral|stone|red|orange|amber|yellow|lime|green|emerald|teal|cyan|sky|blue|indigo|violet|purple|fuchsia|pink|rose)-\d{2,3}\b|-\[#[0-9a-f]+\]/i;
const buyerWebSource = buyerWebFiles.filter((f) => /\.(vue|ts|js|css|html)$/.test(f));

test('新买家站源码不写色值、不用 Tailwind 自带色板', () => {
  assert.ok(buyerWebSource.length > 0, 'apps/buyer-web 没有源码');
  const hits = buyerWebSource.flatMap((file) => fs.readFileSync(file, 'utf8').split('\n').flatMap((line, i) =>
    ANY_COLOR.test(line) || TAILWIND_PALETTE.test(line) ? [`${path.relative(repoRoot, file)}:${i + 1}: ${line.trim().slice(0, 120)}`] : []));
  assert.deepEqual(hits, []);
});

test('新买家站加载轻游主题，Nuxt UI 主色与正文色指向品牌令牌', () => {
  const nuxtConfig = fs.readFileSync(path.join(buyerWebRoot, 'nuxt.config.ts'), 'utf8');
  assert.match(nuxtConfig, /['"][^'"]*shop\/lilishop-ui\/brand\/theme\.css['"]/);
  const css = fs.readFileSync(path.join(buyerWebRoot, 'app/assets/css/main.css'), 'utf8');
  assert.match(css, /--ui-primary:\s*var\(--lr-primary\);/);
  assert.match(css, /--ui-text:\s*var\(--lr-ink\);/);
});

test('新买家站 favicon 是轻游 favicon，页面描述写轻游出境旅行通信卡', () => {
  const favicon = fs.readFileSync(path.join(repoRoot, 'logo/png/favicon.ico'));
  assert.ok(fs.readFileSync(path.join(buyerWebRoot, 'public/favicon.ico')).equals(favicon));
  const nuxtConfig = fs.readFileSync(path.join(buyerWebRoot, 'nuxt.config.ts'), 'utf8');
  const description = nuxtConfig.match(/name: 'description', content: '([^']*)'/)?.[1] ?? '';
  assert.match(description, /轻游/);
  assert.match(description, /旅行通信卡/);
});

test('闸本身：新买家站色值闸拦写死色值与 Tailwind 色板类，放过令牌类', () => {
  for (const line of ['color: #0b7a75;', "class=\"bg-blue-500\"", 'text-[#ff0000]', 'background: rgb(1, 2, 3)', 'oklch(0.7 0.1 200)']) {
    assert.ok(ANY_COLOR.test(line) || TAILWIND_PALETTE.test(line), line);
  }
  for (const line of ['class="text-primary bg-elevated"', 'color: var(--lr-ink);', "to: '/esim/jp'", 'border-default']) {
    assert.ok(!ANY_COLOR.test(line) && !TAILWIND_PALETTE.test(line), line);
  }
});

test('闸本身：拦上游主色，放过状态色', () => {
  assert.ok(UPSTREAM_COLORS.test('color: #409EFF;'));
  assert.ok(UPSTREAM_COLORS.test('background: rgba(64, 158, 255, 0.1)'));
  assert.ok(UPSTREAM_COLORS.test("mainColor:'#ff5c58'"));
  assert.ok(UPSTREAM_COLORS.test('border-color: #E11D48'));
  assert.ok(UPSTREAM_COLORS.test('background: linear-gradient(135deg, #8b5cf6 0%, #ec4899 100%);'));
  for (const line of ['color: #f97316;', 'border: 1px solid #FDE68A;', "'#ffedd5',", '&.yellow { background: #f59e0b; }']) assert.ok(UPSTREAM_COLORS.test(line), line);
  assert.ok(!UPSTREAM_COLORS.test('color: #c8461f;'));
  assert.ok(!UPSTREAM_COLORS.test('color: #f56c6c;'));
  assert.ok(!UPSTREAM_COLORS.test('color: #409eff0;'));
  assert.ok(!UPSTREAM_COLORS.test('color: #0b7a75;'));
  for (const line of ['color: red;', "style=\"color:red\"", 'border: 1px solid red', 'background:#f00', 'color: #FF0000;', 'color = "red";', "bgColor: 'red'", 'bgColor.value = "red";']) {
    assert.ok(UPSTREAM_RED.test(line), line);
  }
  for (const line of ["skin: 'red'", "value: 'red', label: '红色'", 'color: #f00a12;', 'color: var(--el-color-danger);', 'colored: true']) {
    assert.ok(!UPSTREAM_RED.test(line), line);
  }
});

const SITE_ASSET = /\/liroam\/[a-z0-9/-]+\.svg/g;

test('装修示例与页面引用的站内素材，在用到它的前端 public 里都有', async () => {
  const mock = await import(pathToFileURL(path.join(uiRoot, 'brand/decorate-mock.js')));
  const shared = JSON.stringify(Object.values(mock).filter((v) => typeof v !== 'function'));
  const missing = [];
  for (const app of ['buyer', 'seller', 'manager']) {
    const src = appFiles.filter((f) => f.startsWith(path.join(uiRoot, app, 'src') + path.sep) && TEXT.test(f));
    const refs = new Set([shared, ...src.map((f) => fs.readFileSync(f, 'utf8'))].flatMap((s) => s.match(SITE_ASSET) || []));
    for (const ref of refs) {
      if (!fs.existsSync(path.join(uiRoot, app, 'public', ref))) missing.push(`${app}/public${ref}`);
    }
  }
  assert.deepEqual(missing, []);
});

test('库里首页分类菜单占位与前端判定占位的是同一份', async () => {
  const { MOCK_MENU_PLACEHOLDER } = await import(pathToFileURL(path.join(uiRoot, 'brand/decorate-mock.js')));
  const sql = fs.readFileSync(
    path.join(repoRoot, 'modules/liroam-schema/src/main/resources/db/migration/liroam/V202611261330__brand_liroam_menu_placeholder.sql'), 'utf8');
  const written = sql.match(/SET @new := '([\s\S]*?)';/)[1];
  assert.deepEqual(JSON.parse(written), MOCK_MENU_PLACEHOLDER);
});

test('闸本身：能抓到品牌名与上游图床，放过部署地址', () => {
  const probe = (line) => BRAND.test(ALLOWED.reduce((s, re) => s.replace(re, ''), line));
  assert.ok(probe("title: 'lilishop'"));
  assert.ok(probe('Copyright © 2026 Lilishop.'));
  assert.ok(probe('https://lilishop-pc.oss-cn-beijing.aliyuncs.com/static/logo/logo.png'));
  assert.ok(probe('<a href="https://pickmall.cn">'));
  assert.ok(!probe("css: ['../../shop/lilishop-ui/brand/theme.css']"));
  assert.ok(probe("import x from 'shop/lilishop-ui/buyer/src/x'"));
  assert.ok(probe('https://lilishop-pc.oss-cn-beijing.aliyuncs.com/static/goods/goods_1.png'));
  assert.ok(probe('img: "https://lilishop-wechat.oss-cn-beijing.aliyuncs.com/tpl-1.png"'));
  assert.ok(!probe('API_PROD: { buyer: "https://buyer-api.pickmall.cn" }'));
});
