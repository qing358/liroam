/** 积分商城兑券接口。@author Bulbasaur @since 2026-11-14 */
import request, { Method } from '@/plugins/request';
/** 分页查询当前可兑换活动，支持未登录浏览。 */
export const listPointsCoupons = params => request({ url: '/buyer/promotion/pointsCoupon', method: Method.GET, needToken: false, params });
/** 每次兑换一张，重试沿用请求号和确认积分数。 */
export const exchangePointsCoupon = (id, data) => request({ url: `/buyer/promotion/pointsCoupon/${id}/exchange`, method: Method.POST, needToken: true, data });
/** 查询本人成功兑换流水。 */
export const listPointsCouponRecords = params => request({ url: '/buyer/promotion/pointsCoupon/records', method: Method.GET, needToken: true, params });
