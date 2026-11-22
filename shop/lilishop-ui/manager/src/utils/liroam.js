/** 旅行卡后台页面共用的纯函数（无依赖，tests/liroam-utils.test.cjs 直接加载源码）。 */

export function fenToYuan(fen) {
  if (fen === null || fen === undefined || fen === "") return "";
  const n = Number(fen);
  if (!Number.isFinite(n)) return "";
  const sign = n < 0 ? "-" : "";
  const abs = Math.round(Math.abs(n));
  return sign + Math.floor(abs / 100) + "." + String(abs % 100).padStart(2, "0");
}

export function yuanToFen(yuan) {
  if (yuan === null || yuan === undefined || yuan === "") return null;
  const n = Number(yuan);
  if (!Number.isFinite(n)) return null;
  return Math.round(Number((n * 100).toFixed(6)));
}

/** 比率以小数传输（0.05 = 5%）。 */
export function rateToPercent(rate) {
  if (rate === null || rate === undefined || rate === "") return "";
  const n = Number(rate);
  if (!Number.isFinite(n)) return "";
  return Number((n * 100).toFixed(2)) + "%";
}

export function listOf(result) {
  if (Array.isArray(result)) return result;
  if (result && Array.isArray(result.records)) return result.records;
  return [];
}

export function pageOf(result) {
  const records = listOf(result);
  const total = result && !Array.isArray(result) && Number.isFinite(Number(result.total)) ? Number(result.total) : records.length;
  return { records, total };
}

/** 卡资产状态（domain-design §7.3）；展示优先用后端 statusText。 */
export const CARD_STATUSES = [
  { value: "CREATED", label: "已建" },
  { value: "ALLOCATED", label: "已分配卡号" },
  { value: "PROVISIONED", label: "已订购" },
  { value: "DELIVERED", label: "已交码" },
  { value: "IN_USE", label: "使用中" },
  { value: "SUSPENDED", label: "已停用" },
  { value: "EXPIRED", label: "已到期" },
  { value: "CANCELLED", label: "已作废" },
  { value: "TERMINATED", label: "已终止" },
  { value: "EXCEPTION", label: "人工处理中" },
];

export const REPRICE_STATUSES = [
  { value: "PENDING", label: "待确认" },
  { value: "CONFIRMED", label: "已确认" },
  { value: "REJECTED", label: "已驳回" },
  { value: "SUPERSEDED", label: "已失效" },
];

export const REPRICE_TRIGGERS = [
  { value: "FX", label: "汇率变动" },
  { value: "TARIFF", label: "资费表变动" },
];

export const TARIFF_SHEET_STATUSES = [
  { value: "DRAFT", label: "待启用" },
  { value: "ACTIVE", label: "生效中" },
  { value: "SUPERSEDED", label: "已替换" },
];

export const FX_RATE_STATUSES = [
  { value: "ACTIVE", label: "当前" },
  { value: "SUPERSEDED", label: "历史" },
];

const TARIFF_LINE_KINDS = [
  { value: "DATA", label: "流量" },
  { value: "VOICE", label: "语音" },
  { value: "SMS", label: "短信" },
  { value: "CARD_FEE", label: "卡费" },
];

/** options 为 [{value, label}]；未知值原样显示。 */
export function labelOf(options, value) {
  if (value === null || value === undefined || value === "") return "";
  const hit = options.find((o) => o.value === value);
  return hit ? hit.label : String(value);
}

/** 资费表导入 / 启用结果（ImportReport）→ 逐行摘要。 */
export function describeTariffImport(report) {
  if (!report || typeof report !== "object") return ["导入完成"];
  const lines = [`资费表 #${report.sheetId}：${report.fileName}（${labelOf(TARIFF_SHEET_STATUSES, report.status)}）`];
  if (report.effectiveFromText) lines.push(`生效日期：${report.effectiveFromText}`);
  const kinds = Object.entries(report.linesByKind || {}).map(([kind, count]) => `${labelOf(TARIFF_LINE_KINDS, kind)} ${count}`);
  lines.push(`共 ${report.lineCount} 行` + (kinds.length ? `：${kinds.join("、")}` : ""));
  (report.resources || []).forEach((r) => lines.push(`资源 ${r.resourceCode}：匹配 ${r.regionCount} 个国家/地区`));
  const unmatched = report.unmatchedCountries || [];
  if (unmatched.length) lines.push(`未匹配国家/地区 ${unmatched.length} 个（已原样入库，不进可用区域）：${unmatched.join("、")}`);
  return lines;
}

