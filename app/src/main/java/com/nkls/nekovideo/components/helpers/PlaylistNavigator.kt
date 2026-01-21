package com.nkls.nekovideo.components.helpers

import android.content.Context
import android.util.Log
import com.nkls.nekovideo.MediaPlaybackService

/**
 * Centralizador de navegação de playlist.
 * TODAS as ações de next/previous devem passar por aqui para evitar dessincronização.
 *
 * Uso:
 * - PlaylistNavigator.next(context) - avança para o próximo vídeo
 * - PlaylistNavigator.previous(context) - volta para o vídeo anterior
 */
object PlaylistNavigator {

    private const val TAG = "PlaylistNavigator"

    // ✅ Cooldown para evitar comandos duplicados/muito rápidos
    private const val NAVIGATION_COOLDOWN_MS = 300L
    private var lastNavigationTime = 0L
    private var isNavigating = false

    /**
     * Verifica se uma navegação pode ser executada (proteção contra duplicação)
     */
    @Synchronized
    private fun canNavigate(): Boolean {
        val now = System.currentTimeMillis()
        val elapsed = now - lastNavigationTime

        if (isNavigating) {
            Log.d(TAG, "Navegação bloqueada: já está navegando")
            return false
        }

        if (elapsed < NAVIGATION_COOLDOWN_MS) {
            Log.d(TAG, "Navegação bloqueada: cooldown (${elapsed}ms < ${NAVIGATION_COOLDOWN_MS}ms)")
            return false
        }

        return true
    }

    /**
     * Marca o início de uma navegação
     */
    @Synchronized
    private fun startNavigation() {
        isNavigating = true
        lastNavigationTime = System.currentTimeMillis()
    }

    /**
     * Marca o fim de uma navegação
     */
    @Synchronized
    private fun endNavigation() {
        isNavigating = false
    }

    /**
     * Avança para o próximo vídeo da playlist.
     * Atualiza o PlaylistManager E o player de forma sincronizada.
     *
     * @return true se navegou com sucesso, false se já está no fim ou bloqueado
     */
    fun next(context: Context): Boolean {
        if (!canNavigate()) {
            return false
        }

        startNavigation()

        try {
            val indexBefore = PlaylistManager.getCurrentIndex()

            return when (val result = PlaylistManager.next()) {
                is PlaylistManager.NavigationResult.Success -> {
                    val newWindow = PlaylistManager.getCurrentWindow()
                    val currentInWindow = PlaylistManager.getCurrentIndexInWindow()
                    val indexAfter = PlaylistManager.getCurrentIndex()

                    Log.d(TAG, "Next: $indexBefore → $indexAfter (window idx: $currentInWindow)")

                    MediaPlaybackService.updatePlayerWindow(context, newWindow, currentInWindow)
                    true
                }
                PlaylistManager.NavigationResult.EndOfPlaylist -> {
                    Log.d(TAG, "Next: fim da playlist")
                    false
                }
                else -> {
                    Log.d(TAG, "Next: resultado inesperado $result")
                    false
                }
            }
        } finally {
            endNavigation()
        }
    }

    /**
     * Volta para o vídeo anterior da playlist.
     * Atualiza o PlaylistManager E o player de forma sincronizada.
     *
     * @return true se navegou com sucesso, false se já está no início ou bloqueado
     */
    fun previous(context: Context): Boolean {
        if (!canNavigate()) {
            return false
        }

        startNavigation()

        try {
            val indexBefore = PlaylistManager.getCurrentIndex()

            return when (val result = PlaylistManager.previous()) {
                is PlaylistManager.NavigationResult.Success -> {
                    val newWindow = PlaylistManager.getCurrentWindow()
                    val currentInWindow = PlaylistManager.getCurrentIndexInWindow()
                    val indexAfter = PlaylistManager.getCurrentIndex()

                    Log.d(TAG, "Previous: $indexBefore → $indexAfter (window idx: $currentInWindow)")

                    MediaPlaybackService.updatePlayerWindow(context, newWindow, currentInWindow)
                    true
                }
                PlaylistManager.NavigationResult.StartOfPlaylist -> {
                    Log.d(TAG, "Previous: início da playlist")
                    false
                }
                else -> {
                    Log.d(TAG, "Previous: resultado inesperado $result")
                    false
                }
            }
        } finally {
            endNavigation()
        }
    }

    /**
     * Pula para um índice específico da playlist.
     *
     * @param index O índice global na playlist
     * @return true se navegou com sucesso
     */
    fun jumpTo(context: Context, index: Int): Boolean {
        if (!canNavigate()) {
            return false
        }

        startNavigation()

        try {
            val indexBefore = PlaylistManager.getCurrentIndex()

            return when (val result = PlaylistManager.jumpTo(index)) {
                is PlaylistManager.NavigationResult.Success -> {
                    val newWindow = PlaylistManager.getCurrentWindow()
                    val currentInWindow = PlaylistManager.getCurrentIndexInWindow()

                    Log.d(TAG, "JumpTo: $indexBefore → $index (window idx: $currentInWindow)")

                    MediaPlaybackService.updatePlayerWindow(context, newWindow, currentInWindow)
                    true
                }
                else -> {
                    Log.d(TAG, "JumpTo: falhou para índice $index")
                    false
                }
            }
        } finally {
            endNavigation()
        }
    }

    /**
     * Informações úteis para UI
     */
    fun hasNext(): Boolean = PlaylistManager.hasNext()
    fun hasPrevious(): Boolean = PlaylistManager.hasPrevious()
    fun getCurrentIndex(): Int = PlaylistManager.getCurrentIndex()
    fun getTotalSize(): Int = PlaylistManager.getTotalSize()
}
