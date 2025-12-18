package com.nkls.nekovideo.billing

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PremiumManager(context: Context) {
    // 🔧 MODO DEV - Mude para true durante desenvolvimento
    private val DEV_MODE = false

    private val prefs = context.getSharedPreferences("premium", Context.MODE_PRIVATE)
    private val _isPremium = MutableStateFlow(prefs.getBoolean("is_premium", false) || DEV_MODE)
    val isPremium: StateFlow<Boolean> = _isPremium.asStateFlow()

    fun setPremium(premium: Boolean) {
        prefs.edit().putBoolean("is_premium", premium).apply()
        _isPremium.value = premium || DEV_MODE
    }
}