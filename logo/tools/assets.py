"""轻游界面素材：装修横幅与活动卡底图、会员等级 / 权益图标、默认头像与占位图、本机滑块验证码素材。

色值只取 logo/README.md 色值表与前端主题令牌表，图形标与组合标直接复用 logo/svg/ 的路径，不另画一套。
输出写进各前端 public/liroam/ 与 deploy/dev-assets/verification/，库里的装修与会员数据用站内相对路径引用。
重建：python3 logo/tools/assets.py（滑块素材需要 rsvg-convert 与 ImageMagick convert）。
"""

import re
import shutil
import subprocess
import tempfile
from pathlib import Path

LOGO = Path(__file__).resolve().parent.parent
REPO = LOGO.parent
UI = REPO / "shop/lilishop-ui"


def _palette():
    text = (LOGO / "README.md").read_text(encoding="utf-8")
    names = {"漫游青": "teal", "夜航墨": "ink", "日出橙": "sunrise", "日光黄": "sunlight", "雾青": "mist"}
    colors = {names[m[1]]: m[2] for m in re.finditer(r"^\| (漫游青|夜航墨|日出橙|日光黄|雾青) \|[^|]*\| `(#[0-9A-F]{6})`", text, re.M)}
    tokens = {m[1]: m[2] for m in re.finditer(r"^\| `(primary-light-3/5/7/8|primary-hover)`[^|]*\| ([^|]+)\|", text, re.M)}
    light = re.findall(r"#[0-9A-F]{6}", tokens["primary-light-3/5/7/8"])
    colors.update(light3=light[0], light5=light[1], light7=light[2], light8=light[3])
    colors["hover"] = re.search(r"#[0-9A-F]{6}", tokens["primary-hover"])[0]
    assert len(colors) == 10, colors
    return colors


C = _palette()
WHITE = "#FFFFFF"
FONT = "'Noto Sans SC', 'PingFang SC', 'Microsoft YaHei', sans-serif"


def _paths(name):
    svg = (LOGO / "svg" / name).read_text(encoding="utf-8")
    box = re.search(r'viewBox="([^"]+)"', svg)[1]
    return box, re.findall(r'<path id="(\w+)" fill="[^"]+" d="([^"]+)"/>', svg)


MARK_BOX, MARK = _paths("liroam-mark-white.svg")
LOCKUP_BOX, LOCKUP = _paths("liroam-horizontal-reverse.svg")


def mark(x, y, h, color, dot):
    w = h * float(MARK_BOX.split()[2]) / float(MARK_BOX.split()[3])
    body = "".join(f'<path fill="{dot if pid == "dot" else color}" d="{d}"/>' for pid, d in MARK)
    return f'<svg x="{x:.1f}" y="{y:.1f}" width="{w:.1f}" height="{h}" viewBox="{MARK_BOX}">{body}</svg>'


def lockup(x, y, h):
    w = h * float(LOCKUP_BOX.split()[2]) / float(LOCKUP_BOX.split()[3])
    body = "".join(f'<path fill="{C["sunlight"] if pid == "dot" else WHITE}" d="{d}"/>' for pid, d in LOCKUP)
    return f'<svg x="{x}" y="{y}" width="{w:.1f}" height="{h}" viewBox="{LOCKUP_BOX}">{body}</svg>'


def route(x, y, down, end, u, color, dot=None):
    """图形标的线路语言：竖向落下 → 45° 折角 → 水平前行到 end，之后留 1u 空白是站点。"""
    d = f"M{x} {y}V{y + down}L{x + u} {y + down + u}H{end}"
    line = f'<path d="{d}" fill="none" stroke="{color}" stroke-width="{u}" stroke-linecap="butt" stroke-linejoin="miter"/>'
    if dot:
        line += f'<circle cx="{end + 1.53 * u}" cy="{y + down + u}" r="{0.53 * u}" fill="{dot}"/>'
    return line


