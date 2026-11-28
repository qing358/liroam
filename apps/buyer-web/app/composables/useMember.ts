import { currentMember, logout as shellLogout, type Member } from '~/services/passport'
import type { SessionTokens } from '~/utils/session'

/** 当前登录买家；null 为未登录，undefined 为尚未读取 */
export function useMember() {
  const { $buyerApi, $session } = useNuxtApp()
  const member = useState<Member | null | undefined>('member', () => undefined)

  async function load(): Promise<Member | null> {
    if (!$session.tokens()) {
      member.value = null
      return null
    }
    member.value = await currentMember($buyerApi).catch(() => null)
    return member.value
  }

  async function signIn(tokens: SessionTokens) {
    $session.save(tokens)
    await load()
  }

  async function signOut() {
    await shellLogout($buyerApi).catch(() => undefined)
    $session.clear()
    member.value = null
  }

  return { member, load, signIn, signOut, signedIn: () => Boolean($session.tokens()) }
}
