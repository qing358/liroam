# 轻游 Liroam 品牌标识规范

![总览](preview/overview.png)

## 1. 图形标「折角」

一段线路竖向落下，45° 转折，水平前行；留 1u 空白，之后是站点圆点。

- 语言取自地铁 / 航线线路图：统一笔宽，只用 90° 与 45°，两端平切，站点为实心圆。没有拟物，没有手绘感。
- 轮廓是 **L**（Liroam 的首字母）；转折是「换乘 / 跨境」，站点是目的地，也是「到哪都在线」的信号点。
- 45° 折角与 App 图标底板右上角的 SIM 卡切角是同一个角度：标志与「卡」用同一笔几何。底板只用于图标场景，不进组合标。

**网格**（单位 u = 笔宽，源文件见 `tools/marks.py`）：

| 部位 | 尺寸 |
|------|------|
| 整体高度 | 6u（竖笔顶到水平段底） |
| 竖笔 / 水平段 | 宽 1u，竖笔中心线 x = 0.5u，水平段中心线 y = 5.5u |
| 45° 折角 | 水平、竖直方向各占 1u |
| 水平段长度 | 平切于 x = 3.5u |
| 空白 | 1u |
| 站点 | 直径 1.06u（笔宽加 6% 圆形视觉补偿），圆心与水平段中心线同高 |

英文字标 `liroam` 为同一套平头等宽单线手绘几何字，与中文等宽对齐；i 的点与站点同色，是全套标识里唯一的暖色。

主笔画的三个精修变体见 [`preview/concepts.png`](preview/concepts.png)，网格、笔宽、站点完全相同，只有转角不同：

- V1「直角」：最硬朗，但与通用「L.」字母标几乎无法区分，缺乏归属感。
- **V2「折角」（定稿）**：45° 折角呼应 SIM 切角，线路图语义明确；在 16px 下仍能看出切角。
- V3「象限」：四分之一圆转角，最柔和，但更接近通用电信或互联网标志，商务感弱于 V2。

## 2. 色彩

| 名称 | 角色 | HEX | RGB | 说明 |
|------|------|-----|-----|------|
| 漫游青 | 主色 | `#0B7A75` | 11 122 117 | 图形标、英文字标、App 底板；与白对比 5.2:1 |
| 夜航墨 | 文字 / 深底 / 单色 | `#102A43` | 16 42 67 | 中文字标、单色版、深色背景 |
| 日出橙 | 浅底站点 | `#E4572E` | 228 87 46 | 只用于浅色背景上的站点与 i 点；与白对比 3.7:1 |
| 日光黄 | 深底站点 | `#FFC04D` | 255 192 77 | 只用于漫游青 / 夜航墨背景上的站点与 i 点；与漫游青 3.2:1、与夜航墨 9.0:1 |
| 雾青 | 浅背景 | `#EEF6F5` | 238 246 245 | 版面浅底 |

站点色随背景换：浅底用日出橙，深底用日光黄；同一画面不混用。标识内暖色只出现在站点与 i 点，不扩大到其它元素。

### 前端主题色

界面（四端商城前端）用同一色板，派生色只按下表取，令牌在 `shop/lilishop-ui/brand/`（`tokens.scss` / `tokens.less` / `theme.css` 三份同值，`scripts/brand.test.mjs` 校验与上表一致）：

| 令牌 | HEX | 用途 | 对比（白底 / 白字） |
|------|-----|------|------|
| `primary` 漫游青 | `#0B7A75` | 主按钮、链接、选中、标签、进度、Element `--el-color-primary` | 5.2:1 |
| `primary-hover` | `#09625E` | 主按钮悬停、链接悬停（Element `dark-2`）；不用 Element 默认的「变浅」悬停，白字会不够清楚 | 7.2:1 |
| `primary-active` | `#074E4B` | 主按钮按下 | 9.5:1 |
| `primary-light-3/5/7/8` | `#54A29E` `#85BDBA` `#B6D7D6` `#CEE4E3` | 禁用态、描边、浅色块（主色与白按 70/50/30/20% 混合）；不承载正文文字 | — |
| `mist` 雾青 | `#EEF6F5` | 选中行、浅底、表头（Element `light-9`） | — |
| `ink` 夜航墨 | `#102A43` | 深色侧栏、深色区块、标题文字 | 14.6:1 |
| `accent` 日出橙 | `#E4572E` | 强调色：促销角标底、描边、大号数字与图形点缀 | 3.7:1，只配大字或图形 |
| `accent-text` | `#C8461F` | 强调色的文字版：价格、优惠、提示文字等小字 | 4.8:1 |
| `accent-light-9` | `#FCEEEA` | 强调浅底；上面的 `accent-text` 只有 4.3:1，不放小字 | — |
| `sun` 日光黄 | `#FFC04D` | 深底（夜航墨 / 漫游青深色）上的大号数字与图形点缀，如优惠券面额；不上浅底 | 与夜航墨 9.0:1、与 `primary-active` 5.9:1 |

