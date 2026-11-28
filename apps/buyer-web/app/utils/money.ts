/** 壳交易与收银台金额是元（Double）；自研接口是分。前台统一显示人民币两位小数 */
export function formatYuan(yuan: number | null | undefined): string {
  return `¥${(Number(yuan) || 0).toFixed(2)}`
}

export function formatFen(fen: number | null | undefined): string {
  return formatYuan((Number(fen) || 0) / 100)
}
