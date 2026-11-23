"""几何底层：中心线（直线 + 圆弧，海龟式链接保证相切）→ 等宽圆头描边的填充轮廓。

圆弧的等距线仍是同心圆弧，所以描边轮廓是精确的，不靠采样；最后用 pathops 并集成干净闭合路径。
坐标系为 SVG 屏幕坐标（y 向下），角度单位为度，0° 指向 +x，顺时针为正。
"""
import math

import pathops
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen


def _pt(c, r, a):
    t = math.radians(a)
    return (c[0] + r * math.cos(t), c[1] + r * math.sin(t))


def arc_to(path, c, r, a0, a1):
    """从当前点（应位于 a0）沿圆弧画到 a1，按 ≤90° 分段转三次贝塞尔。"""
    n = max(1, math.ceil(abs(a1 - a0) / 90 - 1e-9))
    step = (a1 - a0) / n
    for i in range(n):
        s, e = a0 + step * i, a0 + step * (i + 1)
        k = 4 / 3 * math.tan(math.radians(e - s) / 4)
        p0, p3 = _pt(c, r, s), _pt(c, r, e)
        ts, te = math.radians(s), math.radians(e)
        p1 = (p0[0] - k * r * math.sin(ts), p0[1] + k * r * math.cos(ts))
        p2 = (p3[0] + k * r * math.sin(te), p3[1] - k * r * math.cos(te))
        path.cubicTo(*p1, *p2, *p3)


def circle(c, r):
    p = pathops.Path()
    p.moveTo(*_pt(c, r, 0))
    arc_to(p, c, r, 0, 360)
    p.close()
    return p


class Stroke:
    """海龟式中心线：line(长度) / arc(半径, 转角)，转角正 = 屏幕上右转。"""

    def __init__(self, x, y, heading):
        self.pos = (x, y)
        self.heading = heading
        self.segments = []

    def line(self, length):
        h = math.radians(self.heading)
        end = (self.pos[0] + length * math.cos(h), self.pos[1] + length * math.sin(h))
        self.segments.append(("L", self.pos, end))
        self.pos = end
        return self

    def arc(self, r, sweep):
        side = 90 if sweep > 0 else -90
        c = _pt(self.pos, r, self.heading + side)
        a0 = self.heading - side
        a1 = a0 + sweep
        self.segments.append(("A", c, r, a0, a1))
        self.pos = _pt(c, r, a1)
        self.heading += sweep
        return self

    def outline(self, width):
        hw = width / 2
        parts = []
        joints = []
        for seg in self.segments:
            p = pathops.Path()
            if seg[0] == "L":
                (x0, y0), (x1, y1) = seg[1], seg[2]
                d = math.hypot(x1 - x0, y1 - y0)
                nx, ny = -(y1 - y0) / d * hw, (x1 - x0) / d * hw
                p.moveTo(x0 + nx, y0 + ny)
                p.lineTo(x1 + nx, y1 + ny)
                p.lineTo(x1 - nx, y1 - ny)
                p.lineTo(x0 - nx, y0 - ny)
                joints += [seg[1], seg[2]]
            else:
                _, c, r, a0, a1 = seg
                p.moveTo(*_pt(c, r + hw, a0))
                arc_to(p, c, r + hw, a0, a1)
                p.lineTo(*_pt(c, r - hw, a1))
                arc_to(p, c, r - hw, a1, a0)
                joints += [_pt(c, r, a0), _pt(c, r, a1)]
            p.close()
            parts.append(p)
        parts += [circle(j, hw) for j in joints]
        return union(parts)


def union(paths):
    out = pathops.Path()
    for p in paths:
        out = pathops.op(out, p, pathops.PathOp.UNION, fix_winding=True)
    return out


def difference(a, b):
    return pathops.op(a, b, pathops.PathOp.DIFFERENCE)


def transformed(path, matrix):
    out = pathops.Path()
    path.draw(TransformPen(out.getPen(), matrix))
    return out


def translate(path, dx, dy, s=1.0):
    return transformed(path, (s, 0, 0, s, dx, dy))


def bounds(path):
    return path.bounds  # (xmin, ymin, xmax, ymax)


def svg_d(path, nd=2):
    pen = SVGPathPen(None, ntos=lambda v: f"{round(v, nd):g}")
    path.draw(pen)
    return pen.getCommands()


def filleted_polygon(points, radii):
    """凸多边形逐角圆角（radii 与顶点一一对应），返回闭合路径。"""
    n = len(points)
    p = pathops.Path()
    for i in range(n):
        prev, cur, nxt = points[i - 1], points[i], points[(i + 1) % n]
        din = math.atan2(cur[1] - prev[1], cur[0] - prev[0])
        dout = math.atan2(nxt[1] - cur[1], nxt[0] - cur[0])
        turn = math.degrees(dout - din)
        turn = (turn + 180) % 360 - 180
        r = radii[i]
        t = r * math.tan(math.radians(abs(turn)) / 2)
        a = (cur[0] - t * math.cos(din), cur[1] - t * math.sin(din))
        side = 90 if turn > 0 else -90
        c = _pt(a, r, math.degrees(din) + side)
        a0 = math.degrees(din) - side
        if i == 0:
            p.moveTo(*a)
        else:
            p.lineTo(*a)
        arc_to(p, c, r, a0, a0 + turn)
    p.close()
    return p
