import { getRequest, postRequest, putRequest, uploadFileRequest } from "@/libs/axios";

// 旅行卡后台接口；请求基址已含 /manager 前缀
const JSON_HEADERS = { "Content-Type": "application/json" };

function upload(url, file, fields = {}) {
  const form = new FormData();
  form.append("file", file);
  Object.entries(fields).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== "") form.append(key, value);
  });
  return uploadFileRequest(url, form);
}

// 卡台账
export const listCards = (params) => getRequest("/liroam/cards", params);
export const getCard = (id) => getRequest(`/liroam/cards/${encodeURIComponent(id)}`);
export const saveCardCredential = (id, lpa) => postRequest(`/liroam/cards/${encodeURIComponent(id)}/credential`, { lpa }, JSON_HEADERS);
export const importCredentials = (file) => upload("/liroam/credentials/import", file);
export const importPool = (file, supplierId, cardProductId) => upload("/liroam/pool/import", file, { supplierId, cardProductId });

// 人工待办
export const listManualTasks = (params) => getRequest("/liroam/manual-tasks", params);
export const resolveManualTask = (id, note) => postRequest(`/liroam/manual-tasks/${encodeURIComponent(id)}/resolve`, { note }, JSON_HEADERS);

// 退款
export const listRefunds = (params) => getRequest("/liroam/refunds", params);
/** action: approve | reject | complete */
export const actOnRefund = (id, action, note) => postRequest(`/liroam/refunds/${encodeURIComponent(id)}/${action}`, note ? { note } : {}, JSON_HEADERS);
export const listRefundRules = () => getRequest("/liroam/refund-rules");
export const saveRefundRule = (rule) => postRequest("/liroam/refund-rules", rule, JSON_HEADERS);

// 定价
export const listRepriceSuggestions = (params) => getRequest("/liroam/reprice-suggestions", params);
/** action: confirm | reject */
export const actOnRepriceSuggestion = (id, action) => postRequest(`/liroam/reprice-suggestions/${encodeURIComponent(id)}/${action}`, {}, JSON_HEADERS);
export const getPricingSettings = () => getRequest("/liroam/settings/pricing");
export const savePricingSettings = (settings) => putRequest("/liroam/settings/pricing", settings, JSON_HEADERS);
export const listFxRates = (params) => getRequest("/liroam/fx/rates", params);
export const syncFxRates = () => postRequest("/liroam/fx/sync", {}, JSON_HEADERS);
export const listTariffSheets = (params) => getRequest("/liroam/tariff-sheets", params);
export const uploadTariffSheet = (file, supplierId) => upload("/liroam/tariff-sheets", file, { supplierId });
export const activateTariffSheet = (id) => postRequest(`/liroam/tariff-sheets/${encodeURIComponent(id)}/activate`, {}, JSON_HEADERS);
export const listCardProductRegions = (id) => getRequest(`/liroam/card-products/${encodeURIComponent(id)}/regions`);
