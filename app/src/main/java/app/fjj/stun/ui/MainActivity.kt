package app.fjj.stun.ui

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import androidx.activity.OnBackPressedCallback
import androidx.core.view.GravityCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import app.fjj.stun.R
import app.fjj.stun.databinding.ActivityMainBinding
import app.fjj.stun.util.AppUtils
import android.widget.TextView
import com.google.android.material.navigation.NavigationView

class MainActivity : BaseActivity(), NavigationView.OnNavigationItemSelectedListener {

    private lateinit var binding: ActivityMainBinding

    // 来自 stun:// deep link 的待导入 URI，由 HomeFragment 在 onResume 消费
    var pendingStunImport: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            app.fjj.stun.repo.ProfileManager.migratePlaintextProfiles(this@MainActivity)
        }

        binding.navView.setNavigationItemSelectedListener(this)
        setupHeader()

        handleDeepLink(intent)

        if (savedInstanceState == null) {
            navigateTo(HomeFragment(), R.id.nav_home)
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

    private fun setupHeader() {
        val headerView = binding.navView.getHeaderView(0)
        headerView.findViewById<TextView>(R.id.tv_version)?.text = try {
            AppUtils.getAppVersion(this)
        } catch (e: Exception) {
            "v1.0.0"
        }
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.nav_home -> navigateTo(HomeFragment(), item.itemId)
            R.id.nav_settings -> navigateTo(SettingsFragment(), item.itemId)
            R.id.nav_logs -> navigateTo(LogsFragment(), item.itemId)
            R.id.nav_about -> navigateTo(AboutFragment(), item.itemId)
        }
        binding.drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    private fun navigateTo(fragment: Fragment, itemId: Int) {
        if (isFinishing || isDestroyed) return
        val currentFragment = supportFragmentManager.findFragmentById(R.id.fragment_container)
        if (currentFragment?.javaClass == fragment.javaClass) return

        supportFragmentManager.commit(allowStateLoss = true) {
            setCustomAnimations(
                android.R.anim.fade_in,
                android.R.anim.fade_out
            )
            replace(R.id.fragment_container, fragment)
            setReorderingAllowed(true)
        }
        
        // Manually handle menu item selection state across different groups
        val menu = binding.navView.menu
        for (i in 0 until menu.size()) {
            val item = menu.getItem(i)
            if (item.hasSubMenu()) {
                val subMenu = item.subMenu
                for (j in 0 until subMenu!!.size()) {
                    val subItem = subMenu.getItem(j)
                    subItem.isChecked = subItem.itemId == itemId
                }
            } else {
                item.isChecked = item.itemId == itemId
            }
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
            // 用 schemeSpecificPart（已解码 % 编码），避免 base64 中的 /+= 被百分号编码后 base64 解码失败
            pendingStunImport = "stun://" + data.schemeSpecificPart
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
        setIntent(intent)
        // App 已在前台：直接把 deep link 转交给当前 HomeFragment 导入
        (supportFragmentManager.findFragmentById(R.id.fragment_container) as? HomeFragment)
            ?.let { it.consumePendingStunImport() }
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
