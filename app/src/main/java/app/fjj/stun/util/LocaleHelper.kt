package app.fjj.stun.util

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import app.fjj.stun.repo.SettingsManager
import java.util.Locale

object LocaleHelper {

    /**
     * Used to wrap Context for non-AppCompat components (like Services or Application).
     */
    fun wrapContext(context: Context): Context {
        val language = SettingsManager.getLanguage(context)
        if (language == "auto") return context

        val locale = getLocale(language)
        Locale.setDefault(locale)

        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(locale)
        configuration.setLocales(android.os.LocaleList(locale))
        
        return context.createConfigurationContext(configuration)
    }

    /**
     * Applies the selected language using AppCompatDelegate.
     * Maps internal codes to standard BCP-47 tags.
     */
    fun applyLocale(context: Context) {
        val language = SettingsManager.getLanguage(context)
        val localeTag = if (language == "auto") {
            ""
        } else {
            when (language) {
                "en" -> "en"
                "zh" -> "zh-CN"
                "zh-rTW" -> "zh-TW"
                "de" -> "de"
                "fr" -> "fr"
                "ja" -> "ja"
                else -> "en"
            }
        }

        // Set Default Locale to help non-AppCompat resource lookups
        if (localeTag.isNotEmpty()) {
            Locale.setDefault(Locale.forLanguageTag(localeTag))
        }

        val appLocale = LocaleListCompat.forLanguageTags(localeTag)
        AppCompatDelegate.setApplicationLocales(appLocale)
    }

    private fun getLocale(language: String): Locale {
        return when (language) {
            "en" -> Locale.ENGLISH
            "zh" -> Locale.SIMPLIFIED_CHINESE
            "zh-rTW" -> Locale.TRADITIONAL_CHINESE
            "de" -> Locale.GERMAN
            "fr" -> Locale.FRENCH
            "ja" -> Locale.JAPANESE
            else -> Locale.ENGLISH
        }
    }
}
