// 服务端渲染：首页、全部目的地、目的地页、套餐详情的内容直接出现在 HTML 源码里（关 JS 可见）。
// 跑构建产物 .output（先 npm run build），K3 接口用假 buyer-api；端口取 deploy/ports.env 的 worktree 临时段 39100-39199
import { spawn, type ChildProcess } from 'node:child_process'
import { existsSync } from 'node:fs'
import { createServer, type Server } from 'node:net'
import { fileURLToPath } from 'node:url'
import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import { startFakeBuyerApi } from './fake-buyer-api'

const root = fileURLToPath(new URL('../..', import.meta.url))
const entry = `${root}.output/server/index.mjs`

async function freePort(from = 39100, to = 39199): Promise<number> {
  for (let port = from + Math.floor(Math.random() * 50); port <= to; port++) {
    const free = await new Promise<boolean>((resolve) => {
      const probe: Server = createServer().once('error', () => resolve(false)).listen(port, '127.0.0.1', () => probe.close(() => resolve(true)))
    })
    if (free) return port
  }
  throw new Error('39100-39199 没有空闲端口')
}

let nuxt: ChildProcess
let base = ''
let api: Awaited<ReturnType<typeof startFakeBuyerApi>>

beforeAll(async () => {
  if (!existsSync(entry)) throw new Error('没有构建产物，先 npm run build')
  const apiPort = await freePort()
  api = await startFakeBuyerApi(apiPort)
  const port = await freePort(apiPort + 1)
  base = `http://127.0.0.1:${port}`
  nuxt = spawn(process.execPath, [entry], {
    env: { ...process.env, PORT: String(port), HOST: '127.0.0.1', NUXT_PUBLIC_BUYER_API_URL: `http://127.0.0.1:${apiPort}` },
    stdio: 'ignore',
  })
  for (let i = 0; i < 100; i++) {
    if (await fetch(base).then(() => true, () => false)) return
    await new Promise((r) => setTimeout(r, 200))
  }
  throw new Error('Nuxt 服务没有起来')
}, 30_000)

afterAll(() => {
  nuxt?.kill()
  api?.server.close()
})

const html = async (path: string) => {
  const res = await fetch(base + path)
  return { status: res.status, text: await res.text() }
}

describe('选卡页服务端渲染', () => {
  it('首页：热门目的地、多国与全球（有成员数写覆盖数）直接在源码里', async () => {
    const { status, text } = await html('/')
    expect(status).toBe(200)
    expect(text).toContain('出境上网，出发前备好')
    expect(text).toContain('href="/esim/jp"')
    expect(text).toContain('¥19 起')
    expect(text).toContain('欧洲多国')
    expect(text).toContain('¥39 起 · 3 款')
    expect(text).toContain('覆盖 14 个国家和地区 · ¥29 起')
  })

  it('全部目的地：按洲分组，中东与非洲合一组', async () => {
    const { text } = await html('/esim')
    for (const s of ['亚洲', '欧洲', '中东与非洲', '日本', '¥19 起 · 4 款', '埃及', '阿联酋']) expect(text).toContain(s)
    expect(text).not.toContain('欧洲多国')
  })

  it('搜索：拼音命中国家，多国与全球写「含」该国', async () => {
    const { text } = await html('/esim?q=riben')
    expect(text).toContain('日本')
    expect(text).toContain('含日本')
    expect(text).toContain('全球通用')
    const none = await html('/esim?q=zzz')
    expect(none.text).toContain('没有找到「zzz」')
  })

  it('目的地页：套餐卡片与价格在源码里，链接到套餐详情', async () => {
    const { status, text } = await html('/esim/jp')
    expect(status).toBe(200)
    expect(text).toContain('<title>日本 eSIM 与流量卡 · 轻游</title>')
    expect(text).toContain('日本全境可用 · 4G / 5G · 4 款套餐 ¥19 起')
    for (const price of ['¥19', '¥39', '¥69', '¥99.50']) expect(text).toContain(price)
    expect(text).toContain('不限')
    expect(text).toContain('需实名')
    expect(text).toContain('href="/esim/jp/1002"')
  })

  it('套餐详情：指标、徽标、使用区域说明原文与立即购买入口', async () => {
    const { status, text } = await html('/esim/jp/1002')
    expect(status).toBe(200)
    expect(text).toContain('日本 eSIM 7 天 5GB')
    for (const s of ['仅数据', '免实名', '高速流量', '有效天数', '可在日本全境使用，包括北海道、冲绳等离岛。']) expect(text).toContain(s)
    expect(text).toContain('href="/checkout?skuId=1002&amp;num=1&amp;code=JP"')
  })

  it('不存在的目的地与已下架套餐返回 404', async () => {
    expect((await html('/esim/zz')).status).toBe(404)
    expect((await html('/esim/jp/9999')).status).toBe(404)
  })

  it('服务端取目的地数据不带登录态', () => {
    const k3 = api.requests.filter((r) => r.url.startsWith('/buyer/liroam/destinations'))
    expect(k3.length).toBeGreaterThan(0)
    expect(k3.every((r) => !r.headers.accesstoken)).toBe(true)
  })
})
