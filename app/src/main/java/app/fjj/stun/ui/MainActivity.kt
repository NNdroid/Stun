package app.fjj.stun.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import app.fjj.stun.R
import app.fjj.stun.databinding.ActivityMainBinding
import app.fjj.stun.util.AppUtils
import kotlinx.coroutines.launch

class MainActivity : BaseActivity() {

    private lateinit var binding: ActivityMainBinding

    var pendingVpnStart: Boolean = false
    var pendingStunImport: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            app.fjj.stun.repo.ProfileManager.migratePlaintextProfiles(this@MainActivity)
        }

        setupNavigationDrawer()

        if (savedInstanceState == null) {
            navigateTo(HomeFragment(), R.id.nav_home)
        } else {
            val restoredItem = when (supportFragmentManager.findFragmentById(R.id.fragment_container)) {
                is SettingsFragment -> R.id.nav_settings
                is LogsFragment -> R.id.nav_logs
                is AboutFragment -> R.id.nav_about
                else -> R.id.nav_home
            }
            updateNavigationSelection(restoredItem)
        }
        handleDeepLink(intent)
        handleShortcutIntent(intent)

        // Crash files can be large; do not read/delete them on the first-frame path.
        lifecycleScope.launch {
            val crashReport = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                app.fjj.stun.util.CrashHandler.checkPreviousCrash(this@MainActivity)
            }
            if (!crashReport.isNullOrBlank() && !isFinishing && !isDestroyed) {
                app.fjj.stun.util.CrashHandler.showCrashDialog(this@MainActivity, crashReport)
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    val currentFragment = supportFragmentManager.findFragmentById(R.id.fragment_container)
                    if (currentFragment !is HomeFragment) {
                        navigateTo(HomeFragment(), R.id.nav_home)
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })
    }

    private fun setupNavigationDrawer() {
        val density = resources.displayMetrics.density
        binding.drawerLayout.setDrawerElevation(12 * density)
        binding.drawerLayout.doOnLayout { drawer ->
            val edgeReveal = (56 * density).toInt()
            val minWidth = minOf((256 * density).toInt(), drawer.width)
            val targetWidth = minOf((360 * density).toInt(), drawer.width - edgeReveal)
                .coerceAtLeast(minWidth)
            binding.navView.updateLayoutParams<DrawerLayout.LayoutParams> {
                width = targetWidth
            }
        }

        val content = binding.navView.findViewById<View>(R.id.nav_drawer_content)
        val initialTopPadding = content.paddingTop
        val initialBottomPadding = content.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(
                top = initialTopPadding + bars.top,
                bottom = initialBottomPadding + bars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(content)

        binding.navView.findViewById<TextView>(R.id.tv_version)?.text = try {
            AppUtils.getAppVersion(this)
        } catch (e: Exception) {
            "v1.0.0"
        }

        binding.navView.findViewById<View>(R.id.nav_home).setOnClickListener {
            navigateTo(HomeFragment(), R.id.nav_home)
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        }
        binding.navView.findViewById<View>(R.id.nav_settings).setOnClickListener {
            navigateTo(SettingsFragment(), R.id.nav_settings)
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        }
        binding.navView.findViewById<View>(R.id.nav_logs).setOnClickListener {
            navigateTo(LogsFragment(), R.id.nav_logs)
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        }
        binding.navView.findViewById<View>(R.id.nav_about).setOnClickListener {
            navigateTo(AboutFragment(), R.id.nav_about)
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        }
    }

    private fun navigateTo(fragment: Fragment, itemId: Int) {
        if (isFinishing || isDestroyed) return
        val currentFragment = supportFragmentManager.findFragmentById(R.id.fragment_container)
        updateNavigationSelection(itemId)
        if (currentFragment?.javaClass == fragment.javaClass) return

        supportFragmentManager.commit(allowStateLoss = true) {
            setCustomAnimations(
                android.R.anim.fade_in,
                android.R.anim.fade_out
            )
            replace(R.id.fragment_container, fragment)
            setReorderingAllowed(true)
        }
        
    }

    private fun updateNavigationSelection(selectedItemId: Int) {
        val rows = listOf(
            R.id.nav_home to R.id.nav_home_indicator,
            R.id.nav_settings to R.id.nav_settings_indicator,
            R.id.nav_logs to R.id.nav_logs_indicator,
            R.id.nav_about to R.id.nav_about_indicator
        )
        rows.forEach { (rowId, indicatorId) ->
            val selected = rowId == selectedItemId
            binding.navView.findViewById<View>(rowId).isSelected = selected
            binding.navView.findViewById<View>(indicatorId).visibility =
                if (selected) View.VISIBLE else View.INVISIBLE
        }
    }

    fun navigateToHome() {
        navigateTo(HomeFragment(), R.id.nav_home)
    }

    fun openDrawer() {
        binding.drawerLayout.openDrawer(GravityCompat.START)
    }

    private fun handleDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme.equals("stun", ignoreCase = true)) {
            // schemeSpecificPart is decoded, so Base64/JSON punctuation survives
            // links that other apps percent-encode before opening Stun. A URI
            // written as stun://payload exposes the leading // as part of SSP.
            pendingStunImport = data.schemeSpecificPart.removePrefix("//")
        }
    }

    private fun handleShortcutIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: ""
        val extraAction = intent.getStringExtra("action") ?: ""
        var handled = true

        when {
            action == ACTION_SHORTCUT_START_VPN || extraAction == "start_vpn" -> {
                pendingVpnStart = true
                navigateTo(HomeFragment(), R.id.nav_home)
                supportFragmentManager.executePendingTransactions()
                (supportFragmentManager.findFragmentById(R.id.fragment_container) as? HomeFragment)
                    ?.consumePendingVpnStart()
            }
            action == ACTION_SHORTCUT_STOP_VPN || extraAction == "stop_vpn" -> {
                val state = app.fjj.stun.repo.StunRepository.vpnState.value
                val isActive = state == app.fjj.stun.repo.VpnState.CONNECTED ||
                    state == app.fjj.stun.repo.VpnState.CONNECTING ||
                    state == app.fjj.stun.repo.VpnState.RECONNECTING
                if (isActive) {
                    val mode = app.fjj.stun.repo.SettingsManager.getServiceMode(this)
                    val isTProxy = mode == app.fjj.stun.repo.SettingsManager.SERVICE_MODE_TPROXY
                    val serviceClass = if (isTProxy) app.fjj.stun.service.MyTransparentProxyService::class.java else app.fjj.stun.service.MyVpnService::class.java
                    val serviceAction = if (isTProxy) app.fjj.stun.service.MyTransparentProxyService.ACTION_STOP else app.fjj.stun.service.MyVpnService.ACTION_STOP
                    val vpnIntent = Intent(this, serviceClass).apply { this.action = serviceAction }
                    val stopResult = runCatching {
                        androidx.core.content.ContextCompat.startForegroundService(this, vpnIntent)
                    }
                    stopResult.onFailure {
                        android.widget.Toast.makeText(
                            this,
                            getString(app.fjj.stun.core.R.string.error_prefix, it.localizedMessage ?: it.javaClass.simpleName),
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                    if (stopResult.isSuccess) {
                        android.widget.Toast.makeText(this, getString(app.fjj.stun.core.R.string.shortcut_stop_vpn), android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
            action == ACTION_SHORTCUT_SWITCH_NODE -> {
                navigateTo(HomeFragment(), R.id.nav_home)
            }
            action == ACTION_SHORTCUT_REMOTE_TV -> {
                TvDevicePickerBottomSheet().show(supportFragmentManager, "TvDevicePickerBottomSheet")
            }
            else -> handled = false
        }

        if (handled) {
            // Dynamic shortcuts/widgets are commands, not persistent launch
            // state. Clearing them prevents replay on configuration changes.
            intent.action = Intent.ACTION_MAIN
            intent.removeExtra("action")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
        handleShortcutIntent(intent)
        setIntent(intent)
        if (pendingVpnStart || pendingStunImport != null) {
            navigateTo(HomeFragment(), R.id.nav_home)
            supportFragmentManager.executePendingTransactions()
        }
        // App 已在前台：把外部动作转交给 HomeFragment，统一执行校验和权限流程。
        (supportFragmentManager.findFragmentById(R.id.fragment_container) as? HomeFragment)?.let {
            it.consumePendingVpnStart()
            it.consumePendingStunImport()
        }
    }

    companion object {
        const val ACTION_SHORTCUT_START_VPN = "app.fjj.stun.ACTION_SHORTCUT_START_VPN"
        const val ACTION_SHORTCUT_STOP_VPN = "app.fjj.stun.ACTION_SHORTCUT_STOP_VPN"
        const val ACTION_SHORTCUT_SWITCH_NODE = "app.fjj.stun.ACTION_SHORTCUT_SWITCH_NODE"
        const val ACTION_SHORTCUT_REMOTE_TV = "app.fjj.stun.ACTION_SHORTCUT_REMOTE_TV"
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean {
        if (ev?.action == android.view.MotionEvent.ACTION_DOWN) {
            val v = currentFocus
            if (v is android.widget.EditText) {
                val outRect = android.graphics.Rect()
                v.getGlobalVisibleRect(outRect)
                if (!outRect.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                    val imm = getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                    imm?.hideSoftInputFromWindow(v.windowToken, 0)
                    v.clearFocus()
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }
}
