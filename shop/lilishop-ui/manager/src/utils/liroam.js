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

/** 表单里填的百分数 → 传输用小数比率（5 → 0.05）。 */
export function percentToRate(percent) {
  if (percent === null || percent === undefined || percent === "") return null;
  const n = Number(percent);
  if (!Number.isFinite(n)) return null;
  return Number((n / 100).toFixed(6));
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

export const REPRICE_ADJUST_MODES = [
  { value: "PERCENT", label: "按涨跌幅" },
  { value: "TARGET_MARGIN", label: "按目标毛利率" },
];

/** 批量确认 / 驳回结果（BatchDecision）→ 一句提示；失败逐条带后端原因。 */
export function describeBatchDecision(result, verb) {
  const done = (result && result.done) || [];
  const failed = (result && result.failed) || [];
  const head = `已${verb} ${done.length} 条`;
  if (!failed.length) return head;
  return `${head}，${failed.length} 条未处理：` + failed.map((f) => `#${f.id} ${f.message}`).join("；");
}

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

/** 佣金策略作用域、结算锚点（CommissionPolicy）。 */
export const COMMISSION_SCOPES = [
  { value: "GLOBAL", label: "全局" },
  { value: "AGENT_GRADE", label: "按代理等级" },
  { value: "SKU", label: "按 SKU" },
];

export const SETTLE_ANCHORS = [
  { value: "DELIVERED", label: "交码起算" },
  { value: "PAID", label: "支付起算" },
];

export const WITHDRAWAL_STATUSES = [
  { value: "APPLIED", label: "待审核" },
  { value: "APPROVED", label: "待打款" },
  { value: "PAID", label: "已打款" },
  { value: "REJECTED", label: "已驳回" },
];

export const WITHDRAW_METHODS = [
  { value: "ALIPAY", label: "支付宝" },
  { value: "WECHAT", label: "微信" },
  { value: "BANK", label: "银行卡" },
];

export const RECON_KINDS = [
  { value: "PAYMENT_DAILY", label: "支付日对账" },
  { value: "SUPPLIER_DAILY", label: "供应商日对账" },
  { value: "CHANNEL_DAILY", label: "渠道日对账" },
];

export const RECON_DIFF_KINDS = [
  { value: "AMOUNT", label: "金额不符" },
  { value: "MISSING_THEIRS", label: "对方缺失" },
  { value: "MISSING_OURS", label: "我方缺失" },
  { value: "STATUS", label: "未履约完成" },
];

/** 策略一行摘要：「实付抽成 10%｜一级 80% / 二级 20%｜T+1（交码起算）｜提现需审批」。 */
export function policySummary(policy, modes) {
  if (!policy) return "";
  const mode = (modes || []).find((m) => m.mode === policy.mode);
  const title = mode ? mode.title : policy.mode;
  if (policy.mode === "NONE") return title;
  const head = policy.mode === "PAID_RATE" && policy.poolRate !== null && policy.poolRate !== undefined ? `${title} ${rateToPercent(policy.poolRate)}` : title;
  return [
    head,
    `一级 ${rateToPercent(policy.directRate || 0)} / 二级 ${rateToPercent(policy.inviteRate || 0)}`,
    `T+${policy.settleDays || 0}（${labelOf(SETTLE_ANCHORS, policy.settleAnchor)}）`,
    policy.withdrawApproval === false ? "提现免审批" : "提现需审批",
  ].join("｜");
}

/** 只决定按钮显隐；能否操作以后端为准（Withdrawals）。 */
export function withdrawalActions(w) {
  return {
    approve: w.status === "APPLIED",
    reject: w.status === "APPLIED" || w.status === "APPROVED",
    paid: w.status === "APPROVED",
  };
}

export function reconScopeText(run) {
  if (!run.scopeRef) return "—";
  return `${run.kind === "CHANNEL_DAILY" ? "渠道" : "供应商"} ${run.scopeRef}`;
}

export function reconDiffText(d) {
  const money = (minor) => `${fenToYuan(minor)} ${d.currency}`;
  if (d.diffKind === "AMOUNT") return `${d.ourRef}：我方 ${money(d.ourAmountMinor)}，对方 ${money(d.theirAmountMinor)}`;
  if (d.diffKind === "MISSING_THEIRS") return `${d.ourRef}：对方账单没有（我方 ${money(d.ourAmountMinor)}）`;
  if (d.diffKind === "MISSING_OURS") return `${d.theirRef}：我方没有（对方 ${money(d.theirAmountMinor)}）`;
  if (d.diffKind === "STATUS") return `${d.ourRef}：对方已结算，我方未履约完成（${money(d.ourAmountMinor)}）`;
  return `${d.ourRef || d.theirRef || ""}：${d.diffKind}`;
}

/** 渠道订单行内可做的操作：整单待人工可重放；回传转人工的行可重试回传。 */
export function channelOrderActions(order) {
  const lines = (order && Array.isArray(order.lines)) ? order.lines : [];
  return {
    replay: Boolean(order) && order.status === "MANUAL",
    retryLineIds: lines.filter((l) => l.writebackStatus === "MANUAL").map((l) => l.id),
  };
}

export function reconTagType(reconStatus) {
  if (reconStatus === "BALANCED") return "success";
  if (reconStatus === "DIFF") return "danger";
  return "info";
}

export function writebackTagType(status) {
  if (status === "DONE") return "success";
  if (status === "MANUAL") return "danger";
  if (status === "FAILED") return "warning";
  return "info";
}

/** 「渠道订单」模拟面板：所选渠道若装配的是模拟实现（dev/test 才有），返回 { adapterKey, name, code }，否则 null。 */
export function simulatedChannelOf(channels, simulated, channelId) {
  if (!channelId || !Array.isArray(channels) || !Array.isArray(simulated)) return null;
  const channel = channels.find((c) => c.id === channelId);
  const sim = channel && simulated.find((s) => s.adapterKey === channel.adapterKey);
  return sim ? { adapterKey: sim.adapterKey, name: sim.name, code: channel.code } : null;
}

export function bytesText(bytes) {
  if (bytes === null || bytes === undefined) return "-";
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} GB`;
}

/** 与后端 DB_CAPACITY 告警同口径：≥80% 注意、≥100% 严重；容量 ≤ 0 视为不检查。 */
export function capacityUsage(used, limit) {
  if (!limit || limit <= 0) return { percent: 0, status: "success" };
  const status = used >= limit ? "exception" : used * 5 >= limit * 4 ? "warning" : "success";
  return { percent: Math.min(100, Math.round((used * 100) / limit)), status };
}
