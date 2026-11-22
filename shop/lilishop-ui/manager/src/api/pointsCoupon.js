/** 积分兑券配置及流水接口。@author Bulbasaur @since 2026-11-14 */
import { getRequest, postRequest, putRequest } from '@/libs/axios';

/** 查询活动。 */
export const listPointsCoupons = params => getRequest('/promotion/pointsGoods/coupons', params);
/** 保存活动配置。 */
export const savePointsCoupon = data => postRequest('/promotion/pointsGoods/coupons', data, { 'Content-Type': 'application/json' });
/** 修改上下架状态。 */
export const setPointsCouponStatus = (id, enabled) => putRequest(`/promotion/pointsGoods/coupons/${id}/status`, { enabled });
/** 查询成功兑换流水。 */
export const listPointsCouponRecords = params => getRequest('/promotion/pointsGoods/coupons/records', params);
