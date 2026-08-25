package app.fjj.stun.ui

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.color.DynamicColors

abstract class BaseActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // 强制每个 Activity 应用 Material 3 动态色（含经 SplashScreen 启动的 MainActivity），
        // 避免全局 applyToActivitiesIfAvailable 对 SplashScreen 启动的 Activity 覆盖不全导致页面配色不一致
        DynamicColors.applyToActivityIfAvailable(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
    }
}
