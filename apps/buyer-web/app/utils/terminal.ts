// 终端类型与支付方式的唯一定义处（K7）：买家所在终端决定登录 / 收银台的 clientType 与每种支付方式的调起方式。

export type Terminal = 'PC' | 'H5' | 'WECHAT'

/** 壳 `ClientTypeEnum`：微信内置浏览器也按移动端（H5）计 */
export type ShellClientType = 'PC' | 'H5'

/** 壳 `PaymentMethodEnum` 中新站会用到的三种 */
export type PaymentMethod = 'ALIPAY' | 'WECHAT' | 'WALLET'

/** 壳 `PaymentClientEnum`（`PC` 为本仓新增的电脑网站支付，见 docs/upstream.md） */
export type PaymentClient = 'PC' | 'NATIVE' | 'H5' | 'JSAPI'

/**
 * 收银台拿到结果后怎么做：
 * redirect 结果是渠道收银台地址，整页跳转；qrcode 结果是扫码串；form 壳直接回渠道表单 HTML（支付宝手机网站支付）；
 * jsapi 结果是公众号内调起参数；direct 余额支付，结果即已付款。
 */
export type PaymentAction = 'redirect' | 'qrcode' | 'form' | 'jsapi' | 'direct'

export interface PaymentOption {
  method: PaymentMethod
  client: PaymentClient
  action: PaymentAction
}

export interface PaymentFlags {
  /** 微信内 JSAPI 支付需要公众号网页授权拿 openid，材料到位前关闭 */
  wechatJsapiEnabled: boolean
}

export function detectTerminal(userAgent: string | undefined): Terminal {
  const ua = userAgent ?? ''
  if (/MicroMessenger/i.test(ua)) return 'WECHAT'
  if (/Android|iPhone|iPad|iPod|Mobile|HarmonyOS/i.test(ua)) return 'H5'
  return 'PC'
}

export function shellClientType(terminal: Terminal): ShellClientType {
  return terminal === 'PC' ? 'PC' : 'H5'
}

const OPTIONS: Record<Terminal, PaymentOption[]> = {
  PC: [
    { method: 'WECHAT', client: 'NATIVE', action: 'qrcode' },
    { method: 'ALIPAY', client: 'PC', action: 'redirect' },
    { method: 'WALLET', client: 'NATIVE', action: 'direct' },
  ],
  H5: [
    { method: 'ALIPAY', client: 'H5', action: 'form' },
    { method: 'WECHAT', client: 'H5', action: 'redirect' },
    { method: 'WALLET', client: 'H5', action: 'direct' },
  ],
  // 微信内打不开支付宝；微信 H5 支付在微信内不可用，只能走 JSAPI
  WECHAT: [
    { method: 'WECHAT', client: 'JSAPI', action: 'jsapi' },
    { method: 'WALLET', client: 'H5', action: 'direct' },
  ],
}

/** 确认订单页（交易还没建、拿不到壳 support）按终端列出的支付方式 */
export function paymentChoices(terminal: Terminal, flags: PaymentFlags): PaymentOption[] {
  return OPTIONS[terminal].filter((o) => o.client !== 'JSAPI' || flags.wechatJsapiEnabled)
}

/** 收银台可用支付方式：终端支持且壳后台对该终端开启（tradeDetail.support） */
export function paymentOptions(terminal: Terminal, support: readonly string[], flags: PaymentFlags): PaymentOption[] {
  return paymentChoices(terminal, flags).filter((o) => support.includes(o.method))
}

/** 确认订单页预选的方式在收银台可用时直接调起，否则让买家重选 */
export function preferredOption(options: readonly PaymentOption[], method: string | undefined): PaymentOption | null {
  return options.find((o) => o.method === method) ?? null
}

export function paymentHint(terminal: Terminal, method: PaymentMethod): string | undefined {
  if (method !== 'WECHAT') return undefined
  return terminal === 'PC' ? '扫码支付' : '在微信内打开可直接支付'
}

export const PAYMENT_METHOD_LABEL: Record<PaymentMethod, string> = {
  WECHAT: '微信支付',
  ALIPAY: '支付宝',
  WALLET: '账户余额',
}
