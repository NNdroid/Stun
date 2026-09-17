package app.fjj.stun.geo

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 地球视图的几何内核：正交投影 + 三维平面裁剪。
 *
 * 纯数学，不依赖 Android（`Path` 由调用方通过 [forEachFillVertex] / [forEachStrokeEdge]
 * 这类回调式 API 自行填充），因此可以在普通 JVM 单测里直接跑，不需要 Robolectric。
 *
 * 对 `app` 模块公开：`GlobeView` 住在 app 模块，core 的 `internal` 对它不可见。
 *
 * ## 为什么必须做三维裁剪，而不是逐顶点判 `z > 0`
 * 逐顶点判可见的写法，会把"视界两侧的两个相邻顶点"直接连成一条线段，在球面正中横切出
 * 一条弦（假边）——海岸线凭空多一道直线。正确做法是把整个环对平面 `v·forward = 0`
 * 做 Sutherland–Hodgman 裁剪，让交点精确落在视界上。
 *
 * ## 为什么裁剪交点还要额外打标记
 * 裁剪产生的闭合边并不在球面上：它是"多边形沿视界被切断"的那条边，正确形状是
 * **视界圆上的一段弧**。若当普通边去连，投影后仍然是一条弦（同一种假边）。所以交点带
 * [ClipBuffer] 的 horizon 标记，两条分支各取所需：
 * - 填充：把弦换成沿视界圆的弧（[forEachFillVertex]）；
 * - 描边：整条边丢掉——海岸线在视界处应当自然断开，不该沿视界画一圈（[forEachStrokeEdge]）。
 */
object GlobeProjection {

    /** 俯仰（相机纬度）夹取上限：越过它球体会转到"倒立"，交互上无意义。 */
    const val MAX_PITCH_DEG = 60f

    /** 视界弧的采样步长（度）。3° 在 240dp 球上已看不出多边形感。 */
    @PublishedApi
    internal const val HORIZON_STEP_DEG = 3f

    /** 大圆弧（节点连线）的采样步长（度）。 */
    @PublishedApi
    internal const val ARC_STEP_DEG = 2f

    /**
     * 相机正交基：`x→东`，`y→北`，`z→朝观察者`。
     * 球面点投影到盘面即为 `(v·east, v·north, v·forward)`，`forward > 0` 为可见半球。
     * 三者构成右手系（`east × north = forward`）。
     */
    class Basis {
        var xx = 0f; var xy = 0f; var xz = 0f
        var yx = 0f; var yy = 0f; var yz = 0f
        var zx = 0f; var zy = 0f; var zz = 0f

        /** 以球面点 (lonDeg, latDeg) 为盘面正中重建基。 */
        fun set(lonDeg: Float, latDeg: Float) {
            val l = Math.toRadians(lonDeg.toDouble())
            val f = Math.toRadians(latDeg.toDouble())
            val cl = cos(l); val sl = sin(l)
            val cf = cos(f); val sf = sin(f)
            xx = (-sl).toFloat(); xy = cl.toFloat(); xz = 0f
            yx = (-sf * cl).toFloat(); yy = (-sf * sl).toFloat(); yz = cf.toFloat()
            zx = (cf * cl).toFloat(); zy = (cf * sl).toFloat(); zz = sf.toFloat()
        }
    }

    /** 裁剪缓冲：三维顶点（单位向量）+ 视界标记。复用同一实例即可让绘制期零分配。 */
    class ClipBuffer(capacity: Int = 1024) {
        var size = 0
        var xs = FloatArray(capacity)
        var ys = FloatArray(capacity)
        var zs = FloatArray(capacity)
        var horizon = BooleanArray(capacity)

        fun ensure(capacity: Int) {
            if (capacity <= xs.size) return
            var n = xs.size
            while (n < capacity) n = n shl 1
            xs = xs.copyOf(n); ys = ys.copyOf(n); zs = zs.copyOf(n); horizon = horizon.copyOf(n)
        }

