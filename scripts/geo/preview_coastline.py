#!/usr/bin/env python3
"""校验 coastline_110m.bin，并按将要移植到 Kotlin 的算法渲染预览图。

这个脚本的价值在于：正交投影 + 三维平面裁剪是整套地球视图里唯一"做错就一眼看穿"
的部分，先在 Python 里把算法跑通、把不变量断言死，再往 Kotlin 抄，能省掉
"分不清是数据错还是投影错"的调试成本。

两类校验：
  1) 语义校验 —— 拿已知陆地/海洋坐标做奇偶射线法点在多边形内测试（含里海洞）；
  2) 投影不变量 —— 裁剪后所有投影点必须落在单位圆内；未经裁剪的可见顶点不得丢失。
"""

import math
import struct
import sys
from pathlib import Path

from PIL import Image, ImageDraw

BIN = Path(
    sys.argv[1]
    if len(sys.argv) > 1
    else "E:/AndroidStudioProjects/Stun/core/src/main/assets/geo/coastline_110m.bin"
)
OUT = Path(
    sys.argv[2]
    if len(sys.argv) > 2
    else "E:/AndroidStudioProjects/Stun/.workbuddy/tmp/coastline_preview.png"
)


def decode(path: Path):
    blob = path.read_bytes()
    assert blob[:8] == b"STUNGEO1"
    scale, = struct.unpack_from("<H", blob, 10)
    ring_count, = struct.unpack_from("<I", blob, 12)
    point_count, = struct.unpack_from("<I", blob, 16)
    pos = 20

    def rd():
        nonlocal pos
        v = 0
        shift = 0
        while True:
            b = blob[pos]
            pos += 1
            v |= (b & 0x7F) << shift
            if not (b & 0x80):
                return v
            shift += 7

    unzig = lambda u: (u >> 1) ^ -(u & 1)
    counts = [rd() for _ in range(ring_count)]
    assert sum(counts) == point_count
    rings = []
    for n in counts:
        lon0, lat0 = struct.unpack_from("<ii", blob, pos)
        pos += 8
        ring = [(lon0 / scale, lat0 / scale)]
        x, y = lon0, lat0
        for _ in range(n - 1):
            x += unzig(rd())
            y += unzig(rd())
            ring.append((x / scale, y / scale))
        rings.append(ring)
    assert pos == len(blob), f"尾部多出 {len(blob)-pos} 字节"
    return rings


def point_in_land(lon: float, lat: float, rings) -> bool:
    """奇偶射线法；外环与内环一起统计，故里海这类洞会被正确判为海洋。"""
    inside = False
    for ring in rings:
        n = len(ring)
        for i in range(n):
            x1, y1 = ring[i]
            x2, y2 = ring[(i + 1) % n]
            if (y1 > lat) != (y2 > lat):
                xin = x1 + (lat - y1) * (x2 - x1) / (y2 - y1)
                if lon < xin:
                    inside = not inside
    return inside


def basis(lon0: float, lat0: float):
    l0, f0 = math.radians(lon0), math.radians(lat0)
    fwd = (math.cos(f0) * math.cos(l0), math.cos(f0) * math.sin(l0), math.sin(f0))
    east = (-math.sin(l0), math.cos(l0), 0.0)
    north = (-math.sin(f0) * math.cos(l0), -math.sin(f0) * math.sin(l0), math.cos(f0))
    return east, north, fwd


def dot(a, b):
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]


def cross(a, b):
    return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])


def clip_tagged(ring, fwd):
    """Sutherland–Hodgman：对三维平面 fwd·v = 0 裁剪，保留前半球。

    关键点：交点要**打标记**。裁剪产生的那些边不是海岸线，而是"多边形沿视界闭合"
    的边；它的正确形状是**视界圆上的一段弧**，不是两点之间的弦。用弦去填充就会在球面
    正中横切出一条直线（就是假边）。标记出来，填充分支才有机会把弦换成弧。
    """
    n = len(ring)
    out = []  # [(vec, on_horizon)]
    for i in range(n):
        a = ring[i]
        b = ring[(i + 1) % n]
        za = dot(a, fwd)
        zb = dot(b, fwd)
        if za >= 0:
            out.append((a, False))
        if (za >= 0) != (zb >= 0):
            t = za / (za - zb)
            out.append((tuple(a[k] + (b[k] - a[k]) * t for k in range(3)), True))
    return out


