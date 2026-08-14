package app.fjj.stun.ui

import android.os.Bundle
import android.view.MenuItem
import androidx.activity.OnBackPressedCallback
import androidx.core.view.GravityCompat
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

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.navView.setNavigationItemSelectedListener(this)
        setupHeader()

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
        val currentFragment = supportFragmentManager.findFragmentById(R.id.fragment_container)
        if (currentFragment?.javaClass == fragment.javaClass) return

        supportFragmentManager.commit {
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
}