/** 汇率同步接口总是 success，是否成功看 result.ok。 */
export function fxSyncOutcome(result) {
  if (result && result.ok) return { ok: true, message: `已同步：1 HKD = ${result.rate} CNY（来源 ${result.sourceKey}）` };
  const source = result && result.sourceKey ? `（来源 ${result.sourceKey}）` : "";
  return { ok: false, message: `同步失败${source}：${(result && result.error) || "原因未知"}` };
}

const FORM_LABELS = { ESIM: "eSIM", PHYSICAL: "实体卡" };

export function cardFormText(card) {
  if (!card) return "";
  return card.formText || FORM_LABELS[String(card.form || "").toUpperCase()] || card.form || "";
}

/** 后端字段可能增减或改名：按顺序取第一个非空值。 */
export function pick(row, ...keys) {
  if (!row) return "";
  for (const key of keys) {
    const value = row[key];
    if (value !== null && value !== undefined && value !== "") return value;
  }
  return "";
}

export const KYC_GATE_MODES = [
  { value: "NONE", label: "不要求" },
  { value: "PROMPT", label: "提示实名" },
  { value: "BLOCK_DELIVERY", label: "实名后交付" },
];

export const LISTING_STATES = [
  { value: "DRAFT", label: "草稿" },
  { value: "LISTED", label: "已上架" },
  { value: "DELISTED", label: "已下架" },
];

export const SKU_KINDS = [
  { value: "NEW_CARD", label: "新卡" },
  { value: "TOP_UP", label: "加油包" },
];

export const CARD_FORMS = [
  { value: "ESIM", label: "eSIM" },
  { value: "PHYSICAL", label: "实体卡" },
  { value: "SOFT", label: "软卡" },
];

/** 套餐模板额度（PlanTemplateRow）→「3 GB · 100 分钟 · 50 条 · 30 天」。 */
export function planQuotaText(tpl) {
  if (!tpl) return "";
  const parts = [];
  if (tpl.dataQuotaBytes) parts.push(`${Number((tpl.dataQuotaBytes / 2 ** 30).toFixed(2))} GB`);
  if (tpl.voiceSeconds) parts.push(`${Math.floor(tpl.voiceSeconds / 60)} 分钟`);
  if (tpl.smsCount) parts.push(`${tpl.smsCount} 条`);
  if (tpl.validityDays) parts.push(`${tpl.validityDays} 天`);
  return parts.join(" · ");
}

/** 只决定按钮显隐；是否允许以后端为准（CatalogAdmin）。 */
export function skuActions(sku) {
  const priced = sku.priceFen !== null && sku.priceFen !== undefined;
  return {
    list: sku.listingState !== "LISTED" && priced,
    delist: sku.listingState === "LISTED",
    remove: sku.listingState === "DRAFT" && !sku.locked,
    needPrice: !priced,
  };
}

// 与后端 CredentialEntry 可录入状态一致（交付后可更正）
const CREDENTIAL_STATUSES = ["ALLOCATED", "PROVISIONED", "DELIVERED", "IN_USE", "SUSPENDED", "EXCEPTION"];
const KYC_REVIEWABLE = ["PENDING", "USER_DECLARED", "REJECTED"];
const VOID_STATUSES = ["CANCELLED", "TERMINATED"];

export function cardActions(card) {
  const enterable = CREDENTIAL_STATUSES.includes(card.status);
  const physical = card.form === "PHYSICAL";
  return {
    credential: enterable && !physical,
    shipment: enterable && physical,
    kycReview: KYC_REVIEWABLE.includes(card.kycStatus) && !VOID_STATUSES.includes(card.status),
  };
}

export function alertLevelTag(level) {
  if (level === "CRITICAL") return "danger";
  if (level === "WARN") return "warning";
  return "info";
}