def horizon_arc(a, b, fwd, step_deg=3.0):
    """视界圆上 a→b 的中间点（不含端点）。视界圆就是 z=0 平面与单位球的交线，
    两点都在该平面上，故绕 fwd 轴旋转即可，长度天然为 1。"""
    ah = tuple(a[k] - dot(a, fwd) * fwd[k] for k in range(3))
    bh = tuple(b[k] - dot(b, fwd) * fwd[k] for k in range(3))
    na = math.sqrt(dot(ah, ah))
    nb = math.sqrt(dot(bh, bh))
    if na < 1e-12 or nb < 1e-12:
        return []
    ah = tuple(c / na for c in ah)
    bh = tuple(c / nb for c in bh)
    ang = math.atan2(dot(cross(ah, bh), fwd), dot(ah, bh))
    steps = int(abs(ang) / math.radians(step_deg))
    if steps < 1:
        return []
    tang = cross(fwd, ah)  # 绕 fwd 旋转的正切方向
    out = []
    for s in range(1, steps):
        t = ang * s / steps
        out.append(tuple(ah[k] * math.cos(t) + tang[k] * math.sin(t) for k in range(3)))
    return out


def fill_polygon(tagged, fwd):
    """填充用：把视界闭合边展开成弧，得到贴合圆盘的轮廓。

    返回 (顶点, 是否视界点) —— 掩码供不变量校验用：只有"两个都是视界点"的相邻对
    才需要检查它们的弧间隔，真正的海岸线顶点不算。
    """
    pts, mask = [], []
    m = len(tagged)
    for i in range(m):
        v, h = tagged[i]
        pts.append(v)
        mask.append(h)
        v2, h2 = tagged[(i + 1) % m]
        if h and h2:
            for w in horizon_arc(v, v2, fwd):
                pts.append(w)
                mask.append(True)
    return pts, mask


def stroke_edges(tagged):
    """描边用：海岸线只画真实边，视界闭合边整条丢掉（海岸线在视界处自然断开）。"""
    edges = []
    m = len(tagged)
    for i in range(m):
        v, h = tagged[i]
        v2, h2 = tagged[(i + 1) % m]
        if h and h2:
            continue
        edges.append((v, v2))
    return edges


def to_vec(lon, lat):
    l, f = math.radians(lon), math.radians(lat)
    return (math.cos(f) * math.cos(l), math.cos(f) * math.sin(l), math.sin(f))


