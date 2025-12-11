package com.nkls.nekovideo.components.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.nkls.nekovideo.billing.PremiumManager

@Composable
fun BannerAd(
    isPremium: Boolean,
    modifier: Modifier = Modifier
) {
    // Se for premium, não mostra nada
    if (isPremium) return

    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .height(75.dp)
            .background(Color.Black),
        factory = { context ->
            AdView(context).apply {
                setAdSize(AdSize.BANNER)
                adUnitId = "ca-app-pub-3950822881129619/1173438298"
                loadAd(AdRequest.Builder().build())
            }
        }
    )
}