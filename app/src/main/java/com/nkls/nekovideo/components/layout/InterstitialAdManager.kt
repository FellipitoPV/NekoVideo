package com.nkls.nekovideo.components.layout

import android.content.Context
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback

class InterstitialAdManager(private val context: Context) {
    private var interstitialAd: InterstitialAd? = null
    private var isLoading = false
    private var hasShownCastAd = false

    fun loadAd() {
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
                    hasShownCastAd = true // Marca como exibido
                    interstitialAd = null
                    loadAd() // Recarrega para próxima sessão
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
            hasShownCastAd = true // Marca para não tentar novamente
            onAdClosed()
        }
    }

    fun resetCastAdFlag() {
        // Reseta flag quando desconecta (opcional)
        hasShownCastAd = false
    }
}