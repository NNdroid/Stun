package app.fjj.stun.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.R
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

class StunCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator {
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }

    override fun onCreateSession(): Session {
        return object : Session() {
            override fun onCreateScreen(intent: Intent): androidx.car.app.Screen {
                return CarHomeScreen(carContext)
            }
        }
    }
}
