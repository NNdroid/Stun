package app.fjj.stun.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import app.fjj.stun.R
import app.fjj.stun.core.R as CoreR
import app.fjj.stun.databinding.ActivityAboutBinding
import app.fjj.stun.repo.StunLogger
import app.fjj.stun.util.AppUtils
import java.io.File
import java.net.URL
import kotlin.concurrent.thread

class AboutFragment : Fragment() {

    private var _binding: ActivityAboutBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = ActivityAboutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationIcon(R.drawable.ic_back)
        binding.toolbar.setNavigationOnClickListener {
            (requireActivity() as MainActivity).navigateToHome()
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.root.updatePadding(left = systemBars.left, right = systemBars.right)
            binding.appBar.updatePadding(top = systemBars.top)
            binding.scrollView.updatePadding(bottom = systemBars.bottom)
            binding.scrollView.clipToPadding = false
            insets
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
            val intent = Intent(Intent.ACTION_VIEW, "https://nndroid.github.io/Stun/privacy_policy.html".toUri())
            startActivity(intent)
        }

        val appVersion = AppUtils.getAppVersion(requireContext())
        val libVersion = AppUtils.getLibVersion()
        binding.tvVersionInfo.text = getString(CoreR.string.about_version_format, appVersion, libVersion)
        binding.tvPackageName.text = requireContext().packageName
    }

    private fun showLicenseDialog() {
        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle(CoreR.string.about_license)
        builder.setMessage(getString(CoreR.string.loading))
        val dialog = builder.create()
        dialog.show()

        val cacheFile = File(requireContext().cacheDir, "license_cache.txt")
        thread {
            try {
                val licenseText = URL("https://raw.githubusercontent.com/NNdroid/Stun/refs/heads/main/LICENSE.txt").readText()
                cacheFile.writeText(licenseText)
                activity?.runOnUiThread {
                    dialog.setMessage(licenseText)
                }
            } catch (e: Exception) {
                StunLogger.e("AboutFragment", "Failed to load license from network", e)
                activity?.runOnUiThread {
                    if (cacheFile.exists()) {
                        dialog.setMessage(cacheFile.readText())
                    } else {
                        dialog.setMessage(getString(CoreR.string.error_license_load, e.message ?: "Unknown"))
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
