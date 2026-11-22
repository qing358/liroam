import { deleteRequest, getRequest, postRequest, putRequest, uploadFileRequest } from "@/libs/axios";

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
/** 实体卡物流轨迹；未寄出时 result 为 null */
export const getCardTracking = (id) => getRequest(`/liroam/cards/${encodeURIComponent(id)}/tracking`);
export const saveCardCredential = (id, lpa) => postRequest(`/liroam/cards/${encodeURIComponent(id)}/credential`, { lpa }, JSON_HEADERS);
/** shipment: { logisticsId, logisticsName, logisticsNo } */
export const saveCardShipment = (id, shipment) => postRequest(`/liroam/cards/${encodeURIComponent(id)}/shipment`, shipment, JSON_HEADERS);
/** { multiWaybillMode: MANUAL|SPLIT, trackingSource, trackingSources[] } */
export const getShippingSettings = () => getRequest("/liroam/cards/settings/shipping");
export const saveShippingSettings = (body) => putRequest("/liroam/cards/settings/shipping", body, JSON_HEADERS);
/** result: VERIFIED | REJECTED */
export const reviewCardKyc = (id, result, note) => postRequest(`/liroam/cards/${encodeURIComponent(id)}/kyc`, { result, note }, JSON_HEADERS);
export const importCredentials = (file) => upload("/liroam/cards/credentials/import", file);
/** form 不填按 eSIM 优先；实体卡库存传 PHYSICAL */
export const importPool = (file, supplierId, cardProductId, form) => upload("/liroam/cards/pool/import", file, { supplierId, cardProductId, form });

// 人工待办
export const listManualTasks = (params) => getRequest("/liroam/cards/manual-tasks", params);
export const resolveManualTask = (id, note) => postRequest(`/liroam/cards/manual-tasks/${encodeURIComponent(id)}/resolve`, { note }, JSON_HEADERS);

// 旅行卡商品
export const listCatalogCardProducts = () => getRequest("/liroam/catalog/card-products");
export const setCardProductHasNumber = (id, hasNumber) => putRequest(`/liroam/catalog/card-products/${encodeURIComponent(id)}/has-number`, { hasNumber }, JSON_HEADERS);
export const setCardProductSoft = (id, enabled) => putRequest(`/liroam/catalog/card-products/${encodeURIComponent(id)}/soft`, { enabled }, JSON_HEADERS);
export const listPlanTemplates = () => getRequest("/liroam/catalog/plan-templates");
export const createPlanTemplate = (body) => postRequest("/liroam/catalog/plan-templates", body, JSON_HEADERS);
export const listOfferings = () => getRequest("/liroam/catalog/offerings");
export const createOffering = (body) => postRequest("/liroam/catalog/offerings", body, JSON_HEADERS);
export const listCatalogSkus = () => getRequest("/liroam/catalog/skus");
export const createCatalogSku = (body) => postRequest("/liroam/catalog/skus", body, JSON_HEADERS);
export const updateSkuGates = (id, gates) => putRequest(`/liroam/catalog/skus/${encodeURIComponent(id)}/gates`, gates, JSON_HEADERS);
export const listSku = (id) => postRequest(`/liroam/catalog/skus/${encodeURIComponent(id)}/list`, {}, JSON_HEADERS);
export const delistSku = (id) => postRequest(`/liroam/catalog/skus/${encodeURIComponent(id)}/delist`, {}, JSON_HEADERS);
export const deleteSku = (id) => deleteRequest(`/liroam/catalog/skus/${encodeURIComponent(id)}`);
export const setSkuPrice = (id, priceFen) => postRequest(`/liroam/sku-bindings/${encodeURIComponent(id)}/price`, { priceFen }, JSON_HEADERS);

// 运营告警
export const listOpsAlerts = () => getRequest("/liroam/ops/alerts");
// 壳物流公司（上游设置 → 物流公司）
export const listLogistics = () => getRequest("/other/logistics/getByPage", { pageNumber: 1, pageSize: 100 });

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
/** action: confirm | reject；逐条处理，返回 { done: [id], failed: [{ id, message }] } */
export const actOnRepriceSuggestions = (ids, action) => postRequest(`/liroam/reprice-suggestions/batch-${action}`, { ids }, JSON_HEADERS);
export const getRepriceReport = () => getRequest("/liroam/reprice-report");
/** adjust: { skuBindingIds, mode: PERCENT | TARGET_MARGIN, value } */
export const previewRepriceBatch = (adjust) => postRequest("/liroam/reprice-batch/preview", adjust, JSON_HEADERS);
export const applyRepriceBatch = (adjust) => postRequest("/liroam/reprice-batch/apply", adjust, JSON_HEADERS);
export const getPricingSettings = () => getRequest("/liroam/settings/pricing");
export const savePricingSettings = (settings) => putRequest("/liroam/settings/pricing", settings, JSON_HEADERS);
export const listFxRates = (params) => getRequest("/liroam/fx/rates", params);
export const syncFxRates = () => postRequest("/liroam/fx/sync", {}, JSON_HEADERS);
export const listTariffSheets = (params) => getRequest("/liroam/tariff-sheets", params);
export const uploadTariffSheet = (file, supplierId) => upload("/liroam/tariff-sheets", file, { supplierId });
export const activateTariffSheet = (id) => postRequest(`/liroam/tariff-sheets/${encodeURIComponent(id)}/activate`, {}, JSON_HEADERS);
export const listCardProductRegions = (id) => getRequest(`/liroam/card-products/${encodeURIComponent(id)}/regions`);

