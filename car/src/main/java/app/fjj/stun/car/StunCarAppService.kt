package app.fjj.stun.car

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.R
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

class StunCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator {
        // 发布包必须走 host 白名单（ALLOW_ALL 是 Play 政策拒绝项，也放开了伪造 host 的口子）；
        // debug 包保持全放行，方便本地 ADB 投影调试。hosts_allowlist_sample 是库自带的
        // Android Auto / Automotive 官方宿主签名清单。
        return if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(R.array.hosts_allowlist_sample)
                .build()
        }
    }

    override fun onCreateSession(): Session {
        return object : Session() {
            override fun onCreateScreen(intent: Intent): androidx.car.app.Screen {
                // 会话已存在时（从车机桌面再次进入）复用栈顶屏，避免每次都新叠一层
                // CarHomeScreen（重复观察者 + 多余的返回层级）。
                return carContext.getCarService(androidx.car.app.ScreenManager::class.java).top
                    ?: CarHomeScreen(carContext)
            }
        }
    }
}