        fun clear() {
            size = 0
        }

        fun add(x: Float, y: Float, z: Float, isHorizon: Boolean) {
            ensure(size + 1)
            xs[size] = x; ys[size] = y; zs[size] = z; horizon[size] = isHorizon
            size++
        }
    }

    // ── 基础几何 ────────────────────────────────────────────────────────────

    /** 经纬度（度）→ 单位球面向量，写入 [out] 的 (offset, offset+1, offset+2)。 */
    fun unitVector(lonDeg: Float, latDeg: Float, out: FloatArray, offset: Int = 0) {
        val l = Math.toRadians(lonDeg.toDouble())
        val f = Math.toRadians(latDeg.toDouble())
        val cf = cos(f)
        out[offset] = (cf * cos(l)).toFloat()
        out[offset + 1] = (cf * sin(l)).toFloat()
        out[offset + 2] = sin(f).toFloat()
    }

    fun unitVector(lonDeg: Float, latDeg: Float): FloatArray =
        FloatArray(3).also { unitVector(lonDeg, latDeg, it, 0) }

    /**
     * 球面点 → 盘面坐标，写入 [out] 的三元组：`[0]=右, [1]=上, [2]=朝观察者`。
     * `out[2] > 0` 表示位于可见半球。单位与球半径无关（半径由调用方乘）。
     */
    fun project(x: Float, y: Float, z: Float, b: Basis, out: FloatArray, offset: Int = 0) {
        out[offset] = x * b.xx + y * b.xy + z * b.xz
        out[offset + 1] = x * b.yx + y * b.yy + z * b.yz
        out[offset + 2] = x * b.zx + y * b.zy + z * b.zz
    }

    /**
     * 两单位向量间的大圆插值（slerp），写入 [out]（每点 3 个 float，共 `steps+1` 点）。
     * 返回实际写入的点数；两向量近似同向时退化为复制。
     * [out] 至少需要 `(steps + 1) * 3` 个 float。
     */
    fun slerp(a: FloatArray, b: FloatArray, steps: Int, out: FloatArray): Int {
        val dot = (a[0] * b[0] + a[1] * b[1] + a[2] * b[2]).coerceIn(-1f, 1f)
        val omega = Math.acos(dot.toDouble())
        val n = steps.coerceAtLeast(1)
        val so = sin(omega)
        // 同向（omega≈0）或反向（omega≈π，sin→0）时 slerp 退化，用线性插值再归一化兜底；
        // 反向时大圆不唯一，取任意一条都比产生 NaN 好。
        if (so < 1e-6) {
            for (i in 0..n) {
                val t = i.toFloat() / n
                val x = a[0] + (b[0] - a[0]) * t
                val y = a[1] + (b[1] - a[1]) * t
                val z = a[2] + (b[2] - a[2]) * t
                val len = sqrt(x * x + y * y + z * z)
                if (len < 1e-6f) {
                    out[i * 3] = a[0]; out[i * 3 + 1] = a[1]; out[i * 3 + 2] = a[2]
                } else {
                    out[i * 3] = x / len; out[i * 3 + 1] = y / len; out[i * 3 + 2] = z / len
                }
            }
            return n + 1
        }
        for (i in 0..n) {
            val t = i.toDouble() / n
            val wa = sin((1 - t) * omega) / so
            val wb = sin(t * omega) / so
            out[i * 3] = (wa * a[0] + wb * b[0]).toFloat()
            out[i * 3 + 1] = (wa * a[1] + wb * b[1]).toFloat()
            out[i * 3 + 2] = (wa * a[2] + wb * b[2]).toFloat()
        }
        return n + 1
    }

    /** 单位向量间的夹角（度）。 */
    fun angleDeg(a: FloatArray, b: FloatArray): Float {
        val dot = (a[0] * b[0] + a[1] * b[1] + a[2] * b[2]).coerceIn(-1f, 1f)
        return Math.toDegrees(Math.acos(dot.toDouble())).toFloat()
    }

