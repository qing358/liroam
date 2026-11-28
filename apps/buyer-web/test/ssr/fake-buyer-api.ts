// 只读的假 buyer-api：按 K3 契约回目的地与套餐（夹具 test/fixtures/k3.ts），供服务端渲染测试用
import { createServer, type IncomingHttpHeaders, type Server } from 'node:http'
import { destinations, japanPlans, usageRegion } from '../fixtures/k3'

const ok = (result: unknown) => ({ success: true, message: '成功', code: 200, timestamp: Date.now(), result })
const fail = (message: string) => ({ success: false, message, code: 400, timestamp: Date.now(), result: null })

/** 关键词命中国家时，连带覆盖它的多国与全球（真实接口按成员匹配） */
function search(q: string) {
  const needle = q.toLowerCase()
  const countries = destinations.filter((d) => d.kind === 'COUNTRY'
    && [d.code, d.nameZh, d.nameEn ?? '', ...d.aliases].some((t) => t.toLowerCase().includes(needle)))
  return countries.length ? [...countries, ...destinations.filter((d) => d.kind !== 'COUNTRY')] : []
}

export function startFakeBuyerApi(port: number): Promise<{ server: Server, requests: { url: string, headers: IncomingHttpHeaders }[] }> {
  const requests: { url: string, headers: IncomingHttpHeaders }[] = []
  const server = createServer((req, res) => {
    const url = new URL(req.url ?? '/', 'http://localhost')
    requests.push({ url: url.pathname + url.search, headers: req.headers })
    let body: unknown = fail('请求的资源不存在')
    const plans = url.pathname.match(/^\/buyer\/liroam\/destinations\/([^/]+)\/plans$/)
    if (url.pathname === '/buyer/liroam/destinations') {
      const q = url.searchParams.get('q')
      body = ok(q ? search(q) : destinations)
    }
    else if (plans) {
      body = plans[1]!.toUpperCase() === 'JP' ? ok(japanPlans) : fail('这个目的地暂时没有在售套餐')
    }
    else if (url.pathname === '/buyer/goods/liroam/usage-region') {
      body = ok(usageRegion)
    }
    res.writeHead(200, { 'content-type': 'application/json' })
    res.end(JSON.stringify(body))
  })
  return new Promise((resolve, reject) => {
    server.once('error', reject)
    server.listen(port, '127.0.0.1', () => resolve({ server, requests }))
  })
}
