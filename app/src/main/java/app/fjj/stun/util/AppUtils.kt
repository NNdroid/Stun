package app.fjj.stun.util

import android.content.Context
import android.content.pm.PackageManager

object AppUtils {
    fun getAppVersion(context: Context): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }
    }

    fun getLibVersion(): String {
        return try {
            myssh.Myssh.getVersion()
        } catch (e: Exception) {
            "Unknown"
        }
    }
}
