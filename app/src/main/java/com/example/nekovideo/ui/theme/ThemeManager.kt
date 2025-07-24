package com.example.nekovideo.ui.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class ThemeManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("nekovideo_settings", Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(getDarkMode())
    val themeMode: StateFlow<String> = _themeMode

    private fun getDarkMode(): String {
        return prefs.getString("dark_mode", "system") ?: "system"
    }

    fun updateTheme(mode: String) {
        prefs.edit().putString("dark_mode", mode).apply()
        _themeMode.value = mode
    }

    @Composable
    fun shouldUseDarkTheme(): Boolean {
        val currentMode by themeMode.collectAsState()
        return when (currentMode) {
            "light" -> false
            "dark" -> true
            "system" -> isSystemInDarkTheme()
            else -> isSystemInDarkTheme()
        }
    }
}