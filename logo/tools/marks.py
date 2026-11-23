"""三个概念方向的图形标几何。A 为定稿方向，B / C 只保留为提案记录。"""
import math

import pathops

from geometry import Stroke, circle, difference, filleted_polygon, union, _pt

# 定稿 A「启程」：竖笔落地（L / 出发）→ 圆弧转向 → 斜上起飞 → 留白 → 落点（目的地 / 在线信号）。
MARK_STROKE = 72
MARK_STEM = 196
MARK_TURN_R = 84
MARK_TAKEOFF_ANGLE = 135  # 左转角；落地朝下 90° → 起飞朝 -45°（右上）
MARK_TAKEOFF = 64
MARK_DOT_R = 44
MARK_DOT_GAP = 34  # 起飞线圆头与落点圆之间的净空


def mark_a():
    """返回 (route, dot) 两条路径，配色时分别着色。"""
    s = Stroke(0, 0, 90).line(MARK_STEM).arc(MARK_TURN_R, -MARK_TAKEOFF_ANGLE).line(MARK_TAKEOFF)
    route = s.outline(MARK_STROKE)
    d = MARK_STROKE / 2 + MARK_DOT_GAP + MARK_DOT_R
    dot = circle(_pt(s.pos, d, s.heading), MARK_DOT_R)
    return route, dot


def mark_b():
    """B「卡角地球」：SIM 切角的圆形地球 + 两条纬线，切角处为落点。"""
    globe = circle((200, 200), 180)
    cut = pathops.Path()
    cut.moveTo(160, -10)
    cut.lineTo(420, -10)
    cut.lineTo(420, 250)
    cut.close()
    globe = difference(globe, cut)
    lines = union([
        Stroke(40, 160, 0).line(320).outline(34),
        Stroke(40, 250, 0).line(320).outline(34),
        Stroke(200, 30, 90).line(340).outline(34),
    ])
    route = difference(globe, lines)
    dot = circle((338, 62), 28)
    return route, dot


def mark_c():
    """C「轻翼」：一张卡片折成的纸飞机，尾迹虚线。"""
    wing = filleted_polygon([(20, 220), (400, 40), (250, 380), (190, 260)], [14, 14, 14, 10])
    fold = filleted_polygon([(190, 260), (400, 40), (220, 300)], [4, 4, 4])
    route = difference(wing, fold)
    dot = union([circle((40, 330), 16), circle((92, 360), 16)])
    return route, dot


CONCEPTS = [
    ("A", "启程", "竖笔落地，圆弧转向，斜上起飞，留白后是落点", mark_a),
    ("B", "卡角地球", "SIM 切角的地球，纬线分割，切角处为落点", mark_b),
    ("C", "轻翼", "卡片折成的纸飞机，尾迹为两点", mark_c),
]


def app_tile(size=1024, radius=232, notch=248, notch_r=72):
    """App 图标底板：圆角方形，右上角 SIM 切角。"""
    s = size
    pts = [(0, 0), (s - notch, 0), (s, notch), (s, s), (0, s)]
    return filleted_polygon(pts, [radius, notch_r, notch_r, radius, radius])
