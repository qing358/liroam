/** 旅行卡页面共用的纯函数（无依赖，tests/liroam-utils.test.cjs 直接加载源码）。 */

export function fenToYuan(fen) {
  if (fen === null || fen === undefined || fen === "") return "";
  const n = Number(fen);
  if (!Number.isFinite(n)) return "";
  const sign = n < 0 ? "-" : "";
  const abs = Math.round(Math.abs(n));
  return sign + Math.floor(abs / 100) + "." + String(abs % 100).padStart(2, "0");
}

/**
 * 加购 cartType：旅行卡一律走普通订单（BUY_NOW / 购物车），付款后进待发货，交码后由后端发货；
 * 壳的核销型虚拟订单（VIRTUAL）付款后进「待核销」，后端会拒绝旅行卡走这条路。
 */
export function checkoutCartType({ goodsType, travelCard } = {}) {
  return goodsType === "VIRTUAL_GOODS" && !travelCard ? "VIRTUAL" : "BUY_NOW";
}

/** 接口可能直接给数组，也可能给分页 `{records}`。 */
export function listOf(result) {
  if (Array.isArray(result)) return result;
  if (result && Array.isArray(result.records)) return result.records;
  return [];
}

function parseLpa(text) {
  const parts = String(text).split("$");
  if (parts.length < 3 || !/^LPA:/i.test(parts[0])) return {};
  return { smdpAddress: parts[1] || "", activationCode: parts[2] || "", confirmationCode: parts[4] || "" };
}

/** 激活码：接口 CredentialView `{kind, payload}`，kind 为 LPA / QR（完整激活信息）或 ACTIVATION_CODE（裸激活码）；字符串当 LPA 原文。无内容返回 null。 */
export function normalizeCredential(result) {
  const raw = typeof result === "string" ? { kind: "LPA", payload: result } : result && typeof result === "object" ? result : {};
  const payload = String(raw.payload || "").trim();
  const bareCode = raw.kind === "ACTIVATION_CODE";
  const lpa = bareCode ? "" : payload;
  const parsed = lpa ? parseLpa(lpa) : {};
  const credential = {
    lpa,
    smdpAddress: parsed.smdpAddress || "",
    activationCode: bareCode ? payload : parsed.activationCode || "",
    confirmationCode: parsed.confirmationCode || "",
  };
  return credential.lpa || credential.activationCode ? credential : null;
}

const FORM_LABELS = { ESIM: "eSIM", PHYSICAL: "实体卡" };

/** 后端给 formText 就用它；否则按已知形态翻译，未知原样显示。 */
export function cardFormText(card) {
  if (!card) return "";
  return card.formText || FORM_LABELS[String(card.form || "").toUpperCase()] || card.form || "";
}

export function failureMessage(res, fallback) {
  return (res && res.message) || fallback;
}

/** 实体卡的交付内容是物流（CredentialView kind = SHIPMENT）；不是则 null。 */
export function shipmentOf(result) {
  if (!result || result.kind !== "SHIPMENT") return null;
  return { company: result.logisticsCompany || "", no: result.logisticsNo || "" };
}

export function credentialLabel(card) {
  return card && card.form === "PHYSICAL" ? "查看物流" : "查看激活码";
}

/** 卡详情实名提示；action 表示显示「我已完成实名」。 */
export function kycNotice(card) {
  if (!card || !card.canDeclareKyc) return null;
  if (card.kycStatus === "REJECTED") return { type: "error", text: "实名未通过，请按要求重新完成实名后再次提交。", action: true };
  if (card.kycBlocking) return { type: "warning", text: "该卡使用地区要求实名登记，完成实名并提交后才会发放。", action: true };
  return { type: "info", text: "该卡使用地区建议实名登记，完成后可在此提交。", action: true };
}

/** 语音 / 短信字段为 null 表示产品无此能力，不展示。 */
export function quotaRows(card) {
  const rows = [{ label: "流量剩余", value: (card && card.remainingText) || "—" }];
  if (card && card.voiceRemainingText) rows.push({ label: "语音剩余", value: card.voiceRemainingText });
  if (card && card.smsRemainingText) rows.push({ label: "短信剩余", value: card.smsRemainingText });
  return rows;
}
