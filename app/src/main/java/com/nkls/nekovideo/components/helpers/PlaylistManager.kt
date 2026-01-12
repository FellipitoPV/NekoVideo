package com.nkls.nekovideo.components.helpers

import android.util.Log

object PlaylistManager {
    private var fullPlaylist: MutableList<String> = mutableListOf()
    private var currentIndex = 0

    // Configurações
    private const val WINDOW_SIZE = 15 // 7 anteriores + atual + 7 próximos
    private const val PRELOAD_THRESHOLD = 3 // Quando faltam 3 vídeos, expande window

    // Estado
    var isShuffleEnabled = false
        private set

    private var originalPlaylist: List<String> = emptyList() // Para desfazer shuffle

    // ===== INICIALIZAÇÃO =====

    fun setPlaylist(playlist: List<String>, startIndex: Int = 0, shuffle: Boolean = false) {
        if (playlist.isEmpty()) {
            clear()
            return
        }

        originalPlaylist = playlist
        fullPlaylist = playlist.toMutableList()

        if (shuffle) {
            // ✅ Shuffle inicial: embaralhar TOTALMENTE sem preservar posição
            isShuffleEnabled = true
            fullPlaylist.shuffle()
            currentIndex = 0
        } else {
            currentIndex = startIndex.coerceIn(0, fullPlaylist.size - 1)
        }

        Log.d("PlaylistManager", "Playlist configurada: ${fullPlaylist.size} vídeos, index: $currentIndex, shuffle: $shuffle")
    }

    fun clear() {
        fullPlaylist.clear()
        originalPlaylist = emptyList()
        currentIndex = 0
        isShuffleEnabled = false
    }

    // ===== WINDOW (JANELA DE CARREGAMENTO) =====

    fun getCurrentWindow(): List<String> {
        if (fullPlaylist.isEmpty()) return emptyList()

        val halfWindow = WINDOW_SIZE / 2
        val start = (currentIndex - halfWindow).coerceAtLeast(0)
        val end = (currentIndex + halfWindow + 1).coerceAtMost(fullPlaylist.size)

        return fullPlaylist.subList(start, end)
    }

    fun getWindowStartIndex(): Int {
        val halfWindow = WINDOW_SIZE / 2
        return (currentIndex - halfWindow).coerceAtLeast(0)
    }

    fun getCurrentIndexInWindow(): Int {
        return currentIndex - getWindowStartIndex()
    }

    // ===== NAVEGAÇÃO =====

    fun next(): NavigationResult {
        if (fullPlaylist.isEmpty()) return NavigationResult.Empty

        currentIndex++

        if (currentIndex >= fullPlaylist.size) {
            currentIndex = fullPlaylist.size - 1
            return NavigationResult.EndOfPlaylist
        }

        Log.d("PlaylistManager", "Next: index $currentIndex/${fullPlaylist.size}")
        return NavigationResult.Success(fullPlaylist[currentIndex], needsWindowUpdate())
    }

    fun previous(): NavigationResult {
        if (fullPlaylist.isEmpty()) return NavigationResult.Empty

        currentIndex--

        if (currentIndex < 0) {
            currentIndex = 0
            return NavigationResult.StartOfPlaylist
        }

        Log.d("PlaylistManager", "Previous: index $currentIndex/${fullPlaylist.size}")
        return NavigationResult.Success(fullPlaylist[currentIndex], needsWindowUpdate())
    }

    fun jumpTo(index: Int): NavigationResult {
        if (fullPlaylist.isEmpty()) return NavigationResult.Empty
        if (index !in fullPlaylist.indices) return NavigationResult.InvalidIndex

        currentIndex = index
        Log.d("PlaylistManager", "Jump to: index $currentIndex/${fullPlaylist.size}")
        return NavigationResult.Success(fullPlaylist[currentIndex], true)
    }

    private fun needsWindowUpdate(): Boolean {
        val halfWindow = WINDOW_SIZE / 2
        val distanceFromStart = currentIndex - getWindowStartIndex()
        val distanceFromEnd = (getWindowStartIndex() + WINDOW_SIZE) - currentIndex

        return distanceFromStart <= PRELOAD_THRESHOLD || distanceFromEnd <= PRELOAD_THRESHOLD
    }