def svg(w, h, body, title):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {w} {h}" width="{w}" height="{h}" role="img" '
            f'aria-label="{title}"><title>{title}</title>{body}</svg>\n')


def text(x, y, size, color, content, weight=600):
    return (f'<text x="{x}" y="{y}" font-family="{FONT}" font-size="{size}" font-weight="{weight}" '
            f'fill="{color}">{content}</text>')


BANNERS = [
    ("ink", "出境上网，出发前备好", "按目的地和天数选购旅行通信卡，eSIM 与实体卡可选"),
    ("teal", "eSIM 付款后在线发码", "实体卡发货后可查物流，开卡进度在「我的卡」查看"),
    ("ink", "一人多卡，按卡管理", "每张卡单独查看用量与到期时间，流量不够可续费加包"),
    ("teal", "流量不够，原卡加包", "在「我的卡」直接续费或加流量包，无需换卡"),
]


def banner(w, h, bg, title, sub):
    u = h / 14
    fill = C[bg]
    lines = C["teal"] if bg == "ink" else C["hover"]
    body = f'<rect width="{w}" height="{h}" fill="{fill}"/>'
    body += route(w * 0.62, -u, h * 0.42, w + u, u, lines)
    body += route(w * 0.74, -u, h * 0.58, w - 4 * u, u, C["light3"] if bg == "ink" else C["light5"], C["sunlight"])
    body += route(w * 0.86, -u, h * 0.30, w + u, u * 0.6, C["light7"] if bg == "ink" else C["light8"])
    body += lockup(h * 0.15, h * 0.15, round(h * 0.105))
    body += text(h * 0.15, h * 0.56, round(h * 0.125), WHITE, title)
    body += text(h * 0.15, h * 0.70, round(h * 0.052), C["light8"], sub, 400)
    return svg(w, h, body, title)


def promo(index):
    w, h = 680, 440
    bg = ["ink", "teal", "ink", "teal"][index]
    u = 36
    body = f'<rect width="{w}" height="{h}" fill="{C[bg]}"/>'
    body += route(w * 0.58 + index * 18, -u, h * 0.50, w + u, u, C["teal"] if bg == "ink" else C["hover"])
    body += route(w * 0.74 + index * 10, -u, h * 0.72, w - 3 * u, u * 0.7, C["light3"] if bg == "ink" else C["light5"], C["sunlight"])
    return svg(w, h, body, "轻游活动卡")


# PC 装修示例（shop/lilishop-ui/brand/decorate-mock.js 引用）：套餐图按目的地、品牌墙按专区、导航按地区。
REGION_GOODS = [("jp", "日本"), ("kr", "韩国"), ("th", "泰国"), ("eu", "欧洲多国"), ("us", "美国"),
                ("sea", "东南亚"), ("hk", "中国香港"), ("anz", "澳新"), ("global", "全球通用")]


def goods(index, name):
    s, u = 400, 24
    bg = "ink" if index % 2 == 0 else "teal"
    body = f'<rect width="{s}" height="{s}" fill="{C[bg]}"/>'
    body += route(s * 0.56, -u, s * 0.36, s + u, u, C["teal"] if bg == "ink" else C["hover"])
    body += route(s * 0.74, -u, s * 0.52, s - 3 * u, u * 0.7, C["light3"] if bg == "ink" else C["light5"], C["sunlight"])
    body += mark(s * 0.1, s * 0.1, 56, WHITE, C["sunlight"])
    body += text(s * 0.1, s * 0.74, 56, WHITE, name)
    body += text(s * 0.1, s * 0.86, 24, C["light8"], "旅行通信卡 · eSIM / 实体卡", 400)
    return svg(s, s, body, f"{name}旅行通信卡")


BRAND_WALL = [("ink", "日韩专区", "热门套餐，落地即用"), ("teal", "东南亚", "多国通用，一卡走完"),
              ("ink", "欧洲多国", "一卡畅游 33 国"), ("teal", "港澳台", "短途出行必备")]


