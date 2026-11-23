// PC 装修示例数据：管理端 / 商家端编辑器预览与买家端无数据时的兜底共用这一份。
// 图片都在各端 public/liroam/decorate/（logo/tools/assets.py 生成）；链接按目的地关键词进商品列表。
// 只读：要写进组件 options 时先 copyOf() 一份。

const IMG = '/liroam/decorate';
const byKeyword = (keyword) => `/goodsList?keyword=${encodeURIComponent(keyword)}`;

export const copyOf = (value) => JSON.parse(JSON.stringify(value));

export const GOODS_PLACEHOLDER = '/liroam/goods-placeholder.svg';

// 套餐示例：名称、价格量级与图片都按出境上网卡写，图片按目的地区分。
export const MOCK_GOODS = [
  { id: '1', goodsName: '日本 eSIM 7 天 5GB 每日高速', price: '39.90', originalPrice: '49.90', thumbnail: `${IMG}/goods-jp.svg` },
  { id: '2', goodsName: '韩国 eSIM 5 天 3GB 落地即用', price: '29.90', originalPrice: '39.90', thumbnail: `${IMG}/goods-kr.svg` },
  { id: '3', goodsName: '泰国 实体卡 8 天 15GB 含当地号码', price: '49.00', originalPrice: '59.00', thumbnail: `${IMG}/goods-th.svg` },
  { id: '4', goodsName: '欧洲多国 eSIM 15 天 10GB 33 国通用', price: '99.00', originalPrice: '129.00', thumbnail: `${IMG}/goods-eu.svg` },
  { id: '5', goodsName: '美国 eSIM 10 天 10GB 支持热点', price: '89.00', originalPrice: '109.00', thumbnail: `${IMG}/goods-us.svg` },
  { id: '6', goodsName: '新加坡 马来西亚 eSIM 7 天 6GB', price: '35.00', originalPrice: '45.00', thumbnail: `${IMG}/goods-sea.svg` },
  { id: '7', goodsName: '中国香港 eSIM 3 天 3GB 需实名', price: '19.90', originalPrice: '25.90', thumbnail: `${IMG}/goods-hk.svg` },
  { id: '8', goodsName: '澳大利亚 新西兰 实体卡 12 天 12GB', price: '109.00', originalPrice: '139.00', thumbnail: `${IMG}/goods-anz.svg` },
  { id: '9', goodsName: '日本 1 天 1GB 流量加油包', price: '9.90', originalPrice: '12.90', thumbnail: `${IMG}/goods-jp.svg` },
  { id: '10', goodsName: '韩国 实体卡 7 天 每日 2GB 寄送', price: '59.00', originalPrice: '69.00', thumbnail: `${IMG}/goods-kr.svg` },
  { id: '11', goodsName: '越南 eSIM 7 天 5GB 多网切换', price: '32.00', originalPrice: '42.00', thumbnail: `${IMG}/goods-sea.svg` },
  { id: '12', goodsName: '全球通用 eSIM 30 天 20GB 百国可用', price: '199.00', originalPrice: '259.00', thumbnail: `${IMG}/goods-global.svg` },
];

export const MOCK_CAROUSEL = [
  { title: '出境上网，出发前备好', img: `${IMG}/banner-1.svg`, url: '/goodsList' },
  { title: 'eSIM 付款后在线发码', img: `${IMG}/banner-2.svg`, url: '/goodsList' },
  { title: '一人多卡，按卡管理', img: `${IMG}/banner-3.svg`, url: '/home/MyTravelCards' },
  { title: '流量不够，原卡加包', img: `${IMG}/banner-4.svg`, url: '/home/MyTravelCards' },
];

export const MOCK_BRAND_WALL = [
  { title: '日韩专区 · 热门套餐', img: `${IMG}/brand-1.svg`, url: byKeyword('日本') },
  { title: '东南亚 · 多国通用', img: `${IMG}/brand-2.svg`, url: byKeyword('泰国') },
  { title: '欧洲多国 · 一卡畅游', img: `${IMG}/brand-3.svg`, url: byKeyword('欧洲') },
  { title: '港澳台 · 短途必备', img: `${IMG}/brand-4.svg`, url: byKeyword('香港') },
];

export const MOCK_PROMO_CARDS = [
  { title: '限时特价', subtitle: '热门套餐限时优惠', bgImg: `${IMG}/promo-1.svg`, url: '/seckill' },
  { title: '新上架', subtitle: '新开通目的地套餐', bgImg: `${IMG}/promo-2.svg`, url: '/goodsList' },
  { title: '续费加包', subtitle: '原卡在线办理，无需换卡', bgImg: `${IMG}/promo-3.svg`, url: '/home/MyTravelCards' },
];

export const MOCK_CHANNELS = [
  { title: '按目的地选', subtitle: '天数流量标明', img: `${IMG}/channel-1.svg`, url: '/goodsList' },
  { title: '领券中心', subtitle: '先领再买', img: `${IMG}/channel-2.svg`, url: '/couponCenter' },
  { title: '我的卡', subtitle: '用量与续费', img: `${IMG}/channel-3.svg`, url: '/home/MyTravelCards' },
  { title: '开卡指南', subtitle: 'eSIM 与实体卡', img: `${IMG}/channel-4.svg`, url: '/article' },
];

