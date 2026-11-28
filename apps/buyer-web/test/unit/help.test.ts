import { readdirSync, readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import {
  HELP_CATEGORY,
  articleHtml,
  articleText,
  deviceBrands,
  helpCategoryName,
  helpTopics,
  searchDevices,
  searchHelp,
} from '~/services/help'
import { createBuyerApi } from '~/utils/buyer-api'
import { createSessionStore, memoryStorage } from '~/utils/session'
import { articles, categoryTree, summaries } from '../fixtures/help'

const byId = (id: string) => articles.find((a) => a.id === id)!

describe('帮助中心分类', () => {
  it('分类 id 与迁移脚本一致（同一事实两处写，靠这条测试拴住）', () => {
    const dir = new URL('../../../../modules/liroam-schema/src/main/resources/db/migration/liroam/', import.meta.url)
    const file = readdirSync(dir).find((f) => f.endsWith('__help_article_categories.sql'))
    expect(file, '缺帮助中心分类迁移').toBeTruthy()
    const sql = readFileSync(new URL(file!, dir), 'utf8')
    for (const id of Object.values(HELP_CATEGORY)) expect(sql).toContain(id)
  })

  it('话题只取帮助中心下的分类、按排序，常见问题单独成区；设备兼容去设备页，其它去分类列表', () => {
    const topics = helpTopics(categoryTree)
    expect(topics.map((t) => t.name)).toEqual(['设备兼容', '安装与开卡', '用量与加包', '退款与售后', '优惠活动'])
    expect(topics[0]).toMatchObject({ to: '/help/devices', hint: '我的手机支持 eSIM 吗', icon: 'i-lucide-smartphone' })
    expect(topics[1]).toMatchObject({ to: '/help?category=9100000000000003002', hint: '扫码、手动输入、实名', icon: 'i-lucide-qr-code' })
    expect(topics[4], '运营新加的分类：通用图标、无副文').toMatchObject({ icon: 'i-lucide-book-open', hint: null })
  })

  it('没有帮助中心根分类时为空（分类被删或缓存未刷新）', () => {
    expect(helpTopics(categoryTree.filter((c) => c.id !== HELP_CATEGORY.root))).toEqual([])
  })

  it('分类名只认帮助中心下的分类', () => {
    expect(helpCategoryName(categoryTree, '9100000000000003002')).toBe('安装与开卡')
    expect(helpCategoryName(categoryTree, '1371779742369316864')).toBeNull()
  })
})

describe('文章内容', () => {
  it('纯文本：去标签、解实体、合并空白，可截断', () => {
    expect(articleText(byId('7001').content)).toBe('建议在出发前一到两天、连着 Wi-Fi 时安装。 到达后开启数据漫游 & 开始上网。')
    expect(articleText(byId('7001').content, 10)).toBe('建议在出发前一到两天…')
  })

  it('渲染前清洗：去脚本与事件属性、javascript 链接，h1 降为 h2', () => {
    const html = articleHtml('<h1>标题</h1><p onclick="x()">正文<a href="javascript:alert(1)">坏链</a><a href="/help/7002">好链</a></p><script>alert(1)</script><iframe src="//x"></iframe>')
    expect(html).toContain('<h2>标题</h2>')
    expect(html).toContain('<a href="/help/7002">好链</a>')
    expect(html).not.toMatch(/script|onclick|javascript|iframe/)
  })
})

describe('设备兼容', () => {
  const brands = deviceBrands([byId('7201'), byId('7202')])

  it('每篇文章是一个品牌，每段 / 列表项 / 表格行是一个机型；「机型 | 支持情况 | 说明」缺省为支持', () => {
    expect(brands).toEqual([
      {
        brand: '演示品牌甲',
        models: [
          { name: '演示机型 A 系列', support: 'FULL', note: null },
          { name: '演示机型 B', support: 'PARTIAL', note: '国行版本为双实体卡槽' },
          { name: '演示机型 C', support: 'NONE', note: null },
        ],
      },
      { brand: '演示品牌乙', models: [{ name: '演示机型 D', support: 'FULL', note: null }] },
    ])
  })

  it('没有机型行的品牌不出', () => {
    expect(deviceBrands([{ ...byId('7201'), content: '<p> </p>' }])).toEqual([])
  })

  it('搜索跨品牌、忽略大小写与空格', () => {
    expect(searchDevices(brands, '机型d').map((m) => `${m.brand} ${m.name}`)).toEqual(['演示品牌乙 演示机型 D'])
    expect(searchDevices(brands, ' 演示机型 ')).toHaveLength(4)
    expect(searchDevices(brands, '不存在')).toEqual([])
  })
})

describe('搜索帮助文章', () => {
  it('按标题查壳文章，只留帮助中心里除设备兼容外的分类', async () => {
    const requests: string[] = []
    const api = createBuyerApi({
      baseURL: 'http://api',
      session: createSessionStore(memoryStorage()),
      fetch: async (input) => {
        const url = new URL(String(input))
        requests.push(url.pathname + url.search)
        const all = [...summaries(undefined, url.searchParams.get('title')!), ...summaries(undefined, '演示')]
        return new Response(JSON.stringify({ success: true, code: 200, result: { records: all, total: all.length } }), { headers: { 'content-type': 'application/json' } })
      },
    })
    const found = await searchHelp(api, categoryTree, '关于')
    expect(requests[0]).toBe('/buyer/other/article?title=%E5%85%B3%E4%BA%8E&pageNumber=1&pageSize=20')
    expect(found).toEqual([])
    expect((await searchHelp(api, categoryTree, '安装')).map((a) => a.title)).toEqual(['什么时候安装 eSIM 最合适？', '扫码安装 eSIM'])
  })
})
