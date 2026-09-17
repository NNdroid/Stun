package app.fjj.stun.geo

import android.content.res.AssetManager
import java.io.IOException

/**
 * 海岸线资产（`assets/geo/coastline_110m.bin`）的解码器。
 *
 * 数据源：Natural Earth 1:110m land（public domain），生成脚本见 `scripts/geo/gen_coastline.py`，
 * 二进制格式定义写在该脚本头部。资产约 19.5 KB / 128 环 / 5015 顶点。
 *
 * 环顺序被原样保留（外环后紧跟属于它的内环），因此渲染侧把所有环塞进同一个
 * `Path`（`FillType.EVEN_ODD`）即可正确挖洞——数据里唯一的洞是里海。
 *
 * 解码永不抛异常：资产损坏时返回 null，让调用方退化成"只有球体没有海岸线"，
 * 而不是把整个连接详情面板拖崩。
 */
class CoastlineData private constructor(
    /** 每个环的顶点，交错存放 (lon, lat)，单位度；首尾不重复（闭合由渲染侧负责）。 */
    val rings: Array<FloatArray>,
) {
    val ringCount: Int get() = rings.size

    val pointCount: Int get() = rings.sumOf { it.size / 2 }

    companion object {
        const val ASSET_PATH = "geo/coastline_110m.bin"

        private val MAGIC = "STUNGEO1".toByteArray(Charsets.US_ASCII)
        private const val VERSION = 1
        private const val HEADER_SIZE = 20

        private const val MAX_RINGS = 100_000
        private const val MAX_POINTS = 4_000_000

        /** 从 asset 读取并解码；缺失或损坏返回 null。 */
        fun load(assets: AssetManager): CoastlineData? = try {
            assets.open(ASSET_PATH).use { decode(it.readBytes()) }
        } catch (_: IOException) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }

        /** 解码字节流；格式不符返回 null（不抛异常）。 */
        fun decode(bytes: ByteArray): CoastlineData? {
            if (bytes.size < HEADER_SIZE) return null
            for (i in MAGIC.indices) {
                if (bytes[i] != MAGIC[i]) return null
            }
            if (bytes[8].toInt() != VERSION) return null

            val scale = readU16(bytes, 10)
            if (scale <= 0) return null
            val ringCount = readU32(bytes, 12)
            val pointCount = readU32(bytes, 16)
            if (ringCount <= 0 || ringCount > MAX_RINGS) return null
            if (pointCount < 3 || pointCount > MAX_POINTS) return null

            val cursor = Cursor(bytes, HEADER_SIZE)
            val counts = IntArray(ringCount)
            var total = 0
            for (i in 0 until ringCount) {
                val n = cursor.readVarint()
                if (n < 3 || n > pointCount) return null
                counts[i] = n
                total += n
            }
            if (total != pointCount) return null

            val invScale = 1f / scale
            val rings = Array(ringCount) { r ->
                val n = counts[r]
                val lon0 = cursor.readI32() ?: return null
                val lat0 = cursor.readI32() ?: return null
                val flat = FloatArray(n * 2)
                var lon = lon0
                var lat = lat0
                flat[0] = lon * invScale
                flat[1] = lat * invScale
                for (k in 1 until n) {
                    val dLon = cursor.readVarint()
                    val dLat = cursor.readVarint()
                    lon += unzigzag(dLon)
                    lat += unzigzag(dLat)
                    flat[k * 2] = lon * invScale
                    flat[k * 2 + 1] = lat * invScale
                }
                flat
            }
            // 尾部有多余字节说明格式对不上，宁可不要这份数据
            if (cursor.remaining != 0) return null
            return CoastlineData(rings)
        }

        private fun readU16(b: ByteArray, at: Int): Int =
            (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

        private fun readU32(b: ByteArray, at: Int): Int =
            (b[at].toInt() and 0xFF) or
                ((b[at + 1].toInt() and 0xFF) shl 8) or
                ((b[at + 2].toInt() and 0xFF) shl 16) or
                ((b[at + 3].toInt() and 0xFF) shl 24)

        private fun unzigzag(v: Int): Int = (v ushr 1) xor -(v and 1)

        /** 读取游标。越界一律返回 null / 0，由调用方的完整性校验兜底。 */
        private class Cursor(private val b: ByteArray, private var pos: Int) {
            val remaining: Int get() = b.size - pos

            fun readVarint(): Int {
                var result = 0
                var shift = 0
                while (true) {
                    if (pos >= b.size || shift > 28) return 0
                    val byte = b[pos++].toInt() and 0xFF
                    result = result or ((byte and 0x7F) shl shift)
                    if (byte and 0x80 == 0) return result
                    shift += 7
                }
            }

            fun readI32(): Int? {
                if (remaining < 4) return null
                val v = (b[pos].toInt() and 0xFF) or
                    ((b[pos + 1].toInt() and 0xFF) shl 8) or
                    ((b[pos + 2].toInt() and 0xFF) shl 16) or
                    ((b[pos + 3].toInt() and 0xFF) shl 24)
                pos += 4
                return v
            }
        }
    }
}