// 佣金
export const listCommissionModes = () => getRequest("/liroam/commission/modes");
export const listCommissionPolicies = () => getRequest("/liroam/commission/policies");
export const saveCommissionPolicy = (policy) => postRequest("/liroam/commission/policies", policy, JSON_HEADERS);
export const saveCommissionFloorPrice = (code, floor) => postRequest(`/liroam/commission/policies/${encodeURIComponent(code)}/floor-prices`, floor, JSON_HEADERS);
export const listCommissionEntries = (params) => getRequest("/liroam/commission/entries", params);
export const listAgentBalances = () => getRequest("/liroam/commission/balances");
export const listWithdrawals = (params) => getRequest("/liroam/commission/withdrawals", params);
export const viewWithdrawalPayee = (id) => getRequest(`/liroam/commission/withdrawals/${encodeURIComponent(id)}/payee`);
export const approveWithdrawal = (id) => postRequest(`/liroam/commission/withdrawals/${encodeURIComponent(id)}/approve`, {}, JSON_HEADERS);
export const rejectWithdrawal = (id, note) => postRequest(`/liroam/commission/withdrawals/${encodeURIComponent(id)}/reject`, { note }, JSON_HEADERS);
export const markWithdrawalPaid = (id, voucher, note) => postRequest(`/liroam/commission/withdrawals/${encodeURIComponent(id)}/paid`, { voucher, note }, JSON_HEADERS);

// 报表与对账
export const getOpsOverview = (params) => getRequest("/liroam/reports/overview", params);
export const listReconRuns = (params) => getRequest("/liroam/recon/runs", params);
export const listReconDiffs = (id) => getRequest(`/liroam/recon/runs/${encodeURIComponent(id)}/diffs`);
export const runPaymentRecon = (day, csv) => postRequest("/liroam/recon/payment", { day, csv }, JSON_HEADERS);
export const runSupplierRecon = (supplierId, day, csv) => postRequest("/liroam/recon/supplier", { supplierId, day, csv }, JSON_HEADERS);
export const resolveReconDiff = (id, status, resolution) => postRequest(`/liroam/recon/diffs/${encodeURIComponent(id)}/resolve`, { status, resolution }, JSON_HEADERS);

// 渠道
export const listChannels = () => getRequest("/liroam/channels");
export const updateChannel = (id, config) => putRequest(`/liroam/channels/${encodeURIComponent(id)}`, config, JSON_HEADERS);
export const listChannelSkuMappings = (id) => getRequest(`/liroam/channels/${encodeURIComponent(id)}/sku-mappings`);
export const createChannelSkuMapping = (id, body) => postRequest(`/liroam/channels/${encodeURIComponent(id)}/sku-mappings`, body, JSON_HEADERS);
export const updateChannelSkuMapping = (mappingId, body) =>
  putRequest(`/liroam/channels/sku-mappings/${encodeURIComponent(mappingId)}`, body, JSON_HEADERS);
export const deleteChannelSkuMapping = (mappingId) => deleteRequest(`/liroam/channels/sku-mappings/${encodeURIComponent(mappingId)}`);
export const listChannelOrders = (params) => getRequest("/liroam/channels/orders", params);
export const pullChannelNow = (id) => postRequest(`/liroam/channels/${encodeURIComponent(id)}/pull`, {}, JSON_HEADERS);
export const replayChannelOrder = (orderId) => postRequest(`/liroam/channels/orders/${encodeURIComponent(orderId)}/replay`, {}, JSON_HEADERS);
export const retryChannelWriteBack = (lineId) =>
  postRequest(`/liroam/channels/order-lines/${encodeURIComponent(lineId)}/writeback-retry`, {}, JSON_HEADERS);
export const runChannelRecon = (id, day) => postRequest(`/liroam/channels/${encodeURIComponent(id)}/recon`, { day });
export const listChannelReconDiffs = (runId) => getRequest(`/liroam/channels/recon-runs/${encodeURIComponent(runId)}/diffs`);

// 模拟渠道（仅 dev/test 进程有这些接口）
export const listMockChannelOrders = () => getRequest("/liroam/dev/channel-mock/orders");
export const createMockChannelOrder = (body) => postRequest("/liroam/dev/channel-mock/orders", body, JSON_HEADERS);
export const adjustMockStatement = (orderId, paidYuan) => postRequest("/liroam/dev/channel-mock/statement/adjust", { orderId, paidYuan }, JSON_HEADERS);
export const dropMockStatement = (orderId) => postRequest("/liroam/dev/channel-mock/statement/drop", { orderId }, JSON_HEADERS);
export const failMockWriteBacks = (count, rejected) => postRequest("/liroam/dev/channel-mock/writeback-faults", { count, rejected }, JSON_HEADERS);
