package app.fjj.stun.util

import android.content.Context
import android.content.res.Resources
import android.util.TypedValue
import androidx.core.content.ContextCompat
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * 主题属性取色（`?attr/colorPrimary` / `?attr/colorSurfaceContainerLow` 这类）的统一入口。
 *
 * **为什么需要它**：按名字取一个主题属性要跨三次 JNI 查表 ——
 * ① `Resources.getIdentifier(name, "attr", pkg)` 按**名字**翻资源表；
 * ② `Theme.resolveAttribute(id)` 再按 id 解析一次；
 * ③ 解析出来若还是资源引用，还得 `ContextCompat.getColor` 走一遍配置匹配。
 * 而调用点全在热路径上：节点列表每绑定一行要取 3~4 个颜色，连接中每秒数次的流量重绘
 * 也要取 2 个（`HomeFragment.renderTrafficState`）。
 *
 * **为什么可以缓存**：主题在一个 Activity 的整个生命周期内是**不变的** ——
 * `BaseActivity.onCreate` 在 `super.onCreate` 之前就调了
 * `DynamicColors.applyToActivityIfAvailable`，之后全仓没有任何运行时
 * `setTheme` / `applyStyle` / `setDefaultNightMode`。所以「同一个 Theme 实例 + 同一个
 * uiMode」下解析出的颜色恒为定值。
 *
 * **两处保险**：
 * - 颜色按 **Theme 实例**缓存，用 `WeakHashMap` 挂住 —— Activity 重建（含切深浅色、
 *   壁纸换动态色触发 Material 的 `recreate()`）会生成新的 Theme 对象，缓存自然失效，
 *   同时不会吊住已销毁的 Activity；
 * - 缓存条目额外记住解析时的 `uiMode`，一旦 uiMode 变了就整条丢弃 —— 覆盖「同一个
 *   Theme 实例被重新 apply 了另一套夜间属性」这种不重建 Activity 的路径。
 */
object ThemeColors {

    /** 名字 → attrId。0（查不到）也缓存：查不到的名字不必每次再翻一遍资源表。 */
    private val attrIds = ConcurrentHashMap<String, Int>()

    private class ThemeEntry(val uiMode: Int) {
        val colors = HashMap<Int, Int>()
    }

    /** Theme 实例 →（attrId → 已解析颜色）。弱键：Activity 销毁后条目自动消失。 */
    private val entries: MutableMap<Resources.Theme, ThemeEntry> =
        Collections.synchronizedMap(WeakHashMap())

    /**
     * 解析 attrId。**顺序与改造前逐字一致**：先查 app 自己的包，再退回 `"android"`。
     *
     * 注意回退分支其实是个陷阱 —— 框架里存在 `android:colorPrimary` 等同名属性，
     * 一旦 app 包查不到就会**误命中框架值**。只因为 `Theme.Stun` 自己定义了这些属性、
     * 第一分支必然命中，才一直没暴露。改这里务必保持顺序，别把两分支调换。
     */
    private fun attrId(context: Context, attrName: String): Int {
        attrIds[attrName]?.let { return it }
        val res = context.resources
        val id = res.getIdentifier(attrName, "attr", context.packageName).takeIf { it != 0 }
            ?: res.getIdentifier(attrName, "attr", "android")
        attrIds[attrName] = id
        return id
    }

    /**
     * 取主题属性颜色，等同 `context.theme.resolveAttribute(attrId, tv, true)` 后
     * 「有 resourceId 走 ContextCompat.getColor，否则用 data 原值」的那套老逻辑，
     * 只是带上了缓存。取不到属性（attrName 不存在）时返回 [default]。
     */
    fun color(context: Context, attrName: String, default: Int): Int {
        val id = attrId(context, attrName)
        if (id == 0) return default

        val theme = context.theme
        val uiMode = context.resources.configuration.uiMode
        val entry = entries[theme]?.takeIf { it.uiMode == uiMode }
            ?: ThemeEntry(uiMode).also { entries[theme] = it }

        entry.colors[id]?.let { return it }

        val typedValue = TypedValue()
        if (!theme.resolveAttribute(id, typedValue, true)) {
            // 解析不到**不入缓存**：default 是每个调用点自己的兜底值（同一个 attr
            // 有人传 BLUE、有人传 RED），缓存下来会把 A 的兜底值发给 B。
            return default
        }
        val value = if (typedValue.resourceId != 0) {
            ContextCompat.getColor(context, typedValue.resourceId)
        } else {
            typedValue.data
        }
        entry.colors[id] = value
        return value
    }
}
