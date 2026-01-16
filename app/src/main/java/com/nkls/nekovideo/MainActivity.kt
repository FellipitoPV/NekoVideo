package com.nkls.nekovideo

import android.app.PictureInPictureParams
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.nkls.nekovideo.billing.BillingManager
import com.nkls.nekovideo.billing.PremiumManager
import com.nkls.nekovideo.components.OptimizedThumbnailManager
import com.nkls.nekovideo.components.helpers.FilesManager
import com.nkls.nekovideo.components.helpers.PlaylistManager
import com.nkls.nekovideo.components.pages.mainscreen.MainScreen
import com.nkls.nekovideo.components.player.MediaControllerManager
import com.nkls.nekovideo.language.LanguageManager
import com.nkls.nekovideo.services.FolderVideoScanner
import com.nkls.nekovideo.theme.NekoVideoTheme
import com.nkls.nekovideo.theme.ThemeManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var themeManager: ThemeManager

    var externalVideoReceived = false
        private set

    // Função para resetar (chamada do MainScreen)
    fun resetExternalVideoFlag() {
        externalVideoReceived = false
    }

    // NOVO: Variável para controlar intent da notificação
    private var notificationIntentReceived = false
    private var lastIntentAction: String? = null
    private var lastIntentTime: Long = 0

    private var _notificationReceived = mutableStateOf(false)
    private var _lastIntentAction = mutableStateOf<String?>(null)
    private var _lastIntentTime = mutableStateOf(0L)


    private lateinit var billingManager: BillingManager
    private lateinit var premiumManager: PremiumManager

    private var _isInPiPMode = mutableStateOf(false)
    val isInPiPMode: Boolean get() = _isInPiPMode.value
    val isInPiPModeState get() = _isInPiPMode  // ✅ Expor State para Compose observar

    private var playerIsVisible = false
    private var playerIsPlaying = false

    // ✅ FUNÇÕES PIP
    fun enterPiPMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build()

                enterPictureInPictureMode(params)
            } catch (e: Exception) {
                Log.e("MainActivity", "Erro ao entrar em PiP: ${e.message}")
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun updatePiPParams(params: PictureInPictureParams) {
        try {
            setPictureInPictureParams(params)
        } catch (e: Exception) {
            Log.e("MainActivity", "Erro ao atualizar PiP params: ${e.message}")
        }
    }

    fun setPlayerState(isVisible: Boolean, isPlaying: Boolean) {
        playerIsVisible = isVisible
        playerIsPlaying = isPlaying
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        _isInPiPMode.value = isInPictureInPictureMode

        Log.d("MainActivity", "PiP mode: $isInPictureInPictureMode")
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()

        // Só entra em PiP se o player estiver visível E tocando
        if (playerIsVisible && playerIsPlaying) {
            enterPiPMode()
        }
    }

    private fun handleExternalVideo(videoUri: Uri) {
        lifecycleScope.launch {
            try {

                val videoPath = when (videoUri.scheme) {
                    "file" -> videoUri.toString()
                    "content" -> videoUri.toString()
                    else -> videoUri.toString()
                }

                if (videoPath.isNotEmpty()) {
                    // ✅ Configurar PlaylistManager ANTES de iniciar o player
                    PlaylistManager.setPlaylist(listOf(videoPath), startIndex = 0, shuffle = false)

                    // Iniciar o serviço
                    MediaPlaybackService.startWithPlaylist(
                        this@MainActivity,
                        listOf(videoPath),
                        0
                    )

                    // Aguardar serviço inicializar
                    delay(800)

                    // FORÇAR abertura do overlay via recomposição
                    setContent {
                        val notificationState = _notificationReceived.value
                        val actionState = _lastIntentAction.value
                        val timeState = _lastIntentTime.value

                        NekoVideoTheme(themeManager = themeManager) {
                            Surface(
                                modifier = Modifier.fillMaxSize(),
                                color = MaterialTheme.colorScheme.background
                            ) {
                                MainScreen(
                                    intent = intent,
                                    themeManager = themeManager,
                                    premiumManager = premiumManager, // ADICIONAR ESTA LINHA
                                    billingManager = billingManager,
                                    notificationReceived = notificationState,
                                    lastAction = actionState,
                                    lastTime = timeState
                                )
                            }
                        }
                    }

                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Erro ao processar vídeo externo", e)
            }
        }
    }

    private fun handleNotificationIntent(intent: Intent?) {
        val currentTime = System.currentTimeMillis()
        lastIntentTime = currentTime
        lastIntentAction = intent?.action


        when (intent?.action) {
            "OPEN_PLAYER" -> {
                notificationIntentReceived = true
            }
            "android.intent.action.VIEW" -> {
                val videoUri = intent.data
                if (videoUri != null) {
                    handleExternalVideo(videoUri)
                }
            }
            null -> {
                Log.d("MainActivity", "   ⚠️ Action NULL")
            }
            else -> {
                Log.d("MainActivity", "   ❓ Action desconhecida: ${intent.action}")
            }
        }
    }

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(newBase?.let { context ->
            val languageCode = LanguageManager.getCurrentLanguage(context)
            LanguageManager.setLocale(context, languageCode)
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        premiumManager = PremiumManager(this)
        billingManager = BillingManager(this, premiumManager)

        billingManager.restorePurchases()

        val currentLanguage = LanguageManager.getCurrentLanguage(this)
        if (currentLanguage != "system") {
            LanguageManager.setLocale(this, currentLanguage)
        }

        enableEdgeToEdge()
        FilesManager.SecureFoldersVisibility.resetOnAppStart(this)

        // PROCESSAR intent inicial
        handleNotificationIntent(intent)

        themeManager = ThemeManager(this)

        // 🚀 Carrega cache e inicia scan se necessário
        FolderVideoScanner.loadCacheFromDisk(this)

        lifecycleScope.launch {
            FolderVideoScanner.startScan(this@MainActivity)
        }

        setContent {
            val currentLanguage by LanguageManager.currentLanguage.collectAsState()

            val notificationState = _notificationReceived.value
            val actionState = _lastIntentAction.value
            val timeState = _lastIntentTime.value

            val localizedContext = remember(currentLanguage) {
                LanguageManager.getLocalizedContext(this@MainActivity, currentLanguage)
            }

            NekoVideoTheme(themeManager = themeManager) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        intent = intent,
                        themeManager = themeManager,
                        premiumManager = premiumManager, // ADICIONAR ESTA LINHA
                        billingManager = billingManager,
                        notificationReceived = notificationState,
                        lastAction = actionState,
                        lastTime = timeState
                    )
                }
            }
        }

        OptimizedThumbnailManager.startPeriodicCleanup()
    }

    fun keepScreenOn(keep: Boolean) {
        if (keep) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private val playbackReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "PLAYBACK_STATE_CHANGED") {
                val isPlaying = intent.getBooleanExtra("IS_PLAYING", false)
                keepScreenOn(isPlaying)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(
            this,
            playbackReceiver,
            IntentFilter("PLAYBACK_STATE_CHANGED"),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        billingManager.restorePurchases()
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(playbackReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver já foi removido
        }
        keepScreenOn(false)
        OptimizedThumbnailManager.cancelLoading("")
    }

    override fun onDestroy() {
        super.onDestroy()
        billingManager.destroy()
        MediaControllerManager.disconnect()
        OptimizedThumbnailManager.stopPeriodicCleanup()
        OptimizedThumbnailManager.clearCache()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        setIntent(intent)
        handleNotificationIntent(intent)

        // Atualizar states
        _notificationReceived.value = notificationIntentReceived
        _lastIntentAction.value = lastIntentAction
        _lastIntentTime.value = lastIntentTime

    }

}

fun Context.findActivity(): ComponentActivity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is ComponentActivity) return context
        context = context.baseContext
    }
    return null
}

