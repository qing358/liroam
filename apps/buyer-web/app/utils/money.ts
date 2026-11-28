/** 壳交易与收银台金额是元（Double）；自研接口是分。结算、订单显示人民币两位小数 */
export function formatYuan(yuan: number | null | undefined): string {
  return `¥${(Number(yuan) || 0).toFixed(2)}`
}

export function formatFen(fen: number | null | undefined): string {
  return formatYuan((Number(fen) || 0) / 100)
}

/** 选卡页价格（分）：整元写「¥39」，有角分保留两位 */
export function formatPrice(fen: number): string {
  return fen % 100 === 0 ? `¥${fen / 100}` : formatFen(fen)
}