def floor_banner():
    w, h, u = 480, 1040, 40
    body = f'<rect width="{w}" height="{h}" fill="{C["ink"]}"/>'
    body += route(w * 0.30, -u, h * 0.46, w + u, u, C["teal"])
    body += route(w * 0.56, -u, h * 0.62, w - 3 * u, u * 0.7, C["light3"], C["sunlight"])
    body += route(w * 0.80, -u, h * 0.30, w + u, u * 0.5, C["light7"])
    return svg(w, h, body, "轻游楼层海报")


def hotzone():
    w, h = 1400, 500
    zones = [(3.5, 28.5, "teal", "领券中心", "下单前先领券"), (35.5, 28.5, "ink", "热门目的地", "按天数选购"),
             (67.5, 29, "teal", "续费加包", "原卡续用，无需换卡")]
    body = f'<rect width="{w}" height="{h}" fill="{C["mist"]}"/>'
    for left, width, bg, title, sub in zones:
        x, y, zw, zh = w * left / 100, h * 0.12, w * width / 100, h * 0.76
        body += f'<svg x="{x:.1f}" y="{y:.1f}" width="{zw:.1f}" height="{zh:.1f}"><rect width="100%" height="100%" rx="16" fill="{C[bg]}"/>'
        body += route(zw * 0.62, -24, zh * 0.40, zw + 24, 24, C["teal"] if bg == "ink" else C["hover"], C["sunlight"])
        body += text(32, zh * 0.62, 44, WHITE, title) + text(32, zh * 0.80, 22, C["light8"], sub, 400) + "</svg>"
    return svg(w, h, body, "轻游热区图")


NAV = ["日", "韩", "泰", "新", "港", "欧", "美", "澳", "中东", "全球"]


def nav(label):
    s = 96
    size = 40 if len(label) == 1 else 28
    body = f'<circle cx="48" cy="48" r="48" fill="{C["mist"]}"/>'
    body += (f'<text x="48" y="{48 + size * 0.36:.1f}" text-anchor="middle" font-family="{FONT}" font-size="{size}" '
             f'font-weight="700" fill="{C["teal"]}">{label}</text>')
    return svg(s, s, body, f"{label}地区")


def channel(index):
    shapes = {
        1: mark(48 - 22 * 368.82 / 400, 26, 44, C["teal"], C["sunrise"]),
        2: _stroke("M24 34H72V44C66 44 66 52 72 52V62H24V52C30 52 30 44 24 44Z") + _stroke("M44 34V62", 5),
        3: _stroke("M32 22H58L66 30V74H32Z") + _stroke("M40 44H58V64H40Z", 5),
        4: BENEFITS["note"],
    }
    return svg(96, 96, f'<circle cx="48" cy="48" r="48" fill="{C["mist"]}"/>' + shapes[index], "频道入口")


def category_tpl(index):
    """H5 分类页四种模板的示意线框（管理端「分类页设置」选模板用）。"""
    w, h = 250, 444
    box = lambda x, y, bw, bh, fill, rx=6: f'<rect x="{x}" y="{y}" width="{bw}" height="{bh}" rx="{rx}" fill="{fill}"/>'
    body = f'<rect width="{w}" height="{h}" rx="20" fill="{C["mist"]}"/>' + box(14, 14, w - 28, 28, WHITE, 14)
    if index in (1, 4):
        for k in range(7):
            body += box(14, 56 + k * 40, 56, 30, C["teal"] if k == 0 else WHITE)
    if index == 1:
        for r in range(4):
            for c in range(3):
                body += box(82 + c * 52, 56 + r * 70, 44, 44, WHITE) + box(86 + c * 52, 106 + r * 70, 36, 6, C["light5"], 3)
    elif index == 4:
        for c in range(3):
            body += box(82 + c * 52, 56, 46, 24, C["teal"] if c == 0 else WHITE, 12)
        for r in range(5):
            body += box(82, 94 + r * 64, 154, 54, WHITE)
    else:
        for r in range(2 if index == 2 else 4):
            for c in range(4):
                body += f'<circle cx="{40 + c * 57}" cy="{80 + r * 72}" r="20" fill="{C["teal"] if r == c == 0 else WHITE}"/>'
                body += box(26 + c * 57, 106 + r * 72, 28, 6, C["light5"], 3)
        if index == 2:
            for r in range(4):
                for c in range(3):
                    body += box(20 + c * 72, 220 + r * 44, 64, 30, WHITE, 15)
    return svg(w, h, body, f"分类页模板 {index}")


