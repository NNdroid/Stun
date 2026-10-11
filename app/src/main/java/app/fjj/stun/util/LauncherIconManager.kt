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
     * 一个可选外观。[aliasClassName] 是短类名，manifest 里以相对形式声明（`.LauncherAurora`），
     * ComponentName 会用 [Context.getPackageName] 补齐。
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

    private fun setEnabled(pm: PackageManager, packageName: String, aliasClassName: String, state: Int, flags: Int) {
        try {
            pm.setComponentEnabledSetting(
                ComponentName(packageName, aliasClassName), state, flags
            )
        } catch (t: Throwable) {
            // 只可能是 manifest 里漏了 alias 或组件名拼错。记下来就好，不能因为这个让
            // 设置页点击或应用启动崩掉。
            StunLogger.e(TAG, "setComponentEnabledSetting($aliasClassName, $state) failed", t)
        }
    }
}
