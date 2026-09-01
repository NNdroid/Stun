package app.fjj.stun.car

import android.content.Intent
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import app.fjj.stun.core.R as CoreR
import app.fjj.stun.repo.ProfileManager
import app.fjj.stun.repo.SettingsManager
import app.fjj.stun.repo.StunRepository
import app.fjj.stun.repo.VpnState
import app.fjj.stun.service.MyTransparentProxyService
import app.fjj.stun.service.MyVpnService

class CarHomeScreen(carContext: CarContext) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val profiles = ProfileManager.getProfiles(carContext)
        val selectedId = SettingsManager.getSelectedProfileId(carContext)
        val vpnState = StunRepository.vpnState.value ?: VpnState.DISCONNECTED

        val listBuilder = ItemList.Builder()

        if (profiles.isEmpty()) {
            listBuilder.addItem(
                Row.Builder()
                    .setTitle(carContext.getString(CoreR.string.main_empty_profiles))
                    .build()
            )
        } else {
            profiles.forEach { profile ->
                val isSelected = profile.id == selectedId
                val rowBuilder = Row.Builder()
                    .setTitle(profile.name)
                    .addText(if (profile.proxyAddr.isNotBlank()) profile.proxyAddr else profile.sshAddr)

                if (isSelected) {
                    rowBuilder.setOnClickListener {
                        toggleVpn()
                    }
                } else {
                    rowBuilder.setOnClickListener {
                        SettingsManager.setSelectedProfileId(carContext, profile.id)
                        invalidate()
                    }
                }

                listBuilder.addItem(rowBuilder.build())
            }
        }

        val isConnected = vpnState == VpnState.CONNECTED
        val actionText = if (isConnected) {
            carContext.getString(CoreR.string.car_power_button_disconnect)
        } else {
            carContext.getString(CoreR.string.car_power_button_connect)
        }

        val toggleAction = Action.Builder()
            .setTitle(actionText)
            .setBackgroundColor(if (isConnected) CarColor.RED else CarColor.GREEN)
            .setOnClickListener {
                toggleVpn()
            }
            .build()

        return ListTemplate.Builder()
            .setTitle(carContext.getString(CoreR.string.car_app_name))
            .setSingleList(listBuilder.build())
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(toggleAction)
                    .build()
            )
            .build()
    }

    private fun toggleVpn() {
        val currentState = StunRepository.vpnState.value ?: VpnState.DISCONNECTED
        val mode = SettingsManager.getServiceMode(carContext)
        val intentClass = if (mode == SettingsManager.SERVICE_MODE_TPROXY) {
            MyTransparentProxyService::class.java
        } else {
            MyVpnService::class.java
        }

        val action = if (currentState == VpnState.CONNECTED || currentState == VpnState.RECONNECTING) {
            "STOP"
        } else {
            "START"
        }

        val intent = Intent(carContext, intentClass).apply { this.action = action }
        carContext.startForegroundService(intent)
        invalidate()
    }
}