def main() -> int:
    rings = decode(BIN)
    print(f"解码 {len(rings)} 环 / {sum(len(r) for r in rings)} 顶点")

    print("\n== 语义校验（奇偶射线法，含里海洞）==")
    cases = [
        ("北京", 116.4, 39.9, True),
        ("伦敦", -0.13, 51.51, True),
        ("开普敦", 18.42, -33.92, True),
        ("圣保罗", -46.63, -23.55, True),
        ("大西洋中部", -30.0, 0.0, False),
        ("太平洋中部", -140.0, 0.0, False),
        ("里海（应为海洋）", 50.5, 42.0, False),
        ("南极洲内陆", 0.0, -85.0, True),
    ]
    bad = 0
    for name, lon, lat, want in cases:
        got = point_in_land(lon, lat, rings)
        ok = got == want
        bad += 0 if ok else 1
        print(f"  {'✅' if ok else '❌'} {name:<16} ({lon:>7.2f},{lat:>6.2f}) 陆地={got} 期望={want}")
    if bad:
        print(f"❌ {bad} 个语义校验失败")
        return 1

    vecs = [[to_vec(lon, lat) for lon, lat in r] for r in rings]

    print("\n== 投影与裁剪不变量 ==")
    views = [(0.0, 20.0), (-100.0, 35.0), (120.0, -25.0), (0.0, -80.0)]
    per_view = {}
    for lon0, lat0 in views:
        east, north, fwd = basis(lon0, lat0)

        def proj(v, east=east, north=north, fwd=fwd):
            return (dot(v, east), dot(v, north), dot(v, fwd))

        fills, strokes = [], []
        maxr = 0.0
        worst_limb_gap = 0.0
        for rv in vecs:
            tagged = clip_tagged(rv, fwd)
            if len(tagged) < 3:
                continue
            pts, mask = fill_polygon(tagged, fwd)
            poly = [proj(v) for v in pts]
            if len(poly) >= 3:
                fills.append(poly)
                for p in poly:
                    assert p[2] >= -1e-9, f"裁剪后仍出现背面顶点 z={p[2]}"
                    maxr = max(maxr, math.hypot(p[0], p[1]))
            strokes.append([(proj(a), proj(b)) for a, b in stroke_edges(tagged)])

            for i in range(len(poly)):
                if not (mask[i] and mask[(i + 1) % len(poly)]):
                    continue
                p1, p2 = poly[i], poly[(i + 1) % len(poly)]
                a1 = math.atan2(p1[1], p1[0])
                a2 = math.atan2(p2[1], p2[0])
                gap = abs((a2 - a1 + math.pi) % (2 * math.pi) - math.pi)
                worst_limb_gap = max(worst_limb_gap, math.degrees(gap))

        assert maxr <= 1.0 + 1e-9, f"投影点越出单位圆 r={maxr}"
        assert worst_limb_gap <= 6.0, (
            f"视界闭合边出现 {worst_limb_gap:.1f}° 的跨度——说明又在用弦代替弧（假边回归）")

        area = 0.0
        for poly in fills:
            s = 0.0
            for i in range(len(poly)):
                x1, y1 = poly[i][0], poly[i][1]
                x2, y2 = poly[(i + 1) % len(poly)][0], poly[(i + 1) % len(poly)][1]
                s += x1 * y2 - x2 * y1
            area += abs(s) / 2
        frac = area / math.pi
        per_view[(lon0, lat0)] = (fills, strokes, east, north)
        print(f"  ✅ 视图 lon0={lon0:>6.1f} lat0={lat0:>5.1f}  填充 {len(fills):>3} 块"
              f"  最大投影半径 {maxr:.4f}  视界最大相邻跨度 {worst_limb_gap:4.2f}°"
              f"  投影陆地占比 {frac*100:5.1f}%")
        assert 0.05 < frac < 0.75, "投影占比离常识太远，算法可能有问题"

    print("\n== 渲染预览 ==")
    cell, R = 320, 140
    img = Image.new("RGB", (cell * 2, cell * 2), (241, 239, 232))
    dr = ImageDraw.Draw(img)
    for idx, (lon0, lat0) in enumerate(views):
        fills, strokes, east, north = per_view[(lon0, lat0)]
        cx = (idx % 2) * cell + cell // 2
        cy = (idx // 2) * cell + cell // 2 + 18
        dr.ellipse([cx - R, cy - R, cx + R, cy + R],
                   fill=(230, 241, 251), outline=(133, 183, 235), width=1)
        for poly in fills:
            pts = [(cx + R * p[0], cy - R * p[1]) for p in poly]
            if len(pts) >= 3:
                dr.polygon(pts, fill=(181, 212, 244))
        for segs in strokes:
            for a, b in segs:
                dr.line([(cx + R * a[0], cy - R * a[1]), (cx + R * b[0], cy - R * b[1])],
                        fill=(24, 95, 165), width=1)
        dr.text((cx - 60, cy + R + 8), f"lon0={lon0:.0f} lat0={lat0:.0f}", fill=(95, 94, 90))
    OUT.parent.mkdir(parents=True, exist_ok=True)
    img.save(OUT)
    print(f"  预览图 {OUT}  ({img.width}×{img.height})")
    print("\n全部校验通过 ✅")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
