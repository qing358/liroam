// 服务端渲染：帮助中心、设备兼容、帮助文章的内容直接在 HTML 源码里；404 与取数失败按设计稿 21。
// 内容来自壳文章接口（假 buyer-api，夹具 test/fixtures/help.ts）
import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import { jsonLd, startSsrServer, type SsrServer } from './server'

let ssr: SsrServer
beforeAll(async () => { ssr = await startSsrServer() }, 30_000)
afterAll(() => ssr?.stop())

const html = (path: string) => ssr.html(path)

describe('帮助中心', () => {
  it('首页：话题分类（设备兼容去设备页）、已发布的常见问题、按标题搜索的表单', async () => {
    const { status, text } = await html('/help')
    expect(status).toBe(200)
    expect(text).toContain('<title>帮助中心 · 轻游</title>')
    expect(text).toContain('需要什么帮助？')
    for (const s of ['设备兼容', '我的手机支持 eSIM 吗', '安装与开卡', '扫码、手动输入、实名', '用量与加包', '退款与售后', '优惠活动']) expect(text).toContain(s)
    expect(text).toContain('href="/help/devices"')
    expect(text).toContain('href="/help?category=9100000000000003002"')
    expect(text).toContain('href="/help/7001"')
    expect(text).toContain('到达后没有信号怎么办？')
    expect(text).not.toContain('草稿：还没发布的问题')
    expect(text).toMatch(/<form[^>]*action="\/help"/)
    expect(text).toMatch(/<input[^>]*name="q"/)
  })

  it('首页的常见问题带 FAQPage 结构化数据（问答原文）', async () => {
    const faq = jsonLd((await html('/help')).text, 'FAQPage')
    expect(faq.mainEntity.map((q: { name: string }) => q.name)).toEqual(['什么时候安装 eSIM 最合适？', '到达后没有信号怎么办？'])
    expect(faq.mainEntity[0].acceptedAnswer.text).toBe('建议在出发前一到两天、连着 Wi-Fi 时安装。 到达后开启数据漫游 & 开始上网。')
  })

  it('客服卡片按运营配置显示：只出已配置的方式', async () => {
    const { text } = await html('/help')
    expect(text).toContain('没找到答案？')
    expect(text).toContain('客服在线时间 每天 9:00–22:00（北京时间）')
    expect(text).toContain('href="tel:4000000000"')
    expect(text).not.toContain('在线客服')
  })

  it('分类列表：标题与文章链接；非帮助中心的分类 404', async () => {
    const { status, text } = await html('/help?category=9100000000000003002')
    expect(status).toBe(200)
    expect(text).toContain('<title>安装与开卡 · 轻游帮助中心</title>')
    for (const s of ['扫码安装 eSIM', '手动输入激活码', '实名认证怎么填', 'href="/help/7101"']) expect(text).toContain(s)
    expect((await html('/help?category=1371779742369316864')).status).toBe(404)
  })

  it('搜索：只列帮助中心里的文章，搜不到给提示', async () => {
    const { text } = await html('/help?q=安装')
    expect(text).toContain('扫码安装 eSIM')
    expect(text).toContain('什么时候安装 eSIM 最合适？')
    const none = await html('/help?q=关于')
    expect(none.text).toContain('没有找到「关于」')
    expect(none.text).not.toContain('href="/help/1371780016521609216"')
  })
})

describe('设备兼容', () => {
  it('确认方法、按品牌的机型与支持情况都在源码里（机型来自运营发布的文章）', async () => {
    const { status, text } = await html('/help/devices')
    expect(status).toBe(200)
    expect(text).toContain('<title>支持 eSIM 的手机 · 轻游</title>')
    for (const s of ['我的手机支持 eSIM 吗', '拨号界面输入 *#06#', '显示 EID 一栏，说明手机支持 eSIM', '确认手机没有锁网']) expect(text).toContain(s)
    for (const s of ['演示品牌甲', '演示品牌乙', '演示机型 A 系列', '演示机型 B', '国行版本为双实体卡槽', '演示机型 D', '部分支持', '不支持']) expect(text).toContain(s)
    expect(text).toContain('手机不支持 eSIM？可以选择实体卡套餐，下单后寄送到家。')
  })
})

describe('帮助文章', () => {
  it('正文清洗后渲染，面包屑、分类侧栏、相关问题与反馈', async () => {
    const { status, text } = await html('/help/7101')
    expect(status).toBe(200)
    expect(text).toContain('<title>扫码安装 eSIM · 轻游帮助中心</title>')
    expect(text).toContain('打开设置，添加 eSIM。')
    expect(text).not.toContain('alert(1)')
    expect(text).toContain('href="/help?category=9100000000000003002"')
    expect(text).toContain('href="/help/7102"')
    expect(text).toContain('实名认证怎么填')
    expect(text).toContain('这篇内容有帮助吗？')
  })

  it('帮助文章带单条问答的 FAQPage', async () => {
    const faq = jsonLd((await html('/help/7001')).text, 'FAQPage')
    expect(faq.mainEntity).toHaveLength(1)
    expect(faq.mainEntity[0].name).toBe('什么时候安装 eSIM 最合适？')
  })

  it('未发布、不在帮助中心、不存在的文章都是 404', async () => {
    for (const id of ['7003', '1371780016521609216', '999']) expect((await html(`/help/${id}`)).status, id).toBe(404)
  })
})

describe('404 与错误页（设计稿 21）', () => {
  it('不存在的页面：页面不存在，回到首页', async () => {
    const { status, text } = await html('/no-such-page')
    expect(status).toBe(404)
    for (const s of ['页面不存在', '链接可能已失效', '回到首页']) expect(text).toContain(s)
    expect(text).toMatch(/<meta[^>]*name="robots"[^>]*content="noindex/)
  })

  it('目的地页取数时 buyer-api 不可用：503「网络不太稳定」可重试，不当 404', async () => {
    const { status, text } = await html('/esim/down')
    expect(status).toBe(503)
    for (const s of ['网络不太稳定', '请检查网络后重试', '重试']) expect(text).toContain(s)
    expect(text).not.toContain('页面不存在')
  })

  it('K1 关掉的功能（壳回 91001）：该功能暂未开放，回到首页', async () => {
    const { status, text } = await html('/esim/off')
    expect(status).toBe(403)
    for (const s of ['该功能暂未开放', '可以先看看其它服务', '回到首页']) expect(text).toContain(s)
    expect(text).not.toContain('网络不太稳定')
  })

  it('壳回业务失败的目的地仍是 404', async () => {
    expect((await html('/esim/zz')).status).toBe(404)
  })
})
