// 帮助中心（设计稿 15～17）：内容来自壳文章接口（/buyer/other/article/**，匿名可读），运营在管理端「文章管理」维护。
// 分类 id 由迁移 V*__help_article_categories.sql 建好（test/unit/help.test.ts 拴住两边一致）；本文件是接口与展示口径的唯一出处。
import { DOCUMENT_NODE, ELEMENT_NODE, TEXT_NODE, parse, transformSync, walkSync } from 'ultrahtml'
import sanitize from 'ultrahtml/transformers/sanitize'
import type { BuyerApi } from '~/utils/buyer-api'

export const HELP_CATEGORY = {
  root: '9100000000000003000',
  devices: '9100000000000003001',
  faq: '9100000000000003009',
} as const

/** 壳 ArticleCategoryVO（分类树，至多两级） */
export interface ArticleCategory {
  id: string
  articleCategoryName: string
  parentId: string
  sort: number
  level: number
  type: string | null
  children: ArticleCategory[]
}

/** 壳 ArticleVO（分页列表项，只列已发布） */
export interface ArticleSummary {
  id: string
  title: string
  articleCategoryName: string
  sort: number | null
  openStatus: boolean
}

/** 壳 Article（content 为富文本 HTML） */
export interface Article {
  id: string
  title: string
  categoryId: string
  sort: number | null
  content: string
  openStatus: boolean | null
  type: string | null
}

interface Page<T> {
  records: T[]
  total: number
}

const BASE = '/buyer/other/article'

export const articleCategories = (api: BuyerApi) => api.get<ArticleCategory[]>(`${BASE}/articleCategory/list`)

export async function categoryArticles(api: BuyerApi, categoryId: string, pageSize = 50): Promise<ArticleSummary[]> {
  const page = await api.get<Page<ArticleSummary>>(BASE, { query: { categoryId, pageNumber: 1, pageSize } })
  return page.records
}

/** 壳按 id 取文章不看发布状态，这里未发布的当作不存在 */
export async function helpArticle(api: BuyerApi, id: string): Promise<Article | null> {
  const article = await api.get<Article | null>(`${BASE}/get/${encodeURIComponent(id)}`)
  return article?.openStatus ? article : null
}

// ---- 分类 ----

const helpRoot = (tree: ArticleCategory[]) => tree.find((c) => c.id === HELP_CATEGORY.root)

const helpChildren = (tree: ArticleCategory[]) => [...(helpRoot(tree)?.children ?? [])].sort((a, b) => a.sort - b.sort)

/** 帮助中心下的分类名；不在帮助中心下为 null */
export const helpCategoryName = (tree: ArticleCategory[], id: string) =>
  helpChildren(tree).find((c) => c.id === id)?.articleCategoryName ?? null

/** 话题图标与副文（设计稿 15）；运营新加的分类用通用图标、不写副文 */
const TOPIC_LOOK: Record<string, { icon: string, hint: string }> = {
  '9100000000000003001': { icon: 'i-lucide-smartphone', hint: '我的手机支持 eSIM 吗' },
  '9100000000000003002': { icon: 'i-lucide-qr-code', hint: '扫码、手动输入、实名' },
  '9100000000000003003': { icon: 'i-lucide-zap', hint: '剩余流量、到期、加包' },
  '9100000000000003004': { icon: 'i-lucide-undo-2', hint: '退款说明、实体卡配送' },
  '9100000000000003005': { icon: 'i-lucide-link', hint: '京东、小红书买的卡' },
  '9100000000000003006': { icon: 'i-lucide-globe', hint: '数据漫游、APN、热点' },
}

export interface HelpTopic {
  id: string
  name: string
  icon: string
  hint: string | null
  to: string
}

export const categoryPath = (id: string) => (id === HELP_CATEGORY.devices ? '/help/devices' : `/help?category=${id}`)

/** 帮助中心首页的话题块：根分类下除常见问题外的全部分类，按排序 */
export function helpTopics(tree: ArticleCategory[]): HelpTopic[] {
  return helpChildren(tree)
    .filter((c) => c.id !== HELP_CATEGORY.faq)
    .map((c) => ({
      id: c.id,
      name: c.articleCategoryName,
      icon: TOPIC_LOOK[c.id]?.icon ?? 'i-lucide-book-open',
      hint: TOPIC_LOOK[c.id]?.hint ?? null,
      to: categoryPath(c.id),
    }))
}

/** 能当帮助文章展示与搜索的分类（设备兼容的文章是机型数据，只在设备页用） */
export const articleCategoryIds = (tree: ArticleCategory[]) =>
  helpChildren(tree).filter((c) => c.id !== HELP_CATEGORY.devices).map((c) => c.id)

/** 按标题搜帮助文章：壳只能按标题模糊查全部文章，这里按分类名留下帮助中心里的 */
export async function searchHelp(api: BuyerApi, tree: ArticleCategory[], q: string): Promise<ArticleSummary[]> {
  const names = new Set(articleCategoryIds(tree).map((id) => helpCategoryName(tree, id)))
  const page = await api.get<Page<ArticleSummary>>(BASE, { query: { title: q.trim(), pageNumber: 1, pageSize: 20 } })
  return page.records.filter((a) => names.has(a.articleCategoryName))
}

// ---- 正文 ----

