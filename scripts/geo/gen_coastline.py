#!/usr/bin/env python3
"""把 Natural Earth 110m 陆地多边形压成地球视图用的紧凑二进制资产。

数据源：Natural Earth 1:110m land（public domain / CC0），GeoJSON 取
https://github.com/nvkelso/natural-earth-vector/blob/master/geojson/ne_110m_land.geojson

为什么是"环"而不是"线段"：地球要填充陆地，而填充需要闭合环 + 奇偶填充规则；
数据集里唯一的洞（里海）作为独立内环保留在同一 Path 里，用 EVEN_ODD 自动挖掉。

关于反经线：本数据集 127 个多边形里只有 1 处 |Δlon| > 180 的跳变，位于南极环
（180 → -180，且两端 lat 都是 -90.0）。lat=-90 在三维里与经度无关、全部塌缩成
同一点，所以这条"缝"投影后长度为 0，天然不存在撕裂。故**不做任何切分**——
切分反而会把南极帽破坏掉。若将来换数据源出现非极区的反经线跳变，渲染侧
GlobeProjection 仍需按"跨半球不补闭合边"兜底。

二进制格式 v1（小端）：
    0   8B  magic "STUNGEO1"
    8   1B  version = 1
    9   1B  flags   = 0
    10  2B  scale   = 1000   # 量化单位/度（即 1e-3 度 ≈ 赤道 111m）
    12  4B  ringCount
    16  4B  pointCount      # 全部环的顶点总数
    20  ... ringCount × varint(环内顶点数)
    ...     每个环：int32 lon0, int32 lat0（绝对量，已乘 scale）
            其后每点：varint(zigzag(Δlon)), varint(zigzag(Δlat))
"""

import json
import struct
import sys
from pathlib import Path

SCALE = 1000
MAGIC = b"STUNGEO1"

SRC = Path(sys.argv[1] if len(sys.argv) > 1 else "/tmp/ne110_land.geojson")
DST = Path(
    sys.argv[2]
    if len(sys.argv) > 2
    else "E:/AndroidStudioProjects/Stun/core/src/main/assets/geo/coastline_110m.bin"
)


def zigzag(v: int) -> int:
    return (v << 1) ^ (v >> 63)


def put_varint(out: bytearray, v: int) -> None:
    while True:
        b = v & 0x7F
        v >>= 7
        if v:
            out.append(b | 0x80)
        else:
            out.append(b)
            return


def main() -> int:
    doc = json.loads(SRC.read_text(encoding="utf-8"))
    if doc.get("type") != "FeatureCollection":
        raise SystemExit("期望 FeatureCollection")

    # 环顺序必须保留：外环 + 紧随其后的内环，奇偶填充才能正确挖洞。
    rings: list[list[tuple[float, float]]] = []
    for feat in doc["features"]:
        geom = feat["geometry"]
        if geom["type"] == "MultiPolygon":
            polys = geom["coordinates"]
        elif geom["type"] == "Polygon":
            polys = [geom["coordinates"]]
        else:
            raise SystemExit(f"未处理的几何类型 {geom['type']}")
        for poly in polys:
            for ring in poly:
                # GeoJSON 首个顶点与末个重复（闭环），丢掉末点省字节；
                # 渲染侧按"闭合环"处理，不依赖这个重复点。
                pts = [(float(x), float(y)) for x, y in ring]
                if len(pts) > 1 and pts[0] == pts[-1]:
                    pts.pop()
                if len(pts) >= 3:
                    rings.append(pts)

    body = bytearray()
    header = bytearray()
    header += MAGIC
    header.append(1)
    header.append(0)
    header += struct.pack("<H", SCALE)
    header += struct.pack("<I", len(rings))
    total_points = sum(len(r) for r in rings)
    header += struct.pack("<I", total_points)

    counts = bytearray()
    for r in rings:
        put_varint(counts, len(r))

    for r in rings:
        lon0 = round(r[0][0] * SCALE)
        lat0 = round(r[0][1] * SCALE)
        body += struct.pack("<ii", lon0, lat0)
        prev_lon, prev_lat = lon0, lat0
        for lon, lat in r[1:]:
            q_lon = round(lon * SCALE)
            q_lat = round(lat * SCALE)
            put_varint(body, zigzag(q_lon - prev_lon))
            put_varint(body, zigzag(q_lat - prev_lat))
            prev_lon, prev_lat = q_lon, q_lat

    blob = bytes(header) + bytes(counts) + bytes(body)
    DST.parent.mkdir(parents=True, exist_ok=True)
    DST.write_bytes(blob)

    lons = [p[0] for r in rings for p in r]
    lats = [p[1] for r in rings for p in r]
    print(f"写出 {DST}")
    print(f"  环数 {len(rings)} / 顶点 {total_points}")
    print(f"  字节 {len(blob)} ({len(blob)/1024:.1f} KB)"
          f"  header {len(header)}B + counts {len(counts)}B + body {len(body)}B")
    print(f"  bbox lon[{min(lons):.2f},{max(lons):.2f}] lat[{min(lats):.2f},{max(lats):.2f}]")
    print(f"  平均 {len(body)/max(total_points,1):.2f} B/点")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