    // ── 裁剪 ────────────────────────────────────────────────────────────────

    /**
     * Sutherland–Hodgman：把 [src]（x,y,z 交错、首尾不重复的闭合环）对 `v·forward = 0`
     * 裁剪，保留前半球部分写入 [out]（调用方负责先 [ClipBuffer.clear]）。
     * 裁剪产生的交点标记为 horizon。
     */
    fun clip(src: FloatArray, b: Basis, out: ClipBuffer) {
        out.clear()
        val n = src.size / 3
        if (n < 3) return
        for (i in 0 until n) {
            val ax = src[i * 3]; val ay = src[i * 3 + 1]; val az = src[i * 3 + 2]
            val j = if (i + 1 == n) 0 else i + 1
            val bx = src[j * 3]; val by = src[j * 3 + 1]; val bz = src[j * 3 + 2]
            val za = ax * b.zx + ay * b.zy + az * b.zz
            val zb = bx * b.zx + by * b.zy + bz * b.zz
            if (za >= 0f) out.add(ax, ay, az, false)
            if ((za >= 0f) != (zb >= 0f)) {
                val t = za / (za - zb)
                out.add(
                    ax + (bx - ax) * t,
                    ay + (by - ay) * t,
                    az + (bz - az) * t,
                    true,
                )
            }
        }
    }

    /**
     * 遍历"填充轮廓"顶点：在视界闭合边上插入沿视界圆的中间点，让填充贴合圆盘而不是切一条弦。
     * 收到 (x, y, z, isHorizon)。`inline` 是为了绘制期不产生 lambda 分配。
     */
    inline fun forEachFillVertex(
        c: ClipBuffer,
        b: Basis,
        onVertex: (Float, Float, Float, Boolean) -> Unit,
    ) {
        val n = c.size
        if (n < 3) return
        for (i in 0 until n) {
            val j = if (i + 1 == n) 0 else i + 1
            onVertex(c.xs[i], c.ys[i], c.zs[i], c.horizon[i])
            if (c.horizon[i] && c.horizon[j]) {
                forEachHorizonArc(c.xs[i], c.ys[i], c.zs[i], c.xs[j], c.ys[j], c.zs[j], b, onVertex)
            }
        }
    }

    /**
     * 视界圆上 a→b 的中间点（不含端点）。
     *
     * a、b 都在 `v·forward = 0` 平面上（裁剪交点必然满足），该平面与单位球交于视界圆，
     * 所以绕 forward 轴旋转即可得到弧上的点，模长天然保持 1。
     */
    inline fun forEachHorizonArc(
        ax: Float, ay: Float, az: Float,
        bx: Float, by: Float, bz: Float,
        b: Basis,
        onVertex: (Float, Float, Float, Boolean) -> Unit,
    ) {
        // 去掉 forward 分量并归一化（交点数值上已近似在平面上，这里只做稳健化）
        val da = ax * b.zx + ay * b.zy + az * b.zz
        val db = bx * b.zx + by * b.zy + bz * b.zz
        var ux = ax - da * b.zx; var uy = ay - da * b.zy; var uz = az - da * b.zz
        var vx = bx - db * b.zx; var vy = by - db * b.zy; var vz = bz - db * b.zz
        val nu = sqrt(ux * ux + uy * uy + uz * uz)
        val nv = sqrt(vx * vx + vy * vy + vz * vz)
        if (nu < 1e-6f || nv < 1e-6f) return
        ux /= nu; uy /= nu; uz /= nu
        vx /= nv; vy /= nv; vz /= nv

        val c0 = ux * vx + uy * vy + uz * vz
        val cx = uy * vz - uz * vy
        val cy = uz * vx - ux * vz
        val cz = ux * vy - uy * vx
        val s0 = cx * b.zx + cy * b.zy + cz * b.zz
        val ang = atan2(s0.toDouble(), c0.toDouble()).toFloat()

        val steps = (abs(ang) / Math.toRadians(HORIZON_STEP_DEG.toDouble())).toInt()
        if (steps < 1) return
        // tangent = forward × u（绕 forward 旋转的正切方向）
        val tx = b.zy * uz - b.zz * uy
        val ty = b.zz * ux - b.zx * uz
        val tz = b.zx * uy - b.zy * ux
        for (s in 1 until steps) {
            val t = ang * s / steps
            val ct = cos(t.toDouble()).toFloat()
            val st = sin(t.toDouble()).toFloat()
            onVertex(ux * ct + tx * st, uy * ct + ty * st, uz * ct + tz * st, true)
        }
    }

