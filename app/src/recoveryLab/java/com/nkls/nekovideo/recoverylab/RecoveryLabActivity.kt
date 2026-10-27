package com.nkls.nekovideo.recoverylab

import com.nkls.nekovideo.components.helpers.PortableVideoStore

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import java.io.File
import java.util.concurrent.Executors

/** Separate launcher; never starts MainActivity, scans the real library or migrates old videos. */
@androidx.annotation.OptIn(UnstableApi::class)
class RecoveryLabActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private var player: ExoPlayer? = null
    private lateinit var status: TextView
    private lateinit var playerView: PlayerView
    private lateinit var experiment: DeviceRecoveryExperiment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        experiment = DeviceRecoveryExperiment(this)
        val padding = (12 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(padding + bars.left, padding + bars.top,
                    padding + bars.right, padding + bars.bottom)
                insets
            }
        }
        layout.addView(TextView(this).apply {
            text = "NekoVideo Recovery Lab\nTeste isolado com cópias. Proteção atual de 8 KB + recuperação interna."
            textSize = 18f
        })
        layout.addView(Button(this).apply {
            text = "Testar cópias dos memes"
            setOnClickListener {
                isEnabled = false
                status.text = "Testando somente a área do laboratório…"
                executor.execute {
                    val result = runCatching {
                        experiment.prepare()
                        experiment.simulateExternalMove()
                        experiment.verify()
                    }.fold({ it }, { "Falha: ${it.message}" })
                    runOnUiThread { status.text = result; isEnabled = true }
                }
            }
        })
        layout.addView(Button(this).apply {
            text = "Reproduzir primeiro vídeo pelo fallback"
            setOnClickListener { playFallback() }
        })
        playerView = PlayerView(this)
        layout.addView(playerView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 500))
        status = TextView(this).apply {
            text = runCatching {
                val advanced = File(experiment.root, "advanced-report.txt")
                if (advanced.isFile) "Testes ampliados concluídos\n\n${advanced.readText()}"
                else File(experiment.activeRun(), "verification-report.txt").readText()
            }
                .getOrElse {
                    "Entrada de cópias: ${experiment.input.absolutePath}\n" +
                        "Senha descartável de teste: ${DeviceRecoveryExperiment.PASSWORD}"
                }
            setTextIsSelectable(true)
        }
        layout.addView(ScrollView(this).apply { addView(status) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(layout)
        layout.requestApplyInsets()
    }

    private fun playFallback() {
        executor.execute {
            val result = runCatching {
                val folder = File(experiment.activeRun(), "externally-moved")
                val file = checkNotNull(folder.listFiles()?.firstOrNull { it.name.startsWith("renamed-") })
                val metadata = PortableVideoStore.Keyring(DeviceRecoveryExperiment.PASSWORD.toCharArray()).use {
                    experiment.lab.resolve(file, null, it).metadata
                }
                file to metadata
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess { (file, metadata) ->
                    startPlayback(file, metadata)
                    status.text = "Reprodução pelo fallback: ${metadata.originalName}\nUse a barra do player para testar avanço e retrocesso."
                }.onFailure { status.text = "Execute o teste primeiro. ${it.message}" }
            }
        }
    }

    internal fun startPlayback(file: File, metadata: PortableVideoStore.Metadata,
                               listener: Player.Listener? = null, muted: Boolean = false): ExoPlayer {
        player?.release()
        val mediaSource = ProgressiveMediaSource.Factory {
            RecoveryLabDataSource(file, metadata)
        }.createMediaSource(MediaItem.fromUri(android.net.Uri.fromFile(file)))
        return ExoPlayer.Builder(this).build().also {
            player = it
            playerView.player = it
            if (listener != null) it.addListener(listener)
            it.volume = if (muted) 0f else 1f
            it.setMediaSource(mediaSource)
            it.prepare()
            it.playWhenReady = true
        }
    }

    override fun onStop() { playerView.player = null; player?.release(); player = null; super.onStop() }
    override fun onDestroy() { executor.shutdown(); super.onDestroy() }
}
