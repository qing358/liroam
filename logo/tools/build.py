"""轻游 Liroam 品牌标识构建：生成 ../svg 源文件、../png 导出、../preview 总览。

用法（仓根执行）：
    python3 -m venv /tmp/liroam-logo-venv
    /tmp/liroam-logo-venv/bin/pip install -r logo/tools/requirements.txt
    /tmp/liroam-logo-venv/bin/python logo/tools/build.py
依赖系统命令 rsvg-convert（librsvg）与 convert（ImageMagick，只用于打包 favicon.ico）。
中文字标取自思源黑体 Noto Sans SC（SIL OFL 1.1）转曲；字体路径可用环境变量 LIROAM_FONT_SC 覆盖。
"""
import base64
import os
import shutil
import subprocess
from pathlib import Path

from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont

from geometry import Stroke, bounds, circle, difference, svg_d, translate, union
from marks import CONCEPTS, MARK_STROKE, app_tile, mark_a

ROOT = Path(__file__).resolve().parent.parent
SVG, PNG, PREVIEW = ROOT / "svg", ROOT / "png", ROOT / "preview"
FONT_SC = os.environ.get("LIROAM_FONT_SC", "/mnt/c/Windows/Fonts/NotoSansSC-VF.ttf")
FONT_SC_WEIGHT = 620

TEAL = "#0B7A75"      # 漫游青：主色
INK = "#102A43"       # 夜航墨：中文字标 / 单色
SUNRISE = "#F0652A"   # 日出橙：浅底上的落点
SUNLIGHT = "#FFC04D"  # 日光黄：深底上的落点
WHITE = "#FFFFFF"
MIST = "#EEF6F5"      # 雾青：浅色背景

# 每个配色方案给出：图形标线、落点、中文字标、英文字标、英文 i 点
SCHEMES = {
    "color": (TEAL, SUNRISE, INK, TEAL, SUNRISE),
    "reverse": (WHITE, SUNLIGHT, WHITE, WHITE, SUNLIGHT),
    "mono": (INK, INK, INK, INK, INK),
    "white": (WHITE, WHITE, WHITE, WHITE, WHITE),
}

# ---------- 英文字标 liroam：与图形标同为圆头等宽单线 ----------
X_HEIGHT = 100
ASCENDER = 142
EN_STROKE = 22


def _stem(x, top, bottom=0):
    t = EN_STROKE
    return Stroke(x, top + t / 2, 90).line(bottom - top - t).outline(t)


def _ring(cx):
    r_out = X_HEIGHT / 2 + 1.5
    c = (cx, -X_HEIGHT / 2)
    return difference(circle(c, r_out), circle(c, r_out - EN_STROKE))


def wordmark_en():
    t, X = EN_STROKE, X_HEIGHT
    h = t / 2
    r_arm, r_arch, r_ring = 37, 31, X / 2 + 1.5 - t / 2
    glyphs = []  # (path, advance, 左侧形状, 右侧形状)
    glyphs.append((_stem(h, -ASCENDER), t, "s", "s"))
    glyphs.append((_stem(h, -X), t, "s", "s"))
    arm_y = -X + h + r_arm
    r = union([_stem(h, -X), Stroke(h, arm_y, -90).arc(r_arm, 90).line(8).outline(t)])
    glyphs.append((r, h + r_arm + 8 + h, "s", "open"))
    glyphs.append((_ring(X / 2 + 1.5), X + 3, "o", "o"))
    cx = X / 2 + 1.5
    a = union([_ring(cx), _stem(cx + r_ring, -X)])
    glyphs.append((a, cx + r_ring + h, "o", "s"))
    arch_y = -X + h + r_arch
    m = union([
        _stem(h, -X),
        Stroke(h, arch_y, -90).arc(r_arch, 180).line(-h - arch_y).outline(t),
        Stroke(h + 2 * r_arch, arch_y, -90).arc(r_arch, 180).line(-h - arch_y).outline(t),
    ])
    glyphs.append((m, h + 4 * r_arch + h, "s", "s"))
    gaps = {("s", "s"): 28, ("open", "o"): 12, ("o", "o"): 18, ("o", "s"): 24, ("s", "o"): 24}
    return glyphs, gaps


