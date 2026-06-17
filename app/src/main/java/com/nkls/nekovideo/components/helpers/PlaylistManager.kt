package com.nkls.nekovideo.components.helpers

import android.util.Log

object PlaylistManager {
    private var fullPlaylist: MutableList<String> = mutableListOf()
    private var currentIndex = 0

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
        } else {
            currentIndex = startIndex.coerceIn(0, fullPlaylist.size - 1)
        }

    }

    fun clear() {
        fullPlaylist.clear()
        originalPlaylist = emptyList()
        currentIndex = 0
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
        return false
    }

    fun syncLoadedWindow(currentIndexInWindow: Int) {
        currentIndex = currentIndexInWindow.coerceIn(0, (fullPlaylist.size - 1).coerceAtLeast(0))
    }

    fun removeCurrent(): RemovalResult {
        if (fullPlaylist.isEmpty()) return RemovalResult.Empty

        val removedPath = fullPlaylist[currentIndex]
        fullPlaylist.removeAt(currentIndex)

        originalPlaylist = originalPlaylist.filter { it != removedPath }

        if (fullPlaylist.isEmpty()) {
            currentIndex = 0
            return RemovalResult.PlaylistEmpty
        }

        if (currentIndex >= fullPlaylist.size) {
            currentIndex = fullPlaylist.size - 1
        }

        val nextVideo = fullPlaylist.getOrNull(currentIndex)

        return if (nextVideo != null) {
            RemovalResult.Success(nextVideo, true)
        } else {
            RemovalResult.PlaylistEmpty
        }
    }

    fun getTotalSize(): Int = fullPlaylist.size

    fun getCurrentIndex(): Int = currentIndex

    fun hasNext(): Boolean = currentIndex < fullPlaylist.size - 1

    fun hasPrevious(): Boolean = currentIndex > 0

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