GRADES = [("mist", C["teal"], C["sunrise"]), ("light3", WHITE, WHITE), ("teal", WHITE, C["sunlight"]), ("ink", WHITE, C["sunlight"])]


def grade(index):
    bg, fg, dot = GRADES[index]
    s = 160
    body = f'<rect width="{s}" height="{s}" rx="36" fill="{C[bg]}"/>' + mark(s / 2 - 44 * 368.82 / 400, 36, 88, fg, dot)
    return svg(s, s, body, f"会员等级 {index + 1}")


GRADE_CARDS = ["mist", "light8", "light7", "light5"]


def grade_card(index):
    w, h, u = 690, 260, 30
    body = f'<rect width="{w}" height="{h}" fill="{C[GRADE_CARDS[index]]}"/>'
    body += route(w * 0.62, -u, h * 0.48, w + u, u, C["light5"] if index < 3 else C["light3"])
    body += route(w * 0.80, -u, h * 0.70, w - 3 * u, u * 0.7, WHITE, C["sunrise"])
    return svg(w, h, body, f"会员等级卡 {index + 1}")


def paid():
    s = 160
    body = f'<circle cx="80" cy="80" r="80" fill="{C["ink"]}"/>' + mark(80 - 40 * 368.82 / 400, 40, 80, WHITE, C["sunlight"])
    return svg(s, s, body, "付费会员")


def _stroke(d, w=8):
    return f'<path d="{d}" fill="none" stroke="{C["teal"]}" stroke-width="{w}" stroke-linecap="butt" stroke-linejoin="miter"/>'


BENEFITS = {
    "badge": mark(48 - 22 * 368.82 / 400, 26, 44, C["teal"], C["sunrise"]),
    "service": _stroke("M26 30H70V58H46L36 68V58H26Z"),
    "note": _stroke("M32 24H64V72H32Z") + _stroke("M40 38H56M40 48H56M40 58H50", 6),
    "points": _stroke("M30 52L48 34L66 52") + _stroke("M30 68L48 50L66 68"),
    "gift": _stroke("M28 44H68V70H28Z") + _stroke("M24 34H72V44H24Z") + _stroke("M48 34V70"),
}


def benefit(key):
    return svg(96, 96, f'<circle cx="48" cy="48" r="48" fill="{C["mist"]}"/>' + BENEFITS[key], "会员权益")


def goods_placeholder():
    s = 400
    body = f'<rect width="{s}" height="{s}" fill="{C["mist"]}"/>' + mark(s / 2 - 70 * 368.82 / 400, 130, 140, C["light5"], C["light5"])
    return svg(s, s, body, "商品图")


SLIDER_BGS = [
    ("teal", "light3", "ink"), ("ink", "teal", "light5"), ("mist", "teal", "light5"),
    ("teal", "ink", "light7"), ("ink", "light3", "teal"), ("mist", "ink", "light3"),
]


def slider_bg(index):
    w, h, u = 300, 150, 14
    bg, a, b = SLIDER_BGS[index]
    body = f'<rect width="{w}" height="{h}" fill="{C[bg]}"/>'
    for k in range(5):
        x = 20 + k * 58 + index * 7
        body += route(x, -u, 30 + ((k * 37 + index * 23) % 90), x + 90, u, C[a if k % 2 else b],
                      C["sunrise"] if bg == "mist" and k == 2 else (C["sunlight"] if k == 2 else None))
    return svg(w, h, body, "验证码背景")