def build_en(target_width=None):
    glyphs, gaps = wordmark_en()
    pairs = [gaps[(glyphs[i][3], glyphs[i + 1][2])] for i in range(len(glyphs) - 1)]
    natural = sum(g[1] for g in glyphs) + sum(pairs)
    extra = 0 if target_width is None else (target_width - natural) / len(pairs)
    x, parts = 0, []
    for i, (p, adv, _, _) in enumerate(glyphs):
        parts.append(translate(p, x, 0))
        if i == 1:
            dot_x = x + EN_STROKE / 2
        x += adv + (pairs[i] + extra if i < len(pairs) else 0)
    dot_r = EN_STROKE * 0.66
    dot = circle((dot_x, -ASCENDER + dot_r), dot_r)
    return union(parts), dot


# ---------- 中文字标：思源黑体转曲 ----------
def build_zh(text="轻游", tracking=-0.02):
    font = TTFont(FONT_SC)
    gs = font.getGlyphSet(location={"wght": FONT_SC_WEIGHT})
    cmap = font.getBestCmap()
    upm = font["head"].unitsPerEm
    x, parts = 0, []
    import pathops
    for ch in text:
        g = gs[cmap[ord(ch)]]
        p = pathops.Path()
        g.draw(TransformPen(p.getPen(), (1, 0, 0, -1, x, 0)))
        parts.append(union([p]))
        x += g.width + tracking * upm
    return union(parts)


# ---------- 组合 ----------
def fit(path, box_h, x=0, y=0, ref=None):
    """按 ref（默认自身）的外框把 path 缩放到高度 box_h，左上角对齐 (x, y)。"""
    b = bounds(ref if ref is not None else path)
    s = box_h / (b[3] - b[1])
    return translate(translate(path, -b[0], -b[1]), x, y, s), s


def layers_mark(h, x=0, y=0):
    route, dot = mark_a()
    both = union([route, dot])
    r, s = fit(route, h, x, y, both)
    d, _ = fit(dot, h, x, y, both)
    return r, d, s


def text_block(zh_h, x, y, en_gap_ratio=0.24, en_ratio=0.50):
    """中文在上、英文在下，英文与中文等宽；返回 (zh, en, en_dot, 宽, 高)。"""
    zh, _ = fit(build_zh(), zh_h, x, y)
    zb = bounds(zh)
    width = zb[2] - zb[0]
    en, en_dot = build_en()
    en_all = union([en, en_dot])
    eb = bounds(en_all)
    s = (zh_h * en_ratio) / (eb[3] - eb[1])
    en, en_dot = build_en(target_width=width / s)
    en_all = union([en, en_dot])
    eb = bounds(en_all)
    ey = zb[3] + zh_h * en_gap_ratio
    def place(p):
        return translate(translate(p, -eb[0], -eb[1]), x, ey, s)
    en, en_dot = place(en), place(en_dot)
    total = bounds(union([zh, en, en_dot]))
    return zh, en, en_dot, total[2] - total[0], total[3] - total[1]


def lockup_horizontal():
    H = 400
    route, dot, _ = layers_mark(H)
    mb = bounds(union([route, dot]))
    gap = H * 0.30
    zh, en, en_dot, w, h = text_block(H * 0.50, mb[2] + gap, 0)
    dy = (H - h) / 2
    zh, en, en_dot = (translate(p, 0, dy) for p in (zh, en, en_dot))
    return {"route": route, "dot": dot, "zh": zh, "en": en, "en_dot": en_dot}


def lockup_vertical():
    H = 400
    route, dot, _ = layers_mark(H)
    mb = bounds(union([route, dot]))
    zh, en, en_dot, w, h = text_block(H * 0.40, 0, H * 1.24)
    mx = (w - (mb[2] - mb[0])) / 2
    route, dot = translate(route, mx, 0), translate(dot, mx, 0)
    return {"route": route, "dot": dot, "zh": zh, "en": en, "en_dot": en_dot}


def lockup_mark():
    route, dot, _ = layers_mark(400)
    return {"route": route, "dot": dot}


LAYER_COLOR_INDEX = {"route": 0, "dot": 1, "zh": 2, "en": 3, "en_dot": 4}


def layer_paths(layers, scheme, dx=0, dy=0, s=1.0, ids=True):
    colors = SCHEMES[scheme]
    out = []
    for name, p in layers.items():
        idattr = f' id="{name.replace("_", "-")}"' if ids else ""
        p = translate(p, dx, dy, s) if (dx or dy or s != 1.0) else p
        out.append(f'<path{idattr} fill="{colors[LAYER_COLOR_INDEX[name]]}" d="{svg_d(p)}"/>')
    return out


