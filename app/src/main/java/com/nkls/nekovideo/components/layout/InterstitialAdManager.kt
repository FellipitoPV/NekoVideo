package com.nkls.nekovideo.components.layout

import android.content.Context
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.nkls.nekovideo.billing.PremiumManager

class InterstitialAdManager(
    private val context: Context,
    private val premiumManager: PremiumManager
) {
    private var interstitialAd: InterstitialAd? = null
    private var isLoading = false
    private var hasShownCastAd = false

    fun loadAd() {
        // Se for premium, não carrega
        if (premiumManager.isPremium.value) return

        if (isLoading || interstitialAd != null) return

        isLoading = true
        val adRequest = AdRequest.Builder().build()

        InterstitialAd.load(
            context,
            "ca-app-pub-3950822881129619/6825220225",
            adRequest,
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    Log.d("InterstitialAd", "Anúncio carregado com sucesso")
                    interstitialAd = ad
                    isLoading = false
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    Log.e("InterstitialAd", "Falha ao carregar: ${error.message}")
                    interstitialAd = null
                    isLoading = false
                }
            }
        )
    }

    fun showAdOnCast(onAdClosed: () -> Unit = {}) {
        // Se for premium, não mostra
        if (premiumManager.isPremium.value) {
            Log.d("InterstitialAd", "Usuário premium - anúncio ignorado")
            onAdClosed()
            return
        }

        // Só mostra se não tiver mostrado ainda nesta sessão
        if (hasShownCastAd) {
            Log.d("InterstitialAd", "Anúncio já foi exibido nesta sessão")
            onAdClosed()
            return
        }

        if (interstitialAd != null) {
            val activity = (context as? android.app.Activity) ?: return

            interstitialAd?.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    Log.d("InterstitialAd", "Anúncio fechado")
                    hasShownCastAd = true
                    interstitialAd = null
                    loadAd()
                    onAdClosed()
                }

                override fun onAdFailedToShowFullScreenContent(error: AdError) {
                    Log.e("InterstitialAd", "Falha ao exibir: ${error.message}")
                    hasShownCastAd = true
                    interstitialAd = null
                    onAdClosed()
                }

                override fun onAdShowedFullScreenContent() {
                    Log.d("InterstitialAd", "Anúncio exibido")
                }
            }

            interstitialAd?.show(activity)
        } else {
            Log.w("InterstitialAd", "Anúncio não está pronto")
            hasShownCastAd = true
            onAdClosed()
        }
    }

    fun resetCastAdFlag() {
        hasShownCastAd = false
    }
}