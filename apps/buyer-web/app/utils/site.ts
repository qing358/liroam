// 站点口径（无依赖，nuxt.config 也引用）
/** 站点名、默认描述（store brand-copy「新买家站 · 全站 title / description」）、运营主体与分享图 */
export const SITE = {
  name: '轻游',
  description: '轻游 Liroam 出境旅行通信卡：按目的地选购 eSIM 与实体卡，付款后在线发码，随时查询用量、续费加包。',
  company: '想象智能（济南）有限公司',
  shareImage: '/brand/liroam-share.png',
  titleTemplate: '%s · 轻游',
  helpTitleTemplate: '%s · 轻游帮助中心',
} as const
