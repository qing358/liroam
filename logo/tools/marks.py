"""图形标几何。三个变体共用一套网格：单位 u = 笔宽；只用 90° 与 45°；两端平切。

主笔画像线路图上的一段线：竖向落下，转角，水平前行；留 1u 空白后是站点圆点（目的地）。
定稿为 V2「折角」，V1 / V3 只为提案图保留。
"""
from geometry import Stroke, circle, filleted_polygon, polyline_outline, union

U = 72              # 笔宽
STEM_TOP = 0
BASE = 5.5 * U      # 水平段中心线高度（外缘落在 6u）
STEM_X = 0.5 * U    # 竖笔中心线
FOOT_END = 3.5 * U  # 水平段平切处
GAP = 1.0 * U
DOT_R = 0.53 * U    # 直径 = 笔宽再加 6% 圆形视觉补偿，上下缘与水平段基本齐平


def _dot():
    return circle((FOOT_END + GAP + DOT_R, BASE), DOT_R)


def mark_right_angle():
    """V1「直角」：竖笔与水平段直角相接。"""
    route = polyline_outline([(STEM_X, STEM_TOP), (STEM_X, BASE), (FOOT_END, BASE)], U)
    return route, _dot()


def mark_chamfer():
    """V2「折角」：转角为 45° 折线，切角与 App 图标的 SIM 切角同一角度。"""
    c = 1.0 * U  # 折线在水平 / 竖直方向各占 1u
    route = polyline_outline(
        [(STEM_X, STEM_TOP), (STEM_X, BASE - c), (STEM_X + c, BASE), (FOOT_END, BASE)], U)
    return route, _dot()


def mark_quadrant():
    """V3「象限」：转角为正四分之一圆，中心线半径 1u。"""
    r = 1.0 * U
    s = (Stroke(STEM_X, STEM_TOP, 90).line(BASE - r - STEM_TOP).arc(r, -90)
         .line(FOOT_END - STEM_X - r))
    return s.outline(U, cap="butt"), _dot()


mark_main = mark_chamfer

VARIANTS = [
    ("V1", "直角", "直角转折，最硬朗；与通用「L」字母标难以区分", mark_right_angle),
    ("V2", "折角", "45° 折角，与 SIM 切角同角度；线路图语言，推荐", mark_chamfer),
    ("V3", "象限", "四分之一圆转角，最柔和；偏通用电信感", mark_quadrant),
]


def app_tile(size=1024, radius=228, notch=236, notch_r=28):
    """App 图标底板：圆角方形，右上角 45° SIM 切角。"""
    s = size
    pts = [(0, 0), (s - notch, 0), (s, notch), (s, s), (0, s)]
    return filleted_polygon(pts, [radius, notch_r, notch_r, radius, radius])
