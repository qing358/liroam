/** 测试用 JWT：只关心 payload 的 exp（秒） */
export function jwt(expSeconds: number): string {
  const b64 = (o: object) => btoa(JSON.stringify(o)).replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_')
  return `${b64({ alg: 'HS256' })}.${b64({ sub: 'm1', exp: expSeconds })}.sig`
}
