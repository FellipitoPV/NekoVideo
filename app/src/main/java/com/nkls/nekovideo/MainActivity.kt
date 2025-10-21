package com.nkls.nekovideo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.nkls.nekovideo.components.OptimizedThumbnailManager
import com.nkls.nekovideo.components.helpers.FilesManager
import com.nkls.nekovideo.components.pages.mainscreen.MainScreen
import com.nkls.nekovideo.components.player.MediaControllerManager
import com.nkls.nekovideo.language.LanguageManager
import com.nkls.nekovideo.services.FolderVideoScanner
import com.nkls.nekovideo.theme.NekoVideoTheme
import com.nkls.nekovideo.theme.ThemeManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    // NOVO: Adicionar ThemeManager
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

    private fun handleExternalVideo(videoUri: Uri) {
        lifecycleScope.launch {
            try {
                Log.d("MainActivity", "Processando vídeo externo: $videoUri")

                val videoPath = when (videoUri.scheme) {
                    "file" -> videoUri.toString()
                    "content" -> videoUri.toString()
                    else -> videoUri.toString()
                }

                if (videoPath.isNotEmpty()) {
                    Log.d("MainActivity", "Iniciando MediaPlaybackService com: $videoPath")

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
                        NekoVideoTheme(themeManager = themeManager) {
                            Surface(
                                modifier = Modifier.fillMaxSize(),
                                color = MaterialTheme.colorScheme.background
                            ) {
                                MainScreen(
                                    intent = intent,
                                    themeManager = themeManager,
                                    notificationReceived = notificationIntentReceived,
                                    lastAction = lastIntentAction,
                                    lastTime = lastIntentTime,
                                    externalVideoReceived = true,
                                    autoOpenOverlay = true
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

        // Processar action
        when (intent?.action) {
            "OPEN_PLAYER" -> {
                notificationIntentReceived = true
            }
            "android.intent.action.VIEW" -> {
                // NOVO: Capturar arquivo enviado via "Abrir com"
                val videoUri = intent.data
                if (videoUri != null) {
                    Log.d("MainActivity", "Arquivo recebido via 'Abrir com': $videoUri")
                    handleExternalVideo(videoUri)
                }
            }
            null -> {
                Log.d("MainActivity", "Intent com action NULL")
            }
            else -> {
                Log.d("MainActivity", "Action desconhecida: ${intent.action}")
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

        enableEdgeToEdge()
        FilesManager.SecureFoldersVisibility.resetOnAppStart(this)

        // PROCESSAR intent inicial
        handleNotificationIntent(intent)

        themeManager = ThemeManager(this)

        // 🚀 Inicia scan imediatamente, em background
        lifecycleScope.launch {
            FolderVideoScanner.startScan(this@MainActivity)
        }

        setContent {
            val currentLanguage by LanguageManager.currentLanguage.collectAsState()

            // Criar contexto localizado
            val localizedContext = remember(currentLanguage) {
                LanguageManager.getLocalizedContext(this@MainActivity, currentLanguage)
            }

                NekoVideoTheme(themeManager = themeManager) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        // USAR SUA FUNÇÃO MAINSCREEN ORIGINAL, só adicionando parâmetros de debug
                        MainScreen(
                            intent = intent,
                            themeManager = themeManager,
                            // Debug info
                            notificationReceived = notificationIntentReceived,
                            lastAction = lastIntentAction,
                            lastTime = lastIntentTime
                        )
                    }
                }
        }

        OptimizedThumbnailManager.startPeriodicCleanup()
        Log.d("MainActivity", "✅ onCreate FINALIZADO")
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
        MediaControllerManager.disconnect()
        OptimizedThumbnailManager.stopPeriodicCleanup()
        OptimizedThumbnailManager.clearCache()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.d("MainActivity", "🔄 ==> onNewIntent CHAMADO <==")
        Log.d("MainActivity", "Nova intent: ${intent}")
        Log.d("MainActivity", "Nova action: ${intent?.action}")

        setIntent(intent)

        // PROCESSAR nova intent
        handleNotificationIntent(intent)

        // IMPORTANTE: Recompor sem recreate para não perder estado
        Log.d("MainActivity", "🔄 Recompondo interface...")

        setContent {
            NekoVideoTheme(themeManager = themeManager) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        intent = intent,
                        themeManager = themeManager,
                        // Debug info atualizado
                        notificationReceived = notificationIntentReceived,
                        lastAction = lastIntentAction,
                        lastTime = lastIntentTime,
                        externalVideoReceived = externalVideoReceived
                    )
                }
            }
        }

        Log.d("MainActivity", "✅ onNewIntent FINALIZADO")
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

