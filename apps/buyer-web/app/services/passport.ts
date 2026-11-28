// 壳登录与会员接口（buyer-api /buyer/passport/**、/buyer/common/**）
import type { BuyerApi } from '~/utils/buyer-api'
import type { SessionTokens } from '~/utils/session'
import type { ShellClientType } from '~/utils/terminal'

/** 壳 VerificationEnums：新站只用登录场景（密码登录前滑块、验证码登录发短信前滑块） */
export const VERIFICATION_LOGIN = 'LOGIN'

export interface SliderChallenge {
  backImage: string
  slidingImage: string
  randomY: number
  originalWidth: number
  originalHeight: number
  sliderWidth: number
  sliderHeight: number
}

export interface Member {
  id: string
  username: string
  nickName?: string
  mobile?: string
  face?: string
}

export const sliderChallenge = (api: BuyerApi) =>
  api.get<SliderChallenge>(`/buyer/common/slider/${VERIFICATION_LOGIN}`)

export const verifySlider = (api: BuyerApi, xPos: number) =>
  api.post<boolean>(`/buyer/common/slider/${VERIFICATION_LOGIN}`, { query: { xPos: Math.round(xPos) } })

export const sendLoginSms = (api: BuyerApi, mobile: string) =>
  api.get<unknown>(`/buyer/common/sms/${VERIFICATION_LOGIN}/${encodeURIComponent(mobile)}`)

export const smsLogin = (api: BuyerApi, clientType: ShellClientType, mobile: string, code: string) =>
  api.post<SessionTokens>('/buyer/passport/member/smsLogin', { form: { mobile, code }, headers: { clientType } })

/** 壳密码登录收 md5 后的密码（与老买家端一致） */
export const passwordLogin = (api: BuyerApi, clientType: ShellClientType, username: string, passwordMd5: string) =>
  api.post<SessionTokens>('/buyer/passport/member/userLogin', { form: { username, password: passwordMd5 }, headers: { clientType } })

export const currentMember = (api: BuyerApi) =>
  api.get<Member>('/buyer/passport/member', { auth: 'required' })

export const logout = (api: BuyerApi) =>
  api.post<unknown>('/buyer/passport/member/logout', { auth: 'required' })