KNOBS = [("top", "right"), ("right", "bottom"), ("left", "top"), ("bottom", "left")]


def slider_piece(index):
    r = 8
    centers = {"top": (30, 12), "right": (48, 30), "bottom": (30, 48), "left": (12, 30)}
    body = '<rect x="12" y="12" width="36" height="36" fill="#FFFFFF"/>'
    body += "".join(f'<circle cx="{centers[k][0]}" cy="{centers[k][1]}" r="{r}" fill="#FFFFFF"/>' for k in KNOBS[index])
    return svg(60, 60, body, "验证码滑块")


def write(rel_dirs, name, content):
    for rel in rel_dirs:
        out = REPO / rel / name
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(content, encoding="utf-8")


def rasterize(content, out, jpg):
    out.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        src, png = Path(tmp) / "a.svg", Path(tmp) / "a.png"
        src.write_text(content, encoding="utf-8")
        subprocess.run(["rsvg-convert", "-o", str(png), str(src)], check=True)
        if jpg:
            subprocess.run(["convert", str(png), "-quality", "88", str(out)], check=True)
        else:
            shutil.copyfile(png, out)


def main():
    decorate = [f"shop/lilishop-ui/{app}/public/liroam/decorate" for app in ("buyer", "manager", "seller")]
    manager_decorate = decorate[1:2]
    for i, (bg, title, sub) in enumerate(BANNERS, 1):
        write(decorate, f"banner-{i}.svg", banner(1050, 420, bg, title, sub))
        if i <= 3:
            write(manager_decorate, f"h5-banner-{i}.svg", banner(750, 332, bg, title, sub))
    for i in range(4):
        write(decorate, f"promo-{i + 1}.svg", promo(i))
    for i, (slug, name) in enumerate(REGION_GOODS):
        write(decorate, f"goods-{slug}.svg", goods(i, name))
    for i, (bg, title, sub) in enumerate(BRAND_WALL, 1):
        write(decorate, f"brand-{i}.svg", banner(600, 300, bg, title, sub))
    for i, label in enumerate(NAV, 1):
        write(decorate, f"nav-{i}.svg", nav(label))
    for i in range(1, 5):
        write(decorate, f"channel-{i}.svg", channel(i))
        write(manager_decorate, f"category-tpl-{i}.svg", category_tpl(i))
    write(decorate, "floor-banner.svg", floor_banner())
    write(decorate, "hotzone.svg", hotzone())
    write(decorate, "video-cover.svg", banner(1280, 720, "ink", "三步开通 eSIM", "下单付款 → 扫码安装 → 落地开网"))

    member = ["shop/lilishop-ui/buyer/public/liroam/member", "shop/lilishop-ui/manager/public/liroam/member"]
    for i in range(4):
        write(member, f"grade-{i + 1}.svg", grade(i))
        write(member, f"grade-card-{i + 1}.svg", grade_card(i))
    write(member, "paid.svg", paid())
    for key in BENEFITS:
        write(member, f"benefit-{key}.svg", benefit(key))

    shared = ["shop/lilishop-ui/manager/public/liroam", "shop/lilishop-ui/seller/public/liroam"]
    write(shared + ["shop/lilishop-ui/buyer/public/liroam"], "goods-placeholder.svg", goods_placeholder())
    app_icon = (LOGO / "svg/liroam-app-icon.svg").read_text(encoding="utf-8")
    write(shared, "store-logo.svg", app_icon)
    write(shared[:1], "avatar-admin.svg", app_icon)

    verification = REPO / "deploy/dev-assets/verification"
    for i in range(len(SLIDER_BGS)):
        rasterize(slider_bg(i), verification / f"bg-{i + 1}.jpg", jpg=True)
    for i in range(len(KNOBS)):
        rasterize(slider_piece(i), verification / f"slider-{i + 1}.png", jpg=False)


if __name__ == "__main__":
    main()