def svg_doc(w, h, body, label="轻游 Liroam"):
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {w:g} {h:g}" width="{w:g}" height="{h:g}" '
        f'role="img" aria-label="{label}">\n<title>{label}</title>\n' + "\n".join(body) + "\n</svg>\n"
    )


def normalize(layers):
    b = bounds(union(list(layers.values())))
    moved = {k: translate(v, -b[0], -b[1]) for k, v in layers.items()}
    return moved, round(b[2] - b[0], 2), round(b[3] - b[1], 2)


def icon_layers(size, mark_ratio, scheme_route, scheme_dot):
    route, dot = mark_a()
    both = union([route, dot])
    b = bounds(both)
    h = size * mark_ratio
    s = h / (b[3] - b[1])
    w = (b[2] - b[0]) * s
    # 落点在右上，整体向左下偏一点做视觉居中
    x = (size - w) / 2 - size * 0.015
    y = (size - h) / 2 + size * 0.02
    r = translate(translate(route, -b[0], -b[1]), x, y, s)
    d = translate(translate(dot, -b[0], -b[1]), x, y, s)
    return [f'<path id="route" fill="{scheme_route}" d="{svg_d(r)}"/>',
            f'<path id="dot" fill="{scheme_dot}" d="{svg_d(d)}"/>']


def write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def rsvg(src, dst, width=None, height=None, bg=None):
    cmd = ["rsvg-convert", str(src), "-o", str(dst)]
    if width:
        cmd += ["-w", str(width)]
    if height:
        cmd += ["-h", str(height)]
    if bg:
        cmd += ["-b", bg]
    subprocess.run(cmd, check=True)


def main():
    for d in (SVG, PNG, PREVIEW):
        if d.exists():
            shutil.rmtree(d)
        d.mkdir(parents=True)

    lockups = {
        "mark": normalize(lockup_mark()),
        "horizontal": normalize(lockup_horizontal()),
        "vertical": normalize(lockup_vertical()),
    }
    png_h = {"mark": 512, "horizontal": 320, "vertical": 640}
    for kind, (layers, w, h) in lockups.items():
        for scheme in SCHEMES:
            name = f"liroam-{kind}-{scheme}"
            write(SVG / f"{name}.svg", svg_doc(w, h, layer_paths(layers, scheme)))
            rsvg(SVG / f"{name}.svg", PNG / f"{name}.png", height=png_h[kind])

    # App 图标：SIM 切角圆角底板（网页 / Android / 宣传用）；满版方形（交给 iOS / 平台自己裁圆角）
    tile = app_tile(1024)
    app = [f'<path id="tile" fill="{TEAL}" d="{svg_d(tile)}"/>'] + icon_layers(1024, 0.54, WHITE, SUNLIGHT)
    write(SVG / "liroam-app-icon.svg", svg_doc(1024, 1024, app))
    full = [f'<rect id="tile" width="1024" height="1024" fill="{TEAL}"/>'] + icon_layers(1024, 0.50, WHITE, SUNLIGHT)
    write(SVG / "liroam-app-icon-fullbleed.svg", svg_doc(1024, 1024, full))
    # favicon：同底板，图形标放大，保证 16px 仍可辨
    fav = [f'<path id="tile" fill="{TEAL}" d="{svg_d(app_tile(64, 14, 15, 4.5))}"/>'] + icon_layers(64, 0.72, WHITE, SUNLIGHT)
    write(SVG / "favicon.svg", svg_doc(64, 64, fav))

    for size in (1024, 512, 192):
        rsvg(SVG / "liroam-app-icon.svg", PNG / f"liroam-app-icon-{size}.png", size, size)
    rsvg(SVG / "liroam-app-icon-fullbleed.svg", PNG / "liroam-app-icon-fullbleed-1024.png", 1024, 1024)
    rsvg(SVG / "liroam-app-icon-fullbleed.svg", PNG / "apple-touch-icon-180.png", 180, 180)
    for size in (16, 32, 48):
        rsvg(SVG / "favicon.svg", PNG / f"favicon-{size}.png", size, size)
    subprocess.run(["convert"] + [str(PNG / f"favicon-{s}.png") for s in (16, 32, 48)] + [str(PNG / "favicon.ico")], check=True)

    build_previews(lockups)


