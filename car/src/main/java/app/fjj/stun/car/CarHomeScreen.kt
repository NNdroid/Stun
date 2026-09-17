package app.fjj.stun.car

import android.content.Intent
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import app.fjj.stun.core.R as CoreR
import app.fjj.stun.repo.Profile
import app.fjj.stun.repo.ProfileManager
import app.fjj.stun.repo.SettingsManager
import app.fjj.stun.repo.StunRepository
import app.fjj.stun.repo.VpnState
import app.fjj.stun.service.MyTransparentProxyService
import app.fjj.stun.service.MyVpnService

class CarHomeScreen(carContext: CarContext) : Screen(carContext) {

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var cachedProfiles: List<Profile>? = null

    @Volatile
    private var profilesLoading = false

    @Volatile
    private var vpnConsentRequired = false

    init {
        // Screen implements LifecycleOwner (car-app 1.4.0); the LiveData observer is
        // auto-removed when the screen is destroyed and only fires while it is started.
        StunRepository.vpnState.observe(this) { invalidate() }
    }

    override fun onGetTemplate(): Template {
        // onGetTemplate runs on the main thread; Room access must happen off it.
        val profiles = cachedProfiles ?: run {
            startProfileLoad()
            emptyList()
        }
        val selectedId = SettingsManager.getSelectedProfileId(carContext)
        val vpnState = StunRepository.vpnState.value ?: VpnState.DISCONNECTED
        val isTransitioning = vpnState == VpnState.CONNECTING || vpnState == VpnState.RECONNECTING
        val canSelectProfile = vpnState == VpnState.DISCONNECTED

        val listBuilder = ItemList.Builder()

        if (vpnConsentRequired) {
            listBuilder.addItem(
                Row.Builder()
                    .setTitle(carContext.getString(CoreR.string.car_vpn_consent_required))
                    .build()
            )
        }

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

                if (isSelected && !isTransitioning) {
                    rowBuilder.setOnClickListener {
                        toggleVpn()
                    }
                } else if (!isSelected && canSelectProfile) {
                    rowBuilder.setOnClickListener {
                        SettingsManager.setSelectedProfileId(carContext, profile.id)
                        invalidate()
                    }
                }

                listBuilder.addItem(rowBuilder.build())
            }
        }

        val isConnected = vpnState == VpnState.CONNECTED
        val actionText = when {
            isTransitioning -> carContext.getString(CoreR.string.main_connecting)
            isConnected -> carContext.getString(CoreR.string.car_power_button_disconnect)
            else -> carContext.getString(CoreR.string.car_power_button_connect)
        }

        val toggleAction = Action.Builder()
            .setTitle(actionText)
            .setBackgroundColor(if (isConnected) CarColor.RED else CarColor.GREEN)
            .setOnClickListener {
                if (!isTransitioning) toggleVpn()
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

    private fun startProfileLoad() {
        if (profilesLoading) return
        profilesLoading = true
        Thread {
            val loaded = try {
                ProfileManager.getProfiles(carContext)
            } catch (_: Exception) {
                emptyList()
            }
            cachedProfiles = loaded
            profilesLoading = false
            mainHandler.post { invalidate() }
        }.start()
    }

    private fun toggleVpn() {
        val currentState = StunRepository.vpnState.value ?: VpnState.DISCONNECTED
        val mode = SettingsManager.getServiceMode(carContext)
        val isTProxy = mode == SettingsManager.SERVICE_MODE_TPROXY
        if (currentState == VpnState.CONNECTING || currentState == VpnState.RECONNECTING) return
        val isStopping = currentState == VpnState.CONNECTED

        if (!isStopping && !isTProxy && VpnService.prepare(carContext) != null) {
            // Starting the VPN service without consent would make it spin in its
            // reconnect loop forever; surface the requirement in the template instead.
            vpnConsentRequired = true
            invalidate()
            return
        }
        vpnConsentRequired = false

        val intentClass = if (isTProxy) {
            MyTransparentProxyService::class.java
        } else {
            MyVpnService::class.java
        }

        val action = if (isStopping) {
            if (isTProxy) MyTransparentProxyService.ACTION_STOP else MyVpnService.ACTION_STOP
        } else {
            if (isTProxy) MyTransparentProxyService.ACTION_START else MyVpnService.ACTION_START
        }

        val intent = Intent(carContext, intentClass).apply { this.action = action }
        carContext.startForegroundService(intent)
        invalidate()
    }
}
