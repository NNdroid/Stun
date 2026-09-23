package app.fjj.stun.ui

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.color.DynamicColors

/**
 * 可见 Activity 的基类，统一做两件事：
 *
 * 1. **动态取色**：强制每个 Activity 应用 Material 3 动态色（含经 SplashScreen 启动的 MainActivity），
 *    避免全局 applyToActivitiesIfAvailable 对 SplashScreen 启动的 Activity 覆盖不全导致页面配色不一致。
 * 2. **边到边**：本仓**没有**在主题里写死 `android:statusBarColor=@android:color/transparent`
 *    （`Theme.Stun` 只放了 `windowLightStatusBar` / `enforce*Contrast`，透明度全靠运行时这一下）。
 *    所以新建 Activity 时**必须继承本类**，直接继承 `AppCompatActivity` 的页面状态栏是有色的。
 */
abstract class BaseActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        enableEdgeToEdge(statusBarStyle(), navigationBarStyle())
        super.onCreate(savedInstanceState)
    }

    /**
     * 状态栏样式。默认 auto —— 图标颜色跟随夜间模式。
     *
     * 压在**暗色内容**上的页面（相机预览、黑色画布）必须覆盖成 [SystemBarStyle.dark]：
     * auto 在浅色模式下会放行深色图标（`isAppearanceLightStatusBars = !isDark`），在暗底上基本看不见。
     */
    protected open fun statusBarStyle(): SystemBarStyle =
        SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)

    /**
     * 导航栏样式，语义同 [statusBarStyle]。
     * 默认 scrim 与 `enableEdgeToEdge()` 无参默认值一致（该常量在 androidx.activity 里是 internal）：
     * 浅色 0xE6 白 / 深色 0x80 近黑，与 framework 的 `system_bar_background_semi_transparent` 对齐。
     */
    protected open fun navigationBarStyle(): SystemBarStyle =
        SystemBarStyle.auto(
            Color.argb(0xE6, 0xFF, 0xFF, 0xFF),
            Color.argb(0x80, 0x1B, 0x1B, 0x1B),
        )
}
