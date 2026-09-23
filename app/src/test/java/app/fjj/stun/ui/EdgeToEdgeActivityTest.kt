package app.fjj.stun.ui

import app.fjj.stun.ui.qr.AnimatedQrScanActivity
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 边到边防回归。
 *
 * 本仓的**状态栏透明是运行时做的**：`Theme.Stun` 里没有 `android:statusBarColor=@android:color/transparent`
 * （只有 `windowLightStatusBar` / `enforce*Contrast`），透明度完全来自 [BaseActivity] 里的 `enableEdgeToEdge()`。
 * 于是"新建 Activity 时忘了继承 BaseActivity"会退化成一个**只在真机上才看得见**的视觉问题：
 * 状态栏是主题默认的不透明色，而页面自己按边到边假设加的 inset padding 会变成双份留白。
 * 编译、lint、布局预览全都不会报错，所以只能靠这里钉死。
 *
 * 纯 JUnit 就够（只断言类型关系，不 inflate 任何东西）。
 */
class EdgeToEdgeActivityTest {

    /**
     * 例外：[VpnQuickActionActivity] 是快捷方式/小组件的无界面执行页，主题 `windowIsTranslucent`
     * 且全程用户不可见，不需要边到边，故直接继承 AppCompatActivity。
     */
    @Test
    fun 可见页面都继承BaseActivity() {
        listOf(
            MainActivity::class.java,
            ProfileEditActivity::class.java,
            SubscriptionHelpActivity::class.java,
            AnimatedQrScanActivity::class.java,
        ).forEach {
            assertTrue(
                "${it.simpleName} 必须继承 BaseActivity，否则状态栏不是透明的",
                BaseActivity::class.java.isAssignableFrom(it),
            )
        }
    }

    /**
     * 扫码页压在相机预览（暗底）上，必须自己声明 dark 风格让系统栏图标变白。
     * 光继承 [BaseActivity] 还不够 —— 基类的 auto 风格在浅色主题下会给深色图标，暗底上看不见。
     */
    @Test
    fun 扫码页覆盖系统栏样式为暗底白图标() {
        val declared = AnimatedQrScanActivity::class.java.declaredMethods.map { it.name }
        assertTrue(
            "AnimatedQrScanActivity 必须覆盖 statusBarStyle()（相机预览是暗底，auto 会给深色图标）",
            "statusBarStyle" in declared,
        )
        assertTrue(
            "AnimatedQrScanActivity 必须覆盖 navigationBarStyle()",
            "navigationBarStyle" in declared,
        )
    }
}