export const MOCK_CATEGORY_NAV = ['日本', '韩国', '泰国', '新加坡', '中国香港', '欧洲多国', '美国', '澳新', '中东', '全球通用']
  .map((title, i) => ({ title, img: `${IMG}/nav-${i + 1}.svg`, url: byKeyword(title), badge: '' }));

export const MOCK_HOT_ZONE = {
  imageUrl: `${IMG}/hotzone.svg`,
  zones: [
    { title: '领券中心 · 下单前先领券', url: '/couponCenter', left: 3.5, top: 12, width: 28.5, height: 76 },
    { title: '热门目的地 · 按天数选购', url: '/goodsList', left: 35.5, top: 12, width: 28.5, height: 76 },
    { title: '续费加包 · 原卡续用', url: '/home/MyTravelCards', left: 67.5, top: 12, width: 29, height: 76 },
  ],
};

export const MOCK_FLOOR_BANNER = `${IMG}/floor-banner.svg`;
export const MOCK_VIDEO_COVER = `${IMG}/video-cover.svg`;

export const MOCK_FLOOR = {
  title: '日韩专区',
  subtitle: 'JAPAN & KOREA',
  subCategories: ['日本', '韩国', 'eSIM', '实体卡', '流量加油包'],
  posterTitle: '日韩出行 先备网',
  posterSub: '领券下单更划算',
  posterTags: ['落地即用', '按天选购', '原卡加包'],
};

export const MOCK_GOODS_CARD = {
  customTitle: '日本 eSIM · 落地即用',
  customDesc: '付款后在线发码，扫码即装；每日高速 5GB，用完可在「我的卡」直接加包。',
  badge: '热门',
};

export const MOCK_COUPONS = [
  { id: '1', price: 5, consumeThreshold: 39, title: '全场通用券', limitText: '全部套餐可用' },
  { id: '2', price: 10, consumeThreshold: 89, title: '日韩套餐券', limitText: '日本 / 韩国套餐可用' },
  { id: '3', price: 20, consumeThreshold: 159, title: '欧美长途券', limitText: '欧洲 / 美洲套餐可用' },
  { id: '4', price: 3, consumeThreshold: 0, title: '新客首单券', limitText: '首次下单专享' },
];

const CARD_TYPES = [['esim', 'eSIM'], ['sim', '实体卡'], ['topup', '流量加油包']];
const region = (id, name, ...children) => ({
  id,
  name,
  children: children.map(([cid, cname]) => ({
    id: cid,
    name: cname,
    children: CARD_TYPES.map(([tid, tname]) => ({ id: `${cid}-${tid}`, name: tname })),
  })),
});

// 分类菜单 / 选分类级联在接口未返回时的示例：按目的地分区。
export const MOCK_REGION_CATEGORIES = [
  region('asia-east', '日韩', ['jp', '日本'], ['kr', '韩国']),
  region('asia-se', '东南亚', ['th', '泰国'], ['sg', '新加坡'], ['my', '马来西亚'], ['vn', '越南']),
  region('hmt', '港澳台', ['hk', '中国香港'], ['mo', '中国澳门'], ['tw', '中国台湾']),
  region('europe', '欧洲', ['eu', '欧洲多国'], ['uk', '英国'], ['fr', '法国']),
  region('america', '美洲', ['us', '美国'], ['ca', '加拿大']),
  region('oceania', '澳新', ['au', '澳大利亚'], ['nz', '新西兰']),
  region('mideast', '中东非洲', ['ae', '阿联酋'], ['eg', '埃及']),
  region('global', '全球通用', ['multi', '多国通用']),
];

// 分类菜单选「自定义」时的初始占位；名称与这份完全一致视为未编辑，仍展示系统分类。
export const MOCK_MENU_PLACEHOLDER = [
  { name: '热门目的地', subCategories: [{ name: '日韩', items: ['日本', '韩国'] }, { name: '东南亚', items: ['泰国', '新加坡', '马来西亚', '越南'] }] },
  { name: '欧美澳新', subCategories: [{ name: '欧洲', items: ['欧洲多国', '英国', '法国'] }, { name: '美洲与大洋洲', items: ['美国', '加拿大', '澳大利亚', '新西兰'] }] },
  { name: '港澳台', subCategories: [{ name: '港澳', items: ['中国香港', '中国澳门'] }, { name: '台湾', items: ['中国台湾'] }] },
  { name: '按卡型选', subCategories: [{ name: '卡型', items: ['eSIM', '实体卡', '纯软卡'] }, { name: '续费加包', items: ['流量加油包', '续费套餐'] }] },
];

export function isMenuPlaceholder(categories) {
  if (!Array.isArray(categories) || categories.length !== MOCK_MENU_PLACEHOLDER.length) return false;
  return categories.every((c, i) => (c?.name || c?.title || '') === MOCK_MENU_PLACEHOLDER[i].name);
}
