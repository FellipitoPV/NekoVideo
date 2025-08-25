package com.nkls.nekovideo.language

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

object LanguageManager {
    private val _currentLanguage = MutableStateFlow("system")
    var currentLanguage: StateFlow<String> = _currentLanguage.asStateFlow()

    // ✅ NOVA: Função para sincronizar StateFlow com valor salvo
    fun initialize(context: Context) {
        val savedLanguage = getCurrentLanguage(context)
        _currentLanguage.value = savedLanguage
    }

    private fun getSystemLocale(context: Context): Locale {
        // Usar o contexto da aplicação (não modificado) para obter locale real do sistema
        val appContext = context.applicationContext
        val systemConfig = appContext.resources.configuration

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            systemConfig.locales.get(0)
        } else {
            systemConfig.locale
        }
    }

    fun setLocale(context: Context, languageCode: String): Context {
        val locale = when (languageCode) {
            "pt" -> Locale("pt", "BR")
            "en" -> Locale("en", "US")
            "es" -> Locale("es", "ES")
            "fr" -> Locale("fr", "FR")
            "system" -> {
                // ✅ Usar função que pega locale real do sistema
                getSystemLocale(context)
            }
            else -> Locale("pt", "BR") // fallback
        }

        Locale.setDefault(locale)

        val config = Configuration(context.resources.configuration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocale(locale)
        } else {
            config.locale = locale
        }

        return context.createConfigurationContext(config)
    }

    fun getCurrentLanguage(context: Context): String {
        val prefs = context.getSharedPreferences("nekovideo_settings", Context.MODE_PRIVATE)
        return prefs.getString("app_language", "system") ?: "system"
    }

    fun saveLanguage(context: Context, languageCode: String) {
        val prefs = context.getSharedPreferences("nekovideo_settings", Context.MODE_PRIVATE)
        prefs.edit().putString("app_language", languageCode).apply()
    }

    fun updateLanguage(context: Context, languageCode: String) {
        saveLanguage(context, languageCode)
        _currentLanguage.value = languageCode
    }

    // Função para obter contexto localizado
    fun getLocalizedContext(context: Context, languageCode: String = getCurrentLanguage(context)): Context {
        return setLocale(context, languageCode)
    }

    fun getContextWithLanguage(context: Context, languageCode: String = getCurrentLanguage(context)): Context {
        return setLocale(context, languageCode)
    }

}