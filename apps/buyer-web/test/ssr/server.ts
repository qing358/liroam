// 服务端渲染测试共用：起假 buyer-api 与构建产物 .output（先 npm run build），端口取 deploy/ports.env 的 worktree 临时段 39100-39199
import { spawn, type ChildProcess } from 'node:child_process'
import { existsSync } from 'node:fs'
import { createServer, type Server } from 'node:net'
import { fileURLToPath } from 'node:url'
import { startFakeBuyerApi } from './fake-buyer-api'

const root = fileURLToPath(new URL('../..', import.meta.url))
const entry = `${root}.output/server/index.mjs`

/** 站点对外地址（NUXT_SITE_URL）：canonical、分享卡片、站点地图与 robots 都从它拼绝对地址 */
export const SITE_URL = 'https://www.liroam.example'

async function freePort(from = 39100, to = 39199): Promise<number> {
  for (let port = from + Math.floor(Math.random() * 50); port <= to; port++) {
    const free = await new Promise<boolean>((resolve) => {
      const probe: Server = createServer().once('error', () => resolve(false)).listen(port, '127.0.0.1', () => probe.close(() => resolve(true)))
    })
    if (free) return port
  }
  throw new Error('39100-39199 没有空闲端口')
}

/** 页面源码里指定 @type 的 JSON-LD */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
export function jsonLd(html: string, type: string): any {
  const blocks = [...html.matchAll(/<script[^>]*type="application\/ld\+json"[^>]*>([\s\S]*?)<\/script>/g)].map((m) => JSON.parse(m[1]!))
  const found = blocks.find((b) => b['@type'] === type)
  if (!found) throw new Error(`源码里没有 ${type} 结构化数据（共 ${blocks.length} 段）`)
  return found
}

/** 页面源码里的 <meta>（name 或 property）/ <link rel> 取值，不依赖属性顺序 */
export function headValue(html: string, tag: 'meta' | 'link', key: string): string | undefined {
  for (const m of html.matchAll(new RegExp(`<${tag}\\s[^>]*>`, 'g'))) {
    const attrs = Object.fromEntries([...m[0].matchAll(/([\w:-]+)="([^"]*)"/g)].map((a) => [a[1], a[2]]))
    if (tag === 'meta' && (attrs.name === key || attrs.property === key)) return attrs.content
    if (tag === 'link' && attrs.rel === key) return attrs.href
  }
  return undefined
}

export interface SsrServer {
  base: string
  api: Awaited<ReturnType<typeof startFakeBuyerApi>>
  html(path: string): Promise<{ status: number, text: string, type: string }>
  stop(): void
}

export async function startSsrServer(): Promise<SsrServer> {
  if (!existsSync(entry)) throw new Error('没有构建产物，先 npm run build')
  const apiPort = await freePort()
  const api = await startFakeBuyerApi(apiPort)
  const port = await freePort(apiPort + 1)
  const base = `http://127.0.0.1:${port}`
  const nuxt: ChildProcess = spawn(process.execPath, [entry], {
    env: {
      ...process.env,
      PORT: String(port),
      HOST: '127.0.0.1',
      NUXT_PUBLIC_BUYER_API_URL: `http://127.0.0.1:${apiPort}`,
      NUXT_SITE_URL: SITE_URL,
      // 客服方式为运营配置：测试只配在线时间与电话，在线客服链接留空
      NUXT_PUBLIC_SUPPORT_HOURS: '每天 9:00–22:00（北京时间）',
      NUXT_PUBLIC_SUPPORT_PHONE: '4000000000',
    },
    stdio: 'ignore',
  })
  const stop = () => {
    nuxt.kill()
    api.server.close()
  }
  for (let i = 0; i < 100; i++) {
    if (await fetch(base).then(() => true, () => false)) {
      return {
        base,
        api,
        stop,
        html: async (path) => {
          const res = await fetch(base + path, { redirect: 'manual', headers: { accept: 'text/html' } })
          return { status: res.status, text: await res.text(), type: res.headers.get('content-type') ?? '' }
        },
      }
    }
    await new Promise((r) => setTimeout(r, 200))
  }
  stop()
  throw new Error('Nuxt 服务没有起来')
}