# ---------- 预览图（只出 PNG，不入库 SVG：预览里有标注文字与位图缩略） ----------
LABEL = 'font-family="Noto Sans CJK SC, sans-serif"'


def _img(path, x, y, w, h):
    data = base64.b64encode(path.read_bytes()).decode()
    return f'<image x="{x}" y="{y}" width="{w}" height="{h}" href="data:image/png;base64,{data}"/>'


def _place(layers, scheme, x, y, height, src_h):
    return layer_paths(layers, scheme, x, y, height / src_h, ids=False)


def build_previews(lockups):
    tmp = PREVIEW / "_tmp.svg"
    hl, hw, hh = lockups["horizontal"]
    vl, vw, vh = lockups["vertical"]
    ml, mw, mh = lockups["mark"]
    W, H = 2400, 2380
    b = [f'<rect width="{W}" height="{H}" fill="{WHITE}"/>']
    b.append(f'<text x="120" y="140" {LABEL} font-size="44" font-weight="700" fill="{INK}">轻游 Liroam 品牌标识</text>')
    b.append(f'<text x="120" y="196" {LABEL} font-size="26" fill="{INK}" fill-opacity="0.7">图形标「启程」：一笔落地、转向、起飞，留白后是落点。轻装出发，去哪都连得上。</text>')
    # 横版彩色 / 反白
    b.append(f'<rect x="120" y="250" width="1060" height="420" rx="24" fill="{MIST}"/>')
    b += _place(hl, "color", 120 + (1060 - hw * 160 / hh) / 2, 380, 160, hh)
    b.append(f'<rect x="1220" y="250" width="1060" height="420" rx="24" fill="{INK}"/>')
    b += _place(hl, "reverse", 1220 + (1060 - hw * 160 / hh) / 2, 380, 160, hh)
    # 竖版：彩色 / 主色底反白 / 单色 / 反白
    cards = [("color", WHITE, True), ("white", TEAL, False), ("mono", WHITE, True), ("reverse", INK, False)]
    for i, (scheme, bg, border) in enumerate(cards):
        x = 120 + i * 550
        stroke = ' stroke="#D5E2E1" stroke-width="2"' if border else ""
        b.append(f'<rect x="{x}" y="710" width="510" height="560" rx="24" fill="{bg}"{stroke}/>')
        th = 340
        b += _place(vl, scheme, x + (510 - vw * th / vh) / 2, 710 + (560 - th) / 2, th, vh)
    # 图形标四种配色
    for i, (scheme, bg, border) in enumerate(cards):
        x = 120 + i * 550
        stroke = ' stroke="#D5E2E1" stroke-width="2"' if border else ""
        b.append(f'<rect x="{x}" y="1310" width="510" height="300" rx="24" fill="{bg}"{stroke}/>')
        th = 180
        b += _place(ml, scheme, x + (510 - mw * th / mh) / 2, 1310 + (300 - th) / 2, th, mh)
    # 图标与小尺寸
    b.append(f'<text x="120" y="1700" {LABEL} font-size="28" font-weight="700" fill="{INK}">App 图标与 favicon（右侧为实际像素）</text>')
    b.append(_img(PNG / "liroam-app-icon-512.png", 120, 1740, 400, 400))
    b.append(_img(PNG / "liroam-app-icon-fullbleed-1024.png", 560, 1740, 400, 400))
    x = 1010
    for name, sz in [("liroam-app-icon-192.png", 192), ("apple-touch-icon-180.png", 180), ("favicon-48.png", 48), ("favicon-32.png", 32), ("favicon-16.png", 16)]:
        b.append(_img(PNG / name, x, 2140 - sz, sz, sz))
        b.append(f'<text x="{x}" y="2184" {LABEL} font-size="22" fill="{INK}" fill-opacity="0.7">{sz}</text>')
        x += sz + 44
    # 色板
    swatches = [("漫游青", TEAL, WHITE), ("夜航墨", INK, WHITE), ("日出橙", SUNRISE, WHITE), ("日光黄", SUNLIGHT, INK), ("雾青", MIST, INK)]
    for i, (n, c, fg) in enumerate(swatches):
        x = 120 + i * 440
        b.append(f'<rect x="{x}" y="2220" width="400" height="110" rx="18" fill="{c}" stroke="#D5E2E1" stroke-width="2"/>')
        b.append(f'<text x="{x + 28}" y="2286" {LABEL} font-size="28" font-weight="700" fill="{fg}">{n}  {c}</text>')
    write(tmp, svg_doc(W, H, b, "轻游 Liroam 品牌标识总览"))
    rsvg(tmp, PREVIEW / "overview.png")

    # 方向提案
    b = [f'<rect width="2400" height="900" fill="{WHITE}"/>',
         f'<text x="120" y="130" {LABEL} font-size="40" font-weight="700" fill="{INK}">方向提案（定稿 A）</text>']
    for i, (key, name, desc, fn) in enumerate(CONCEPTS):
        route, dot = fn()
        layers, w, h = normalize({"route": route, "dot": dot})
        x0 = 120 + i * 740
        bg = MIST if key == "A" else "#F5F7F8"
        b.append(f'<rect x="{x0}" y="180" width="700" height="560" rx="24" fill="{bg}"/>')
        s = 300 / max(w, h)
        b += layer_paths(layers, "color", x0 + (700 - w * s) / 2, 230 + (300 - h * s) / 2, s, ids=False)
        for sz, xx in ((32, x0 + 250), (16, x0 + 330)):
            s2 = sz / max(w, h)
            b += layer_paths(layers, "color", xx, 600, s2, ids=False)
        b.append(f'<text x="{x0 + 40}" y="700" {LABEL} font-size="22" fill="{INK}" fill-opacity="0.6">32px / 16px</text>')
        b.append(f'<text x="{x0}" y="800" {LABEL} font-size="32" font-weight="700" fill="{INK}">{key}「{name}」</text>')
        b.append(f'<text x="{x0}" y="848" {LABEL} font-size="24" fill="{INK}" fill-opacity="0.7">{desc}</text>')
    write(tmp, svg_doc(2400, 900, b, "方向提案"))
    rsvg(tmp, PREVIEW / "concepts.png")

    # 禁用示例
    th = 120
    base = _place(hl, "color", 0, 0, th, hh)
    def g(transform, body, extra=""):
        return f'<g transform="{transform}"{extra}>' + "".join(body) + "</g>"
    misuse = [
        ("拉伸或压扁", g("scale(1.35 0.7)", base)),
        ("改用规范外的颜色", g("", [p.replace(TEAL, "#7C3AED").replace(SUNRISE, "#22C55E") for p in base])),
        ("旋转或倾斜", g("rotate(-10) skewX(-8)", base)),
        ("加描边、阴影或渐变", '<defs><filter id="sh"><feDropShadow dx="6" dy="8" stdDeviation="4" flood-color="#000" flood-opacity="0.45"/></filter></defs>'
         + g("", [p.replace("/>", ' stroke="#F0652A" stroke-width="6"/>') for p in base], ' filter="url(#sh)"')),
        ("移动或删掉落点", g("", [p for p in base[:1]] + [p.replace("<path", '<path transform="translate(-60 150)"') for p in base[1:2]] + base[2:])),
        ("放在低对比或杂乱背景上", g("", base)),
    ]
    b = [f'<rect width="2400" height="1100" fill="{WHITE}"/>',
         f'<text x="120" y="130" {LABEL} font-size="40" font-weight="700" fill="{INK}">禁用示例</text>']
    for i, (label, body) in enumerate(misuse):
        x0, y0 = 120 + (i % 3) * 740, 180 + (i // 3) * 450
        bg = "#7FB8B4" if i == 5 else "#F5F7F8"
        b.append(f'<rect x="{x0}" y="{y0}" width="700" height="340" rx="24" fill="{bg}"/>')
        b.append(f'<g transform="translate({x0 + (700 - hw * th / hh) / 2} {y0 + 110})">{body}</g>')
        b.append(f'<path d="M{x0 + 640} {y0 + 30} l32 32 m0 -32 l-32 32" stroke="#D92D20" stroke-width="8" stroke-linecap="round"/>')
        b.append(f'<text x="{x0}" y="{y0 + 390}" {LABEL} font-size="28" fill="{INK}">✕ {label}</text>')
    write(tmp, svg_doc(2400, 1100, b, "禁用示例"))
    rsvg(tmp, PREVIEW / "misuse.png")
    tmp.unlink()


if __name__ == "__main__":
    main()
