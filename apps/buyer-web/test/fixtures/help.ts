// 壳文章接口（/buyer/other/article/**）的测试替身，字段按壳 ArticleCategoryVO / ArticleVO / Article 原样；
// 分类 id 与迁移 V*__help_article_categories.sql 一致。单测与 SSR 测试的假 buyer-api 共用
import type { Article, ArticleCategory, ArticleSummary } from '../../app/services/help'

const category = (id: string, name: string, sort: number, children: ArticleCategory[] = [], parentId = '9100000000000003000'): ArticleCategory =>
  ({ id, articleCategoryName: name, parentId, sort, level: children.length ? 0 : 1, type: 'OTHER', children })

export const categoryTree: ArticleCategory[] = [
  category('1390968079827075072', '关于我们', 1, [category('1371779742369316864', '平台信息', 2, [], '1390968079827075072')], '0'),
  category('9100000000000003000', '帮助中心', 2, [
    category('9100000000000003001', '设备兼容', 1),
    category('9100000000000003002', '安装与开卡', 2),
    category('9100000000000003003', '用量与加包', 3),
    category('9100000000000003004', '退款与售后', 4),
    category('9100000000000003009', '常见问题', 9),
    category('9100000000000003010', '优惠活动', 10),
  ], '0'),
]

const article = (id: string, categoryId: string, title: string, content: string, sort = 1, openStatus = true): Article =>
  ({ id, categoryId, title, content, sort, openStatus, type: 'OTHER' })

export const articles: Article[] = [
  article('7001', '9100000000000003009', '什么时候安装 eSIM 最合适？', '<p>建议在出发前一到两天、连着 Wi-Fi 时安装。</p><p>到达后开启数据漫游 &amp; 开始上网。</p>', 1),
  article('7002', '9100000000000003009', '到达后没有信号怎么办？', '<p>先确认已开启这张卡的数据漫游。</p>', 2),
  article('7003', '9100000000000003009', '草稿：还没发布的问题', '<p>未发布</p>', 3, false),
  article('7101', '9100000000000003002', '扫码安装 eSIM', '<h2>iPhone</h2><p>打开设置，添加 eSIM。</p><script>alert(1)</script>', 1),
  article('7102', '9100000000000003002', '手动输入激活码', '<p>无法扫码时手动输入 SM-DP+ 地址与激活码。</p>', 2),
  article('7103', '9100000000000003002', '实名认证怎么填', '<p>在「我的卡」填写证件信息。</p>', 3),
  article('7201', '9100000000000003001', '演示品牌甲', '<p>演示机型 A 系列</p><p>演示机型 B ｜ 部分支持 ｜ 国行版本为双实体卡槽</p><ul><li>演示机型 C | 不支持</li></ul>', 1),
  article('7202', '9100000000000003001', '演示品牌乙', '<table><tbody><tr><td>演示机型 D</td><td>支持</td></tr></tbody></table>', 2),
  article('1371780016521609216', '1371779742369316864', '关于我们', '<p>关于轻游</p>', 1),
]

export const summaries = (categoryId?: string, title?: string): ArticleSummary[] =>
  articles
    .filter((a) => a.openStatus && (!categoryId || a.categoryId === categoryId) && (!title || a.title.includes(title)))
    .sort((a, b) => a.sort - b.sort)
    .map((a) => ({
      id: a.id,
      title: a.title,
      sort: a.sort,
      openStatus: a.openStatus,
      articleCategoryName: categoryTree.flatMap((c) => [c, ...c.children]).find((c) => c.id === a.categoryId)!.articleCategoryName,
    }))
