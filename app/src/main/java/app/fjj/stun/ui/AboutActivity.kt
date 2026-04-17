package app.fjj.stun.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import app.fjj.stun.databinding.ActivityAboutBinding
import java.io.File
import java.net.URL
import kotlin.concurrent.thread
import app.fjj.stun.repo.StunLogger
import androidx.core.net.toUri
import app.fjj.stun.util.AppUtils

class AboutActivity : BaseActivity() {

    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(left = systemBars.left, right = systemBars.right, bottom = systemBars.bottom)
            binding.appBar.updatePadding(top = systemBars.top)
            insets
        }

        binding.toolbar.setNavigationOnClickListener {
            finish()
        }

        binding.btnSourceCode.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, "https://github.com/NNdroid/Stun".toUri())
            startActivity(intent)
        }

        binding.btnLicense.setOnClickListener {
            showLicenseDialog()
        }

        binding.btnFeedback.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, "https://github.com/NNdroid/Stun/issues/new".toUri())
            startActivity(intent)
        }

        binding.btnPrivacy.setOnClickListener {
            // TODO: Add privacy policy link if available
            Toast.makeText(this, "Privacy Policy not available", Toast.LENGTH_SHORT).show()
        }

        // Set version info
        val appVersion = AppUtils.getAppVersion(this)
        val libVersion = AppUtils.getLibVersion()

        binding.tvVersionInfo.text = getString(app.fjj.stun.R.string.about_version_format, appVersion, libVersion)
        binding.tvPackageName.text = packageName
    }

    private fun showLicenseDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle(app.fjj.stun.R.string.about_license)
        builder.setMessage("Loading...")
        val dialog = builder.create()
        dialog.show()

        val cacheFile = File(cacheDir, "license_cache.txt")

        thread {
            try {
                val licenseText = URL("https://raw.githubusercontent.com/NNdroid/Stun/refs/heads/main/LICENSE.txt").readText()
                // Cache the license text
                cacheFile.writeText(licenseText)
                runOnUiThread {
                    dialog.setMessage(licenseText)
                }
            } catch (e: Exception) {
                StunLogger.e("AboutActivity", "Failed to load license from network", e)
                runOnUiThread {
                    if (cacheFile.exists()) {
                        val cachedLicense = cacheFile.readText()
                        dialog.setMessage(cachedLicense)
                    } else {
                        dialog.setMessage("Failed to load license: ${e.message}")
                    }
                }
            }
        }
    }
}
