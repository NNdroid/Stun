package app.fjj.stun.util

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import app.fjj.stun.repo.SettingsManager
import java.util.Locale

object LocaleHelper {

    fun wrapContext(context: Context): Context {
        val language = SettingsManager.getLanguage(context)
        val systemLocale = android.content.res.Resources.getSystem().configuration.locales[0]

        if (language == "auto") {
            Locale.setDefault(systemLocale)
            return context
        }

        val locale = when (language) {
            "en" -> Locale.ENGLISH
            "zh" -> Locale.SIMPLIFIED_CHINESE
            "zh-rTW" -> Locale.TRADITIONAL_CHINESE
            "de" -> Locale.GERMAN
            "fr" -> Locale.FRENCH
            "ja" -> Locale.JAPANESE
            else -> Locale.ENGLISH
        }

        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        val localeList = LocaleList(locale)
        config.setLocales(localeList)
        
        return context.createConfigurationContext(config)
    }

    fun applyLocale(context: Context) {
        val language = SettingsManager.getLanguage(context)
        val locale = if (language == "auto") {
            android.content.res.Resources.getSystem().configuration.locales[0]
        } else {
            when (language) {
                "en" -> Locale.ENGLISH
                "zh" -> Locale.SIMPLIFIED_CHINESE
                "zh-rTW" -> Locale.TRADITIONAL_CHINESE
                "de" -> Locale.GERMAN
                "fr" -> Locale.FRENCH
                "ja" -> Locale.JAPANESE
                else -> Locale.ENGLISH
            }
        }

        Locale.setDefault(locale)
        val resources = context.resources
        val configuration = resources.configuration
        configuration.setLocale(locale)
        context.createConfigurationContext(configuration)
    }
}
