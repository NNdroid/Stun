"""从 sapics city mmdb 生成 CountryCentroids.kt 里的"国家/地区 → 代表坐标"表。

用法（需要官方 maxminddb 包）：

    python scripts/geo/gen_country_centroids.py \
        <city-ipv4.mmdb> <city-ipv6.mmdb> \
        core/src/main/java/app/fjj/stun/geo/CountryCentroids.kt

为什么用**"该国家出现最频繁的坐标"**而不是几何质心：
这张表只在"记录里有 country_code 但缺经纬度"（例如换用 country-only 库）时才兜底用。
对一个"把服务器画到地球上"的展示来说，最频繁出现的坐标落在该国的机房聚集地
（US→Ashburn、NL→Amsterdam 这类），比被海外领地拉偏的几何质心更贴近现实。

实现细节：同一坐标先按 3 位小数归并（≈100 m）再计数，避免把相邻街区的微差算成不同点，
也让内存从"每个网络一条"降到"每个国家几千条"。
"""

import sys
from collections import Counter

try:
    import maxminddb
except ImportError:  # pragma: no cover
    sys.exit("需要 maxminddb：pip install maxminddb")

COORD_PRECISION = 3


def collect(path: str, counter: dict[str, Counter]) -> int:
    seen = 0
    with maxminddb.open_database(path) as reader:
        for _network, record in reader:
            if not record:
                continue
            code = record.get("country_code")
            latitude = record.get("latitude")
            longitude = record.get("longitude")
            # 只收真实国家/地区码：两个字母。A1/A2/O1 这类匿名代理伪码不画点。
            if not code or len(code) != 2 or not code.isalpha():
                continue
            if latitude is None or longitude is None:
                continue
            key = (round(float(latitude), COORD_PRECISION), round(float(longitude), COORD_PRECISION))
            counter.setdefault(code.upper(), Counter())[key] += 1
            seen += 1
    return seen


def render(counter: dict[str, Counter]) -> list[str]:
    entries = []
    for code in sorted(counter):
        # 出现次数最多的坐标；并列时取纬度/经度较小者，保证同一份库每次生成结果一致
        key = min(counter[code].items(), key=lambda kv: (-kv[1], kv[0]))[0]
        body = ",".join((code, f"{key[0]:g}", f"{key[1]:g}"))
        entries.append(body)
    return entries


def main() -> None:
    if len(sys.argv) != 4:
        sys.exit(__doc__.strip().splitlines()[2].strip())
    v4_path, v6_path, out_path = sys.argv[1], sys.argv[2], sys.argv[3]

    counter: dict[str, Counter] = {}
    total = collect(v4_path, counter) + collect(v6_path, counter)
    entries = render(counter)

    lines, current = [], ""
    for entry in entries:
        candidate = entry if not current else f"{current};{entry}"
        if len(candidate) > 110:
            lines.append(current)
            current = entry
        else:
            current = candidate
    if current:
        lines.append(current)

    chunks = "\n".join(f'        "{line}",' for line in lines)

    with open(out_path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(f'''package app.fjj.stun.geo

/**
 * **自动生成，别手改** —— 由 `scripts/geo/gen_country_centroids.py` 从 city 库导出。
 *
 * 用途只有一个：当某条记录**带 country_code 但没有经纬度**时兜底给一个点
 * （比如换成 country-only 库）。因此它不是"国家几何质心"，而是
 * **该国家在库里出现最频繁的坐标** —— 对"把服务器画到地球上"这个用途更贴近现实。
 *
 * 数据量：{len(entries)} 个国家/地区，来自 {total} 条带坐标的记录。
 */
internal object CountryCentroids {{

    /** `国家码,纬度,经度`，以 `;` 分隔。 */
    private val PACKED: List<String> = listOf(
{chunks}
    )

    private val table: Map<String, DoubleArray> by lazy {{
        val result = HashMap<String, DoubleArray>(PACKED.size * 12)
        PACKED.forEach {{ line ->
            line.split(';').forEach {{ entry ->
                val parts = entry.split(',')
                if (parts.size == 3) {{
                    val latitude = parts[1].toDoubleOrNull()
                    val longitude = parts[2].toDoubleOrNull()
                    if (latitude != null && longitude != null) {{
                        result[parts[0]] = doubleArrayOf(latitude, longitude)
                    }}
                }}
            }}
        }}
        result
    }}

    /** 取 [countryCode] 的代表坐标；未知国家返回 null。 */
    fun of(countryCode: String?): DoubleArray? {{
        val code = countryCode?.trim()?.uppercase() ?: return null
        if (code.length != 2) return null
        return table[code]
    }}

    /** 表里收录了多少个国家/地区（自检用）。 */
    val size: Int get() = table.size

    /** 表里收录的国家/地区码，返回不可变副本（自检用）。 */
    val codes: Set<String> get() = table.keys.toSet()
}}
''')

    print(f"收到 {total} 条带坐标记录，覆盖 {len(entries)} 个国家/地区 -> {out_path}")


if __name__ == "__main__":
    main()
