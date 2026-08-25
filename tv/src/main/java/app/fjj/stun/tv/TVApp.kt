package app.fjj.stun.tv

import android.app.Application
import app.fjj.stun.repo.SettingsManager
import app.fjj.stun.repo.StunLogger
import app.fjj.stun.util.KeystoreUtils

class TVApp : Application() {
    override fun onCreate() {
        super.onCreate()
        StunLogger.init(this)
        KeystoreUtils.init(this)
        SettingsManager.checkAndUpdateGeoData(this)
    }
}
