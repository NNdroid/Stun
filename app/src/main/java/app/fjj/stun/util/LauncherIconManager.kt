package app.fjj.stun.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import app.fjj.stun.R
import app.fjj.stun.core.R as CoreR
import app.fjj.stun.repo.SettingsManager
import app.fjj.stun.repo.StunLogger

/**
 * 桌面启动图标的外观切换。
 *
 * Android 没有「换应用图标」的 API，只有「换哪个组件是启动入口」。做法是 manifest 里给同一个
 * Activity 挂 5 个 activity-alias，每个各带一套 android:icon（见 AndroidManifest.xml），
 * 这里用 [PackageManager.setComponentEnabledSetting] 让其中**恰好一个** ENABLED、其余 DISABLED；
 * launcher 每次解析启动入口拿到的就是那一个 alias，图标随之切换。
 *
 * MainActivity 本身已不带 MAIN/LAUNCHER 过滤器，否则桌面会出现 5 个图标。
 */
object LauncherIconManager {

    private const val TAG = "LauncherIconManager"

    /**
     * 一个可选外观。[aliasClassName] 是短类名，与 manifest 里相对声明的 `.LauncherAurora`
     * 对应；交给 [componentName] 拼成全限定名才能传给 PackageManager。
     */
    data class IconTheme(
        val id: String,
        val aliasClassName: String,
        val iconRes: Int,
        val labelRes: Int
    )

    /** 顺序即设置页里的展示顺序：默认款打头。 */
    val THEMES: List<IconTheme> = listOf(
        IconTheme(
            id = SettingsManager.ICON_THEME_DEFAULT,
            aliasClassName = "LauncherDefault",
            iconRes = R.mipmap.ic_launcher,
            labelRes = CoreR.string.icon_theme_default
        ),
        IconTheme(
            id = SettingsManager.ICON_THEME_AURORA,
            aliasClassName = "LauncherAurora",
            iconRes = R.mipmap.ic_launcher_aurora,
            labelRes = CoreR.string.icon_theme_aurora
        ),
        IconTheme(
            id = SettingsManager.ICON_THEME_OCEAN,
            aliasClassName = "LauncherOcean",
            iconRes = R.mipmap.ic_launcher_ocean,
            labelRes = CoreR.string.icon_theme_ocean
        ),
        IconTheme(
            id = SettingsManager.ICON_THEME_MINT,
            aliasClassName = "LauncherMint",
            iconRes = R.mipmap.ic_launcher_mint,
            labelRes = CoreR.string.icon_theme_mint
        ),
        IconTheme(
            id = SettingsManager.ICON_THEME_GRAPE,
            aliasClassName = "LauncherGrape",
            iconRes = R.mipmap.ic_launcher_grape,
            labelRes = CoreR.string.icon_theme_grape
        )
    )

    /** 按 id 取外观；不认识的值（旧版本、云端回传、误写）一律回落到默认款。 */
    fun byId(id: String): IconTheme = THEMES.firstOrNull { it.id == id } ?: THEMES.first()

    /**
     * 把桌面启动图标切到 [id]，并记住偏好。
     *
     * [PackageManager.DONT_KILL_APP]：改组件状态不重启应用进程，切换外观不需要杀自己。
     * 顺序刻意是「先开目标、再关其余」——任何一步失败都不会出现「零个 ENABLED 启动组件」的
     * 窗口，那样桌面上的图标会整块消失。
     */
    fun apply(context: Context, id: String) {
        val target = byId(id)
        SettingsManager.saveIconTheme(context, target.id)

        val pm = context.packageManager
        val flags = PackageManager.DONT_KILL_APP
        setEnabled(pm, context.packageName, target.aliasClassName,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED, flags)
        for (theme in THEMES) {
            if (theme == target) continue
            setEnabled(pm, context.packageName, theme.aliasClassName,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, flags)
        }
    }

    /** 冷启动时把桌面图标对齐到当前偏好。失败不影响应用启动：图标保持系统记住的旧状态。 */
    fun applyOnStartup(context: Context) = apply(context, SettingsManager.getIconTheme(context))

    /**
     * 拼出 alias 的组件名，**类名必须显式带包名前缀**。
     *
     * `ComponentName(package, "LauncherAurora")` **不会**自动补前缀。API 35 的实现就是
     * `mPackage = packageName; mClass = className` 原样赋值（反编译 android-all 15 确认，
     * 没有 `indexOf('.')` 那条隐式规则）。裸短类名送进 PMS 查不到，抛
     * `IllegalArgumentException: Component class LauncherAurora does not exist in app.fjj.stun`
     * ——哪怕 manifest 里 alias 明明在。
     *
     * 这一版就是这么踩过：五个 alias 每次冷启动各炸一条、图标永远切不动。
     * Robolectric 的 shadow 把 ComponentName 原样存进覆盖表、不校验组件是否存在，
     * 所以单测一条都没报。
     *
     * 也别写成单参数的 `ComponentName("app.fjj.stun.LauncherAurora")`：这个构造函数在
     * API 35 上已不存在（只剩 `(String,String)`/`(Context,String)`/`(Context,Class)`/`(Parcel)`/`()`），
     * 本项目 android.jar stub 同样没有它；而且旧版本里它按**最后一个**点号切包名，
     * 包名带点时照样会错。
     */
    internal fun componentName(packageName: String, aliasClassName: String): ComponentName =
        ComponentName(packageName, "$packageName.$aliasClassName")

    private fun setEnabled(pm: PackageManager, packageName: String, aliasClassName: String, state: Int, flags: Int) {
        val component = componentName(packageName, aliasClassName)
        try {
            pm.setComponentEnabledSetting(component, state, flags)
        } catch (t: Throwable) {
            // 组件名不在组件表里。最可能是 manifest 与 THEMES 不同步，
            // 其次是装的 APK 构建得比 alias 更早（覆盖安装不会重建组件表）。
            // 日志带完整组件名，出问题时不必再猜传进去的是哪个名字。
            // 不能因为这个让设置页点击或应用启动崩掉。
            StunLogger.e(TAG, "setComponentEnabledSetting(${component.flattenToString()}, $state) failed", t)
        }
    }
}
