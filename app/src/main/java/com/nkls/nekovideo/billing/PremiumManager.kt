package com.nkls.nekovideo.billing

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PremiumManager(context: Context) {
    // =====================================================
    // CONFIGURACOES DE TESTE - Mude conforme necessario
    // =====================================================

    // DEV_MODE = true -> Forca premium (esconde ads)
    private val DEV_MODE = true

    // FORCE_ADS = true -> Ignora premium e mostra ads (para testar ads)
    private val FORCE_ADS = false

    // =====================================================

    private val prefs = context.getSharedPreferences("premium", Context.MODE_PRIVATE)
    private val _isPremium = MutableStateFlow(calculatePremiumState(prefs.getBoolean("is_premium", false)))
    val isPremium: StateFlow<Boolean> = _isPremium.asStateFlow()

    private fun calculatePremiumState(hasPurchased: Boolean): Boolean {
        // FORCE_ADS tem prioridade - sempre mostra ads
        if (FORCE_ADS) return false

        // DEV_MODE forca premium
        if (DEV_MODE) return true

        // Caso normal - usa o estado real da compra
        return hasPurchased
    }

    fun setPremium(premium: Boolean) {
        prefs.edit().putBoolean("is_premium", premium).apply()
        _isPremium.value = calculatePremiumState(premium)
    }
}