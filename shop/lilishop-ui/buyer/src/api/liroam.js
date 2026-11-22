import request, { Method } from "@/plugins/request.js";

const JSON_HEADERS = { "Content-Type": "application/json" };

/** 我的旅行卡列表（CardView[]） */
export function listMyCards() {
  return request({ url: "/buyer/liroam/cards", method: Method.GET, needToken: true });
}

/** 卡详情（含套餐 plans；后端按需刷新，带冷却） */
export function getMyCard(id) {
  return request({ url: `/buyer/liroam/cards/${encodeURIComponent(id)}`, method: Method.GET, needToken: true });
}

/** 查看激活码（后端留审计） */
export function getCardCredential(id) {
  return request({ url: `/buyer/liroam/cards/${encodeURIComponent(id)}/credential`, method: Method.GET, needToken: true });
}

/** 实体卡物流轨迹 {logisticsCompany, logisticsNo, shippedText, message, events:[{timeText, description}]}；未寄出时 result 为 null */
export function getCardTracking(id) {
  return request({ url: `/buyer/liroam/cards/${encodeURIComponent(id)}/tracking`, method: Method.GET, needToken: true });
}

/** 买家自报已完成实名（实名后交付的卡随即发放），返回最新 CardView */
export function declareCardKyc(id) {
  return request({ url: `/buyer/liroam/cards/${encodeURIComponent(id)}/kyc-declaration`, method: Method.POST, needToken: true, headers: JSON_HEADERS, data: {} });
}

/** 可加的套餐 [{skuId, goodsId, name, priceFen}] */
export function getTopUpOptions(id) {
  return request({ url: `/buyer/liroam/cards/${encodeURIComponent(id)}/topup-options`, method: Method.GET, needToken: true });
}

/** 登记加包意图，之后走壳「立即购买」 */
export function openTopUpIntent(id, skuId) {
  return request({
    url: `/buyer/liroam/cards/${encodeURIComponent(id)}/topup-intents`,
    method: Method.POST,
    needToken: true,
    headers: JSON_HEADERS,
    data: { skuId },
  });
}

/** 按单张卡申请退款 */
export function applyCardRefund(id, reason) {
  return request({
    url: `/buyer/liroam/cards/${encodeURIComponent(id)}/refund`,
    method: Method.POST,
    needToken: true,
    headers: JSON_HEADERS,
    data: { reason },
  });
}

/** 商品详情页「使用区域说明」（公开接口；无说明时 result 为 null） */
export function getUsageRegion(skuId) {
  return request({
    url: "/buyer/goods/liroam/usage-region",
    method: Method.GET,
    needToken: false,
    optionalToken: true,
    skipMessage: true,
    params: { skuId },
  });
}
