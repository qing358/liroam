// 只读的假 buyer-api：按 K3 契约回目的地与套餐（夹具 test/fixtures/k3.ts），按壳文章接口回帮助中心（test/fixtures/help.ts），供服务端渲染测试用
import { createServer, type IncomingHttpHeaders, type Server } from 'node:http'
import { destinations, japanPlans, usageRegion } from '../fixtures/k3'
import { articles, categoryTree, summaries } from '../fixtures/help'

const ok = (result: unknown) => ({ success: true, message: '成功', code: 200, timestamp: Date.now(), result })
const fail = (message: string, code = 400) => ({ success: false, message, code, timestamp: Date.now(), result: null })

/** 关键词命中国家时，连带覆盖它的多国与全球（真实接口按成员匹配） */
function search(q: string) {
  const needle = q.toLowerCase()
  const countries = destinations.filter((d) => d.kind === 'COUNTRY'
    && [d.code, d.nameZh, d.nameEn ?? '', ...d.aliases].some((t) => t.toLowerCase().includes(needle)))
  return countries.length ? [...countries, ...destinations.filter((d) => d.kind !== 'COUNTRY')] : []
}

/** 壳 /buyer/other/article 分页：只列已发布，按分类与标题模糊过滤（MyBatis-Plus IPage 字段） */
function articlePage(params: URLSearchParams) {
  const size = Number(params.get('pageSize') ?? 10)
  const current = Number(params.get('pageNumber') ?? 1)
  const all = summaries(params.get('categoryId') ?? undefined, params.get('title') ?? undefined)
  return { records: all.slice((current - 1) * size, current * size), total: all.length, size, current, pages: Math.ceil(all.length / size) }
}

export function startFakeBuyerApi(port: number): Promise<{ server: Server, requests: { url: string, headers: IncomingHttpHeaders }[] }> {
  const requests: { url: string, headers: IncomingHttpHeaders }[] = []
  const server = createServer((req, res) => {
    const url = new URL(req.url ?? '/', 'http://localhost')
    requests.push({ url: url.pathname + url.search, headers: req.headers })
    let body: unknown = fail('请求的资源不存在')
    const plans = url.pathname.match(/^\/buyer\/liroam\/destinations\/([^/]+)\/plans$/)
    const article = url.pathname.match(/^\/buyer\/other\/article\/get\/([^/]+)$/)
    if (plans?.[1]?.toUpperCase() === 'DOWN') {
      res.writeHead(502, { 'content-type': 'text/html' })
      res.end('<html>Bad Gateway</html>')
      return
    }
    if (url.pathname === '/buyer/liroam/destinations') {
      const q = url.searchParams.get('q')
      body = ok(q ? search(q) : destinations)
    }
    else if (plans) {
      const code = plans[1]!.toUpperCase()
      // OFF：K1 关掉的功能，HTTP 200 + success:false + 91001（ApiResult.FEATURE_DISABLED）
      body = code === 'JP' ? ok(japanPlans) : code === 'OFF' ? fail('该功能暂未开放', 91001) : fail('这个目的地暂时没有在售套餐')
    }
    else if (url.pathname === '/buyer/goods/liroam/usage-region') {
      body = ok(usageRegion)
    }
    else if (url.pathname === '/buyer/other/article/articleCategory/list') {
      body = ok(categoryTree)
    }
    else if (url.pathname === '/buyer/other/article') {
      body = ok(articlePage(url.searchParams))
    }
    else if (article) {
      // 壳按 id 直接取，不看发布状态；查不到时 result 为 null
      body = ok(articles.find((a) => a.id === article[1]) ?? null)
    }
    res.writeHead(200, { 'content-type': 'application/json' })
    res.end(JSON.stringify(body))
  })
  return new Promise((resolve, reject) => {
    server.once('error', reject)
    server.listen(port, '127.0.0.1', () => resolve({ server, requests }))
  })
}
