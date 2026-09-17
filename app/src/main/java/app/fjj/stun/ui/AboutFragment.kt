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
import androidx.lifecycle.lifecycleScope
import app.fjj.stun.R
import app.fjj.stun.core.R as CoreR
import app.fjj.stun.databinding.ActivityAboutBinding
import app.fjj.stun.repo.StunLogger
import app.fjj.stun.util.AppUtils
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AboutFragment : Fragment() {

    private var _binding: ActivityAboutBinding? = null
    private val binding get() = _binding!!
    private var licenseDialog: AlertDialog? = null
    private var licenseJob: Job? = null

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
        licenseDialog?.dismiss()
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(CoreR.string.about_license)
            .setMessage(getString(CoreR.string.loading))
            .setNegativeButton(CoreR.string.close, null)
            .create()
        licenseDialog = dialog
        dialog.show()

        val appContext = requireContext().applicationContext
        val cacheFile = File(appContext.cacheDir, "license_cache.txt")
        licenseJob?.cancel()
        licenseJob = viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val connection = URL("https://raw.githubusercontent.com/NNdroid/Stun/refs/heads/main/LICENSE.txt")
                        .openConnection() as HttpURLConnection
                    try {
                        connection.connectTimeout = 8000
                        connection.readTimeout = 8000
                        connection.setRequestProperty("User-Agent", "Stun-Android")
                        if (connection.responseCode !in 200..299) {
                            error("HTTP ${connection.responseCode}")
                        }
                        connection.inputStream.bufferedReader().use { it.readText() }
                            .also { cacheFile.writeText(it) }
                    } finally {
                        connection.disconnect()
                    }
                }.recoverCatching { error ->
                    StunLogger.e("AboutFragment", "Failed to load license from network", error)
                    if (cacheFile.exists()) cacheFile.readText() else throw error
                }
            }
            if (!dialog.isShowing) return@launch
            result.fold(
                onSuccess = { dialog.setMessage(it) },
                onFailure = { error ->
                    val msg = error.localizedMessage ?: error.message ?: getString(CoreR.string.error_unknown)
                    dialog.setMessage(getString(CoreR.string.error_license_load, msg))
                }
            )
        }
    }

    override fun onDestroyView() {
        licenseJob?.cancel()
        licenseJob = null
        licenseDialog?.dismiss()
        licenseDialog = null
        super.onDestroyView()
        _binding = null
    }
}
