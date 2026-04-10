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
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
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

    private var _externalVideoReceived = mutableStateOf(false)

    // Função para resetar (chamada do MainScreen)
    fun resetExternalVideoFlag() {
        externalVideoReceived = false
        _externalVideoReceived.value = false
    }

    // NOVO: Variável para controlar intent da notificação
    private var notificationIntentReceived = false
    private var lastIntentAction: String? = null
    private var lastIntentTime: Long = 0

    private var _notificationReceived = mutableStateOf(false)
    private var _lastIntentAction = mutableStateOf<String?>(null)
    private var _lastIntentTime = mutableStateOf(0L)
    private var _openFolderPath = mutableStateOf<String?>(null)


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

        val autoPipEnabled = getSharedPreferences("nekovideo_settings", Context.MODE_PRIVATE)
            .getBoolean("auto_pip", true)

        if (autoPipEnabled && playerIsVisible && playerIsPlaying) {
            enterPiPMode()
        }
    }

    private fun handleExternalVideo(videoUri: Uri) {
        // Verifica se há múltiplos vídeos via ClipData (alguns file managers enviam assim)
        val clipData = intent?.clipData
        val uris = if (clipData != null && clipData.itemCount > 1) {
            (0 until clipData.itemCount).mapNotNull { clipData.getItemAt(it).uri }
        } else {
            listOf(videoUri)
        }
        handleExternalVideos(uris)
    }

    private fun handleExternalVideos(uris: List<Uri>) {
        lifecycleScope.launch {
            try {
                val paths = uris.map { it.toString() }.filter { it.isNotEmpty() }
                if (paths.isEmpty()) return@launch

                PlaylistManager.setPlaylist(paths, startIndex = 0, shuffle = false)
                MediaPlaybackService.startWithPlaylist(this@MainActivity, paths, 0)

                // Sinaliza para a composição abrir o overlay
                _externalVideoReceived.value = true
            } catch (e: Exception) {
                Log.e("MainActivity", "Erro ao processar vídeos externos", e)
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
            "android.intent.action.SEND_MULTIPLE" -> {
                val uris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                }
                if (!uris.isNullOrEmpty()) {
                    handleExternalVideos(uris)
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

        val currentLanguage = LanguageManager.getCurrentLanguage(this)
        if (currentLanguage != "system") {
            LanguageManager.setLocale(this, currentLanguage)
        }

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK)
        )
        FilesManager.SecureFoldersVisibility.resetOnAppStart(this)

        // PROCESSAR intent inicial
        handleNotificationIntent(intent)

        themeManager = ThemeManager(this)

        // 🚀 Carrega cache e inicia scan se necessário
        FolderVideoScanner.loadCacheFromDisk(this)

        lifecycleScope.launch {
            FolderVideoScanner.startScan(this@MainActivity, forceRefresh = true)
        }

        setContent {
            val currentLanguage by LanguageManager.currentLanguage.collectAsState()

            val notificationState = _notificationReceived.value
            val actionState = _lastIntentAction.value
            val timeState = _lastIntentTime.value
            val folderPathState = _openFolderPath.value
            val externalVideoState = _externalVideoReceived.value

            val localizedContext = remember(currentLanguage) {
                LanguageManager.getLocalizedContext(this@MainActivity, currentLanguage)
            }

            CompositionLocalProvider(LocalContext provides localizedContext) {
                NekoVideoTheme(themeManager = themeManager) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        MainScreen(
                            intent = intent,
                            themeManager = themeManager,
                            notificationReceived = notificationState,
                            lastAction = actionState,
                            lastTime = timeState,
                            openFolderPath = folderPathState,
                            externalVideoReceived = externalVideoState,
                            onFolderPathConsumed = { _openFolderPath.value = null }
                        )
                    }
                }
            }
        }

        OptimizedThumbnailManager.startPeriodicCleanup()

        // One-time migration: limpa cache centralizado antigo
        OptimizedThumbnailManager.clearOldCentralizedCache(this)
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
        // Se o player está pausado quando a Activity é destruída (ex: botão back),
        // para o serviço e zera a playlist para não contaminar um cast futuro
        val controller = MediaControllerManager.getCurrentController()
        if (controller != null && !controller.isPlaying) {
            PlaylistManager.clear()
            MediaPlaybackService.stopService(this)
        }
        super.onDestroy()
        MediaControllerManager.disconnect()
        OptimizedThumbnailManager.stopPeriodicCleanup()
        OptimizedThumbnailManager.clearCache()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.d("BackDebug", "═══════════════════════════════════════")
        Log.d("BackDebug", "📱 onNewIntent - Voltou ao app!")
        Log.d("BackDebug", "   Action: ${intent.action}")
        Log.d("BackDebug", "   Data: ${intent.data}")
        Log.d("BackDebug", "═══════════════════════════════════════")

        setIntent(intent)
        handleNotificationIntent(intent)

        // Atualizar states
        _notificationReceived.value = notificationIntentReceived
        _lastIntentAction.value = lastIntentAction
        _lastIntentTime.value = lastIntentTime

    }

    override fun onResume() {
        super.onResume()
        Log.d("BackDebug", "📱 onResume - App em primeiro plano")

        ContextCompat.registerReceiver(
            this,
            playbackReceiver,
            IntentFilter("PLAYBACK_STATE_CHANGED"),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
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

