package com.nkls.nekovideo.language

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

object LanguageManager {
    private val localeTagsByLanguage = mapOf(
        "pt" to "pt",
        "en" to "en",
        "es" to "es",
        "fr" to "fr",
        "de" to "de",
        "ru" to "ru",
        "hi" to "hi",
        "zh" to "zh-CN",
        "zh-TW" to "zh-TW"
    )
    private val supportedLanguages = localeTagsByLanguage.keys
    private val _currentLanguage = MutableStateFlow("system")
    var currentLanguage: StateFlow<String> = _currentLanguage.asStateFlow()

    fun initialize() {
        _currentLanguage.value = getCurrentLanguage()
    }

    fun getCurrentLanguage(context: Context? = null): String {
        val localeTags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        if (localeTags.isBlank()) return "system"

        val primaryTag = localeTags.substringBefore(',')
        return resolveLanguageCode(primaryTag)
    }

    fun updateLanguage(languageCode: String) {
        val locales = when (languageCode) {
            "system" -> LocaleListCompat.getEmptyLocaleList()
            in supportedLanguages -> LocaleListCompat.forLanguageTags(
                localeTagsByLanguage.getValue(languageCode)
            )
            else -> LocaleListCompat.getEmptyLocaleList()
        }

        AppCompatDelegate.setApplicationLocales(locales)
        _currentLanguage.value = languageCode
    }

    private fun resolveLanguageCode(localeTag: String): String {
        val normalizedTag = localeTag.replace('_', '-')
        val locale = Locale.forLanguageTag(normalizedTag)

        if (locale.language == "zh") {
            val script = locale.script.uppercase(Locale.ROOT)
            val region = locale.country.uppercase(Locale.ROOT)
            return when {
                script == "HANT" || region in setOf("TW", "HK", "MO") -> "zh-TW"
                else -> "zh"
            }
        }

        val language = locale.language
        return language.takeIf { it in supportedLanguages } ?: "system"
    }
}
