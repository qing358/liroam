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
    decorate = ["shop/lilishop-ui/buyer/public/liroam/decorate", "shop/lilishop-ui/manager/public/liroam/decorate"]
    for i, (bg, title, sub) in enumerate(BANNERS, 1):
        write(decorate, f"banner-{i}.svg", banner(1050, 420, bg, title, sub))
        write(decorate[1:], f"h5-banner-{i}.svg", banner(750, 332, bg, title, sub))
    for i in range(4):
        write(decorate, f"promo-{i + 1}.svg", promo(i))

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