const ENTITIES: Record<string, string> = { amp: '&', lt: '<', gt: '>', quot: '"', apos: '\'', nbsp: ' ', '#39': '\'' }
const decode = (s: string) => s.replace(/&(#\d+|#x[\da-f]+|\w+);/gi, (m, e: string) => {
  if (e[0] === '#') return String.fromCodePoint(e[1]?.toLowerCase() === 'x' ? Number.parseInt(e.slice(2), 16) : Number(e.slice(1)))
  return ENTITIES[e.toLowerCase()] ?? m
})

const BLOCKS = new Set(['p', 'div', 'li', 'tr', 'br', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'blockquote', 'section'])
const SKIP = new Set(['script', 'style'])

interface HtmlNode { type: number, name?: string, value?: string, children?: HtmlNode[] }

/** 按块（段落、列表项、表格行等）拆成纯文本行；表格行的单元格用 | 连接 */
function textLines(html: string): string[] {
  const lines: string[] = []
  let current: string[] = []
  const flush = () => {
    const line = decode(current.join('')).replace(/\s+/g, ' ').trim()
    if (line) lines.push(line)
    current = []
  }
  const visit = (node: HtmlNode) => {
    if (node.type === TEXT_NODE) {
      current.push(node.value ?? '')
      return
    }
    if (node.type !== ELEMENT_NODE && node.type !== DOCUMENT_NODE) return
    if (node.name && SKIP.has(node.name)) return
    const block = !!node.name && BLOCKS.has(node.name)
    if (block) flush()
    for (const [i, child] of (node.children ?? []).entries()) {
      if (node.name === 'tr' && i > 0) current.push(' | ')
      visit(child)
    }
    if (block) flush()
  }
  visit(parse(html ?? ''))
  flush()
  return lines
}

/** 纯文本（描述、FAQ 结构化数据）；max 为截断字数 */
export function articleText(html: string, max?: number): string {
  const text = textLines(html).join(' ')
  return max && text.length > max ? `${text.slice(0, max)}…` : text
}

const ALLOWED = ['p', 'br', 'h2', 'h3', 'h4', 'strong', 'b', 'em', 'i', 'u', 's', 'ul', 'ol', 'li', 'a', 'img', 'table', 'thead', 'tbody', 'tr', 'th', 'td', 'blockquote', 'span', 'div', 'hr', 'code', 'pre']
const ATTRIBUTES: Record<string, string[]> = { a: ['href'], img: ['src', 'alt', 'width', 'height'], td: ['colspan', 'rowspan'], th: ['colspan', 'rowspan'] }

/** 渲染前清洗运营写的富文本：只留排版标签（其它标签去掉、保留文字）与链接、图片地址；页面已有 h1，正文 h1 降为 h2 */
export function articleHtml(html: string): string {
  return transformSync(html ?? '', [
    (doc) => {
      walkSync(doc, (node) => {
        if (node.type !== ELEMENT_NODE) return
        if (node.name === 'h1') node.name = 'h2'
        const keep = ATTRIBUTES[node.name] ?? []
        node.attributes = Object.fromEntries(Object.entries(node.attributes)
          .filter(([key, value]) => keep.includes(key) && !/^\s*(javascript|data|vbscript):/i.test(value)))
      })
      return doc
    },
    sanitize({ unblockElements: ALLOWED, dropElements: ['script', 'style', 'iframe', 'object', 'embed', 'form'] }),
  ])
}

// ---- 设备兼容（设计稿 16） ----

export type DeviceSupport = 'FULL' | 'PARTIAL' | 'NONE'

export const SUPPORT_LABEL: Record<DeviceSupport, string> = { FULL: '支持', PARTIAL: '部分支持', NONE: '不支持' }

export interface DeviceModel {
  name: string
  support: DeviceSupport
  note: string | null
}

export interface DeviceBrand {
  brand: string
  models: DeviceModel[]
}

const SUPPORT_BY_LABEL = Object.fromEntries(Object.entries(SUPPORT_LABEL).map(([k, v]) => [v, k as DeviceSupport]))

/**
 * 「设备兼容」分类下每篇文章是一个品牌（标题即品牌名，按文章排序），正文每段 / 列表项 / 表格行是一个机型：
 * 「机型 | 支持情况 | 说明」，支持情况取 支持 / 部分支持 / 不支持，缺省为支持；全角竖线同样可用。
 */
export function deviceBrands(articles: Pick<Article, 'title' | 'content'>[]): DeviceBrand[] {
  return articles
    .map((a) => ({
      brand: a.title.trim(),
      models: textLines(a.content).map((line): DeviceModel => {
        const [name = '', status = '', ...rest] = line.split(/\s*[|｜]\s*/)
        const support = SUPPORT_BY_LABEL[status]
        const note = (support ? rest : [status, ...rest]).filter(Boolean).join('，')
        return { name, support: support ?? 'FULL', note: note || null }
      }).filter((m) => m.name),
    }))
    .filter((b) => b.models.length > 0)
}

const squash = (s: string) => s.replace(/\s+/g, '').toLowerCase()

/** 跨品牌按机型名搜索（忽略大小写与空格） */
export function searchDevices(brands: DeviceBrand[], q: string): (DeviceModel & { brand: string })[] {
  const needle = squash(q)
  if (!needle) return []
  return brands.flatMap((b) => b.models.filter((m) => squash(m.name).includes(needle)).map((m) => ({ ...m, brand: b.brand })))
}
