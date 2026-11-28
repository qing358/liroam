import { describe, expect, it } from 'vitest'
import { detectTerminal, paymentOptions, shellClientType } from '../../app/utils/terminal'

const UA = {
  pc: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36',
  iphone: 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1',
  android: 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36',
  wechat: 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 MicroMessenger/8.0.50',
}
const ALL = ['ALIPAY', 'WECHAT', 'WALLET']
const off = { wechatJsapiEnabled: false }

describe('终端识别', () => {
  it('电脑、手机浏览器、微信内置浏览器分别识别为 PC / H5 / WECHAT；没有 UA 按 PC', () => {
    expect(detectTerminal(UA.pc)).toBe('PC')
    expect(detectTerminal(UA.iphone)).toBe('H5')
    expect(detectTerminal(UA.android)).toBe('H5')
    expect(detectTerminal(UA.wechat)).toBe('WECHAT')
    expect(detectTerminal(undefined)).toBe('PC')
  })

  it('壳 clientType：微信内按移动端 H5', () => {
    expect(shellClientType('PC')).toBe('PC')
    expect(shellClientType('H5')).toBe('H5')
    expect(shellClientType('WECHAT')).toBe('H5')
  })
})

describe('收银台支付方式', () => {
  it('PC：微信扫码、支付宝电脑网站支付跳转、余额', () => {
    expect(paymentOptions('PC', ALL, off)).toEqual([
      { method: 'WECHAT', client: 'NATIVE', action: 'qrcode' },
      { method: 'ALIPAY', client: 'PC', action: 'redirect' },
      { method: 'WALLET', client: 'NATIVE', action: 'direct' },
    ])
  })

  it('手机浏览器：支付宝手机网站支付（壳回表单）、微信 H5 跳转、余额', () => {
    expect(paymentOptions('H5', ALL, off)).toEqual([
      { method: 'ALIPAY', client: 'H5', action: 'form' },
      { method: 'WECHAT', client: 'H5', action: 'redirect' },
      { method: 'WALLET', client: 'H5', action: 'direct' },
    ])
  })

  it('微信内：不出支付宝；JSAPI 开关关闭时只剩余额，打开后出微信支付', () => {
    expect(paymentOptions('WECHAT', ALL, off).map((o) => o.method)).toEqual(['WALLET'])
    expect(paymentOptions('WECHAT', ALL, { wechatJsapiEnabled: true })).toEqual([
      { method: 'WECHAT', client: 'JSAPI', action: 'jsapi' },
      { method: 'WALLET', client: 'H5', action: 'direct' },
    ])
  })

  it('只列壳后台对该终端开启的方式', () => {
    expect(paymentOptions('PC', ['ALIPAY'], off).map((o) => o.method)).toEqual(['ALIPAY'])
    expect(paymentOptions('H5', [], off)).toEqual([])
  })
})
