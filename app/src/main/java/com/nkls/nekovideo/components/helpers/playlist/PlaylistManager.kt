package com.nkls.nekovideo.components.helpers

import android.util.Log

object PlaylistManager {
    private var fullPlaylist: MutableList<String> = mutableListOf()
    private var currentIndex = 0
    private var requestedIndex = 0

    // Estado
    var isShuffleEnabled = false
        private set

    private var originalPlaylist: List<String> = emptyList()


    fun setPlaylist(playlist: List<String>, startIndex: Int = 0, shuffle: Boolean = false) {
        if (playlist.isEmpty()) {
            clear()
            return
        }

        originalPlaylist = playlist
        fullPlaylist = playlist.toMutableList()

        if (shuffle) {
            isShuffleEnabled = true
            fullPlaylist.shuffle()
            currentIndex = 0
            requestedIndex = 0
        } else {
            currentIndex = startIndex.coerceIn(0, fullPlaylist.size - 1)
            requestedIndex = currentIndex
        }

    }

    fun clear() {
        fullPlaylist.clear()
        originalPlaylist = emptyList()
        currentIndex = 0
        requestedIndex = 0
        isShuffleEnabled = false
    }

    fun getCurrentWindow(): List<String> {
        return fullPlaylist.toList()
    }

    fun getWindowStartIndex(): Int {
        return 0
    }

    fun getCurrentIndexInWindow(): Int {
        return currentIndex
    }

    fun next(): NavigationResult {
        if (fullPlaylist.isEmpty()) return NavigationResult.Empty

        val targetIndex = requestedIndex + 1

        if (targetIndex >= fullPlaylist.size) {
            requestedIndex = fullPlaylist.size - 1
            return NavigationResult.EndOfPlaylist
        }

        requestedIndex = targetIndex
        Log.d("PlaylistManager", "Next requested: index $requestedIndex/${fullPlaylist.size} (confirmed=$currentIndex)")
        return NavigationResult.Success(fullPlaylist[requestedIndex], needsWindowUpdate())
    }

    fun previous(): NavigationResult {
        if (fullPlaylist.isEmpty()) return NavigationResult.Empty

        val targetIndex = requestedIndex - 1

        if (targetIndex < 0) {
            requestedIndex = 0
            return NavigationResult.StartOfPlaylist
        }

        requestedIndex = targetIndex
        Log.d("PlaylistManager", "Previous requested: index $requestedIndex/${fullPlaylist.size} (confirmed=$currentIndex)")
        return NavigationResult.Success(fullPlaylist[requestedIndex], needsWindowUpdate())
    }

    fun jumpTo(index: Int): NavigationResult {
        if (fullPlaylist.isEmpty()) return NavigationResult.Empty
        if (index !in fullPlaylist.indices) return NavigationResult.InvalidIndex

        requestedIndex = index
        Log.d("PlaylistManager", "Jump requested: index $requestedIndex/${fullPlaylist.size} (confirmed=$currentIndex)")
        return NavigationResult.Success(fullPlaylist[requestedIndex], true)
    }

    private fun needsWindowUpdate(): Boolean {
        return false
    }

    fun syncLoadedWindow(currentIndexInWindow: Int) {
        currentIndex = currentIndexInWindow.coerceIn(0, (fullPlaylist.size - 1).coerceAtLeast(0))
        requestedIndex = currentIndex
    }

    fun confirmCurrentIndex(index: Int) {
        currentIndex = index.coerceIn(0, (fullPlaylist.size - 1).coerceAtLeast(0))
        requestedIndex = currentIndex
    }

    fun removeCurrent(): RemovalResult {
        if (fullPlaylist.isEmpty()) return RemovalResult.Empty

        val removedPath = fullPlaylist[currentIndex]
        fullPlaylist.removeAt(currentIndex)

        originalPlaylist = originalPlaylist.filter { it != removedPath }

        if (fullPlaylist.isEmpty()) {
            currentIndex = 0
            requestedIndex = 0
            return RemovalResult.PlaylistEmpty
        }

        if (currentIndex >= fullPlaylist.size) {
            currentIndex = fullPlaylist.size - 1
        }

        requestedIndex = currentIndex

        val nextVideo = fullPlaylist.getOrNull(currentIndex)

        return if (nextVideo != null) {
            RemovalResult.Success(nextVideo, true)
        } else {
            RemovalResult.PlaylistEmpty
        }
    }

    fun getTotalSize(): Int = fullPlaylist.size

    fun getCurrentIndex(): Int = currentIndex

    fun getRequestedIndex(): Int = requestedIndex

    fun hasNext(): Boolean = requestedIndex < fullPlaylist.size - 1

    fun hasPrevious(): Boolean = requestedIndex > 0

    fun getFullPlaylist(): List<String> = fullPlaylist.toList()

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

}