Element 的成功 / 警告 / 危险状态色保持其语义色，不换成品牌色。买家端主题色可由运营在后台「主题设置」改（`THEME_SETTING`），出厂值为漫游青。

## 3. 版本与选用

| 版本 | 文件（`svg/`） | 用在哪 |
|------|----------------|--------|
| 横版组合 | `liroam-horizontal-{color,reverse,mono,white}.svg` | 默认首选：网站页头、宣传物、合同抬头 |
| 竖版组合 | `liroam-vertical-{…}.svg` | 方形或竖向版面：启动页、海报、包装、实体卡背 |
| 图形标 | `liroam-mark-{…}.svg` | 空间受限、品牌已在上下文出现时：头像、角标、水印 |
| App 图标 | `liroam-app-icon.svg` | 网页 / Android / 宣传里的应用图标（自带 SIM 切角底板） |
| App 图标满版 | `liroam-app-icon-fullbleed.svg` | 提交 iOS / 小程序等会自行裁圆角的平台 |
| favicon | `favicon.svg`、`png/favicon.ico` | 浏览器标签；图形标在底板内放大，16px 可辨 |

配色后缀：`color` 浅底彩色（默认）；`reverse` 深底彩色（白 + 日光黄站点，用于漫游青 / 夜航墨底）；`mono` 单色夜航墨（单色印刷、传真、压印可改为纯黑 `#000000`）；`white` 单色反白（照片或品牌色底）；`ink` 墨色（见下）。

### 追加套组：夜航星与墨色

两套是一对：「夜航星」= 夜航墨底 + 白色图形与字标 + 日光黄站点（站点像夜航中的一颗星）；「墨色」= 把它的前景与背景对调，夜航墨前景放在白底上，站点按浅底规则换成日出橙（日光黄在白底上对比不足）。

| 套组 | 文件 | 用在哪 |
|------|------|--------|
| 夜航星横排成品 | `liroam-horizontal-night-plate.svg` / `.png` | 自带夜航墨底板与四周 2x 安全区，可直接整块放：深色页头、社媒横幅、PPT 封面与尾页、活动背板、邮件签名深色条。透明底的同配色横版仍是 `liroam-horizontal-reverse` |
| 墨色横排成品 | `liroam-horizontal-ink-plate.svg` / `.png` | 自带白底与 2x 安全区：文档首页、合作方 Logo 墙、媒体投稿等需要「一张带底的图」的场合 |
| 墨色组合 / 图形标 | `liroam-{mark,horizontal,vertical}-ink.svg` / `.png` | 浅底上的商务正式场景：合同、发票、报价单、名片、浅色卡面与包装、白底演示文稿；比 `color` 更克制，比 `mono` 多一个品牌暖点 |
| 浅色 App 图标 | `liroam-app-icon-light.svg`（SIM 切角白底板）、`liroam-app-icon-light-fullbleed.svg`；PNG 1024 / 512 / 192、满版 1024、`apple-touch-icon-light-180.png` | 深色背景上需要高亮的图标：深色模式应用商店横幅、深色宣传页里的下载入口、渠道方要求浅色图标时。默认图标仍用漫游青版 |

选用规则：

- `mono` 与 `ink` 的区别：`mono` 只有一个颜色，用于单色印刷、压印、烫印；`ink` 是双色（夜航墨 + 日出橙），用于正常彩色输出。
- 同一画面里有深浅两种区块时，深区用夜航星，浅区用墨色，不在同一区块里混用。
- 成品带底板，不要再叠加到别的底色上；需要放在其它背景上时改用透明底文件。
- 浅色 App 图标白底板放在白色页面上会看不出边界，白底场景继续用漫游青默认图标；favicon 不出浅色版。

## 4. 字体与授权

| 用途 | 字体 | 授权 |
|------|------|------|
| 中文字标「轻游」 | 思源黑体 Noto Sans SC，可变字重取 wght 620，字距 −0.02em，**已转曲** | SIL Open Font License 1.1：可免费商用，可嵌入、可转曲做标识；不得单独出售字体文件 |
| 英文字标 `liroam` | 无字体：本标识手绘几何字（`tools/build.py` 中构造） | 归轻游所有，无第三方授权 |
| 配套排版（页面、物料正文） | 中文思源黑体 / Noto Sans SC；西文与数字可用 Inter | 均为 SIL OFL 1.1，可商用 |

所有 SVG 均为纯路径，不含 `<text>`、不含嵌入位图，不依赖用户本机字体。

