/** 旅行卡页面共用的纯函数（无依赖，tests/liroam-utils.test.cjs 直接加载源码）。 */

export function fenToYuan(fen) {
  if (fen === null || fen === undefined || fen === "") return "";
  const n = Number(fen);
  if (!Number.isFinite(n)) return "";
  const sign = n < 0 ? "-" : "";
  const abs = Math.round(Math.abs(n));
  return sign + Math.floor(abs / 100) + "." + String(abs % 100).padStart(2, "0");
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

/** 激活码结果容错：字符串当 LPA 原文；对象字段优先，缺的从 lpa 拆。无内容返回 null。 */
export function normalizeCredential(result) {
  const raw = typeof result === "string" ? { lpa: result } : result && typeof result === "object" ? result : {};
  const lpa = String(raw.lpa || raw.lpaString || raw.qrCode || "").trim();
  const parsed = lpa ? parseLpa(lpa) : {};
  const credential = {
    lpa,
    smdpAddress: String(raw.smdpAddress || parsed.smdpAddress || ""),
    activationCode: String(raw.activationCode || raw.matchingId || parsed.activationCode || ""),
    confirmationCode: String(raw.confirmationCode || parsed.confirmationCode || ""),
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
