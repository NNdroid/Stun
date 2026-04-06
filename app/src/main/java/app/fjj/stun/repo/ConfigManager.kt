package app.fjj.stun.repo

import android.content.Context
import android.content.SharedPreferences

object ConfigManager {
    private const val PREF_NAME = "vpn_config"

    // ================== Keys ==================
    private const val KEY_SSH_ADDR = "ssh_addr"
    private const val KEY_USER = "user"
    private const val KEY_PASS = "pass"
    private const val KEY_TUNNEL_TYPE = "tunnel_type"
    private const val KEY_PROXY_ADDR = "proxy_addr"
    private const val KEY_CUSTOM_HOST = "custom_host"
    private const val KEY_LOG_LEVEL = "log_level"

    // ============== Default Values ==============
    const val DEFAULT_SSH_ADDR = "198.98.61.214:666"
    const val DEFAULT_USER = "opentunnel.net-test007"
    const val DEFAULT_PASS = "521qqwq"
    const val DEFAULT_TUNNEL_TYPE = "tls"
    const val DEFAULT_PROXY_ADDR = "198.98.61.214:443"
    const val DEFAULT_CUSTOM_HOST = "microsoft.com"
    const val DEFAULT_LOG_LEVEL = "V"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun saveConfig(
        context: Context,
        sshAddr: String,
        user: String,
        pass: String,
        tunnelType: String,
        proxyAddr: String,
        customHost: String,
        logLevel: String
    ) {
        getPrefs(context).edit().apply {
            putString(KEY_SSH_ADDR, sshAddr)
            putString(KEY_USER, user)
            putString(KEY_PASS, pass)
            putString(KEY_TUNNEL_TYPE, tunnelType)
            putString(KEY_PROXY_ADDR, proxyAddr)
            putString(KEY_CUSTOM_HOST, customHost)
            putString(KEY_LOG_LEVEL, logLevel)
            apply()
        }
    }

    fun getSshAddr(context: Context) = getPrefs(context).getString(KEY_SSH_ADDR, DEFAULT_SSH_ADDR) ?: DEFAULT_SSH_ADDR

    fun getUser(context: Context) = getPrefs(context).getString(KEY_USER, DEFAULT_USER) ?: DEFAULT_USER

    fun getPass(context: Context) = getPrefs(context).getString(KEY_PASS, DEFAULT_PASS) ?: DEFAULT_PASS

    fun getTunnelType(context: Context) = getPrefs(context).getString(KEY_TUNNEL_TYPE, DEFAULT_TUNNEL_TYPE) ?: DEFAULT_TUNNEL_TYPE

    fun getProxyAddr(context: Context) = getPrefs(context).getString(KEY_PROXY_ADDR, DEFAULT_PROXY_ADDR) ?: DEFAULT_PROXY_ADDR

    fun getCustomHost(context: Context) = getPrefs(context).getString(KEY_CUSTOM_HOST, DEFAULT_CUSTOM_HOST) ?: DEFAULT_CUSTOM_HOST

    fun getLogLevel(context: Context) = getPrefs(context).getString(KEY_LOG_LEVEL, DEFAULT_LOG_LEVEL) ?: DEFAULT_LOG_LEVEL
}