## 5. 安全区与最小尺寸

**单位 x = 图形标笔宽 1u**（= 图形标高度 ÷ 6）。

- 安全区：任何版本四周至少留 **1x** 空白，不放文字、图形或版面边缘；印刷品建议 2x。
- 横版：x ≈ 组合高度 × 0.167；竖版：x ≈ 组合高度 × 0.086。
- SVG 画布即为图形外框（不含安全区），排版时自行留白；例外是两张 `*-plate` 成品，画布已含 2x 安全区与底色。

| 版本 | 屏幕最小 | 印刷最小 |
|------|----------|----------|
| 横版组合 | 高 36px | 高 10mm |
| 竖版组合 | 高 72px | 高 20mm |
| 图形标 | 高 20px | 高 6mm |
| App 图标 / favicon | 16px（用 `favicon.*`，不要缩图形标） | — |

更小的场合不用组合标，只用图形标或 favicon。

## 6. 禁用示例

![禁用示例](preview/misuse.png)

- 不拉伸、压扁、旋转、倾斜。
- 不改色、不加描边 / 阴影 / 渐变 / 发光，不把暖色扩到站点以外。
- 不移动、放大、删除站点，不改图形标与文字的相对位置和比例。
- 不放在低对比或杂乱背景上：浅底用 `color` / `mono`，深底或品牌色底用 `reverse` / `white`。
- 不用其它字体重排「轻游」或 `liroam`，一律用这里的转曲文件。

## 7. 文件清单与重建

```
logo/
├── README.md            本规范
├── svg/                 源文件（纯路径）：mark / horizontal / vertical × color / reverse / mono / white / ink，horizontal-night-plate、horizontal-ink-plate，app-icon、app-icon-fullbleed、app-icon-light、app-icon-light-fullbleed，favicon
├── png/                 导出：各组合透明底 PNG、两张带底板横排成品；app-icon 与 app-icon-light 各 1024/512/192 与满版 1024、apple-touch-icon(-light)-180、favicon 16/32/48 与 favicon.ico
├── preview/             overview.png 总览、concepts.png 方向提案、misuse.png 禁用示例
└── tools/               构建脚本：geometry.py（中心线 → 等宽描边轮廓）、marks.py（图形标网格、三个变体与底板）、build.py（字标、组合、导出）、assets.py（商城站内素材：装修轮播 / 活动卡、装修样例（套餐商品图、品牌墙、频道、分类导航、热区、楼层横幅、视频封面、分类页模板缩略图）、会员等级图标与等级卡背景、权益图标、商品与店铺占位图、admin 头像、本机登录滑块素材）
```

几何、配色、字距都只在 `tools/` 里改，改完重建，不要手改导出物：

```bash
python3 -m venv /tmp/liroam-logo-venv
/tmp/liroam-logo-venv/bin/pip install -r logo/tools/requirements.txt
/tmp/liroam-logo-venv/bin/python logo/tools/build.py   # 需要 rsvg-convert 与 ImageMagick convert
python3 logo/tools/assets.py                            # 只用标准库；滑块 jpg / png 同样需要 rsvg-convert 与 convert
```

`assets.py` 的色值从本文件色板表读，图形取 `svg/` 的白色标与反白横排，线路图母题（竖线 → 45° 折角 → 横线 → 站点）只在脚本里改；产物写到 `shop/lilishop-ui/{buyer,seller,manager}/public/liroam/` 与 `deploy/dev-assets/verification/`，库里装修与会员图片按根路径 `/liroam/...` 引用。

中文字形取自 `NotoSansSC-VF.ttf`，默认路径为 WSL 下的 Windows 字体目录，可用环境变量 `LIROAM_FONT_SC` 指向其它位置（[Google Fonts 下载](https://fonts.google.com/noto/specimen/Noto+Sans+SC)）。

商城前端（`shop/lilishop-ui` 四端）已换用本标识：浅底一律墨色组合标 `liroam-horizontal-ink.svg`（商家端收起侧栏用 `liroam-mark-ink.svg`），管理端登录页深色品牌区用 `liroam-horizontal-reverse.svg`；四端都没有明暗主题切换，按浅色处理。favicon 固定用 `png/favicon.ico`，不随主题或站点设置变化。后台站点设置里的 logo / icon 为空时回落到上述内置标识；运营上传了自定义 logo 则以设置为准。四端主题色按上文「前端主题色」取令牌（`shop/lilishop-ui/brand/theme.css` 由四端 `main.js` 加载，覆盖 Element Plus 主色变量）。替换范围与合并注意见 `docs/upstream.md` 改动清单「品牌」一行，回归闸为 `node --test scripts/brand.test.mjs`。