    /**
     * 遍历"海岸线"边：跳过视界闭合边。收到 (ax, ay, az, bx, by, bz)，调用方据此
     * moveTo/lineTo（首点）或 lineTo（续接）。
     */
    inline fun forEachStrokeEdge(
        c: ClipBuffer,
        onEdge: (Float, Float, Float, Float, Float, Float) -> Unit,
    ) {
        val n = c.size
        if (n < 3) return
        for (i in 0 until n) {
            val j = if (i + 1 == n) 0 else i + 1
            if (c.horizon[i] && c.horizon[j]) continue
            onEdge(c.xs[i], c.ys[i], c.zs[i], c.xs[j], c.ys[j], c.zs[j])
        }
    }

    /** [c] 是否完全位于可见半球（无任何视界交点）——此时环可当作闭合多边形处理。 */
    fun fullyVisible(c: ClipBuffer): Boolean {
        for (i in 0 until c.size) {
            if (c.horizon[i]) return false
        }
        return true
    }

    /** [c] 是否完全不可见（裁剪后顶点不足以构成面）——调用方应直接跳过该环。 */
    fun invisible(c: ClipBuffer): Boolean = c.size < 3

    /**
     * 把大圆弧 a→b **按参数顺序**采样进 [out]（每点 3 个 float，单位向量），返回点数。
     *
     * 与 [forEachArcVertex] 的区别：这里**不判可见性**，连背面段一起交出来。沿弧跑动的
     * 东西（连接详情里的彗星头，见 `GlobeView.drawComet`）需要按"参数位置"取点，
     * 而且需要知道头到底转到背面没有 —— 只拿到可见点就没法做这种判断了。
     *
     * 步长 [ARC_STEP_DEG]，点数上限 181。所以 [out] 至少 `181 * 3` 个 float。
     */
    fun sampleArc(a: FloatArray, b: FloatArray, out: FloatArray): Int {
        val steps = (angleDeg(a, b) / ARC_STEP_DEG).toInt().coerceIn(1, 180)
        return slerp(a, b, steps, out)
    }

    /**
     * 遍历大圆弧 a→b 的可见顶点。逐段裁剪：出入视界处自然断开。
     * 收到 (x, y, z, startsNewSubpath)——`startsNewSubpath` 为 true 时调用方应 moveTo，
     * 否则 lineTo（弧线在背面断开后重新出现时不能跨过去连一条弦）。
     *
     * [scratch] 至少 `(181) * 3` 个 float，由调用方复用以免绘制期分配。
     */
    inline fun forEachArcVertex(
        a: FloatArray,
        b: FloatArray,
        basis: Basis,
        scratch: FloatArray,
        onVertex: (Float, Float, Float, Boolean) -> Unit,
    ) {
        val count = sampleArc(a, b, scratch)
        var prevVisible = false
        for (i in 0 until count) {
            val x = scratch[i * 3]; val y = scratch[i * 3 + 1]; val z = scratch[i * 3 + 2]
            val visible = (x * basis.zx + y * basis.zy + z * basis.zz) >= 0f
            if (visible) onVertex(x, y, z, !prevVisible)
            prevVisible = visible
        }
    }
}