    // ===== REMOÇÃO (DELETE VIDEO) =====

    fun removeCurrent(): RemovalResult {
        if (fullPlaylist.isEmpty()) return RemovalResult.Empty

        val removedPath = fullPlaylist[currentIndex]
        fullPlaylist.removeAt(currentIndex)

        // Atualizar originalPlaylist também
        originalPlaylist = originalPlaylist.filter { it != removedPath }

        if (fullPlaylist.isEmpty()) {
            currentIndex = 0
            return RemovalResult.PlaylistEmpty
        }

        // Ajustar índice se passou do fim
        if (currentIndex >= fullPlaylist.size) {
            currentIndex = fullPlaylist.size - 1
        }

        val nextVideo = fullPlaylist.getOrNull(currentIndex)

        Log.d("PlaylistManager", "Removed video, new size: ${fullPlaylist.size}, index: $currentIndex")

        return if (nextVideo != null) {
            RemovalResult.Success(nextVideo, true)
        } else {
            RemovalResult.PlaylistEmpty
        }
    }

    // ===== SHUFFLE =====

    fun enableShuffle(keepCurrentIndex: Int = currentIndex) {
        if (fullPlaylist.isEmpty()) return

        isShuffleEnabled = true

        // Salvar vídeo atual
        val currentVideo = fullPlaylist.getOrNull(keepCurrentIndex)

        // Embaralhar
        fullPlaylist.shuffle()

        // Mover vídeo atual para índice 0
        if (currentVideo != null) {
            val newIndex = fullPlaylist.indexOf(currentVideo)
            if (newIndex != -1) {
                fullPlaylist.removeAt(newIndex)
                fullPlaylist.add(0, currentVideo)
                currentIndex = 0
            }
        }

        Log.d("PlaylistManager", "Shuffle ativado, index: $currentIndex")
    }

    fun disableShuffle() {
        if (!isShuffleEnabled) return

        isShuffleEnabled = false

        // Restaurar ordem original
        val currentVideo = fullPlaylist.getOrNull(currentIndex)
        fullPlaylist = originalPlaylist.toMutableList()

        // Encontrar vídeo atual na lista original
        if (currentVideo != null) {
            currentIndex = fullPlaylist.indexOf(currentVideo).coerceAtLeast(0)
        }

        Log.d("PlaylistManager", "Shuffle desativado, index: $currentIndex")
    }

    // ===== INFORMAÇÕES =====

    fun getCurrentVideo(): String? = fullPlaylist.getOrNull(currentIndex)

    fun getTotalSize(): Int = fullPlaylist.size

    fun getCurrentIndex(): Int = currentIndex

    fun hasNext(): Boolean = currentIndex < fullPlaylist.size - 1

    fun hasPrevious(): Boolean = currentIndex > 0

    fun getFullPlaylist(): List<String> = fullPlaylist.toList()

    fun getPlaylistInfo(): PlaylistInfo {
        return PlaylistInfo(
            totalVideos = fullPlaylist.size,
            currentIndex = currentIndex,
            isShuffled = isShuffleEnabled,
            hasNext = hasNext(),
            hasPrevious = hasPrevious()
        )
    }

    // ===== CLASSES DE RESULTADO =====

    sealed class NavigationResult {
        data class Success(val videoPath: String, val needsWindowUpdate: Boolean) : NavigationResult()
        object EndOfPlaylist : NavigationResult()
        object StartOfPlaylist : NavigationResult()
        object Empty : NavigationResult()
        object InvalidIndex : NavigationResult()
    }

    sealed class RemovalResult {
        data class Success(val nextVideoPath: String, val needsWindowUpdate: Boolean) : RemovalResult()
        object PlaylistEmpty : RemovalResult()
        object Empty : RemovalResult()
    }

    data class PlaylistInfo(
        val totalVideos: Int,
        val currentIndex: Int,
        val isShuffled: Boolean,
        val hasNext: Boolean,
        val hasPrevious: Boolean
    )
}