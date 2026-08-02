package com.nkls.nekovideo.language

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

object LanguageManager {
    private val supportedLanguages = setOf("pt", "en", "es", "fr", "de", "ru", "hi")
    private val _currentLanguage = MutableStateFlow("system")
    var currentLanguage: StateFlow<String> = _currentLanguage.asStateFlow()

    fun initialize() {
        _currentLanguage.value = getCurrentLanguage()
    }

    fun getCurrentLanguage(context: Context? = null): String {
        val localeTags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        if (localeTags.isBlank()) return "system"

        val primaryTag = localeTags.substringBefore(',')
        val language = Locale.forLanguageTag(primaryTag).language
        return language.takeIf { it in supportedLanguages } ?: "system"
    }

    fun updateLanguage(languageCode: String) {
        val locales = when (languageCode) {
            "system" -> LocaleListCompat.getEmptyLocaleList()
            in supportedLanguages -> LocaleListCompat.forLanguageTags(languageCode)
            else -> LocaleListCompat.getEmptyLocaleList()
        }

        AppCompatDelegate.setApplicationLocales(locales)
        _currentLanguage.value = languageCode
    }
}
