package com.nkls.nekovideo.components.helpers

import androidx.media3.session.MediaController
import java.io.File

/** Captures a local playback session and transfers that same session to a Cast receiver. */
object CastPlaybackHandoff {
    data class Session(
        val playlist: List<String>,
        val titles: List<String>,
        val currentIndex: Int,
        val positionMs: Long
    )

    fun capture(controller: MediaController?, shouldMigrate: Boolean): Session? {
        if (!shouldMigrate || controller?.currentMediaItem == null) return null

        val playlist = PlaylistManager.getFullPlaylist().ifEmpty {
            listOfNotNull(
                controller.currentMediaItem?.localConfiguration?.uri?.toString()
                    ?.takeIf(String::isNotBlank)
            )
        }
        if (playlist.isEmpty()) return null

        val currentIndex = PlaylistManager.getCurrentIndex().coerceIn(0, playlist.lastIndex)
        val currentTitle = controller.currentMediaItem?.mediaMetadata?.title?.toString()
        val titles = playlist.map { path ->
            if (path.startsWith("locked://")) {
                val obfuscatedName = File(path.removePrefix("locked://")).name
                LockedPlaybackSession.getOriginalName(obfuscatedName)
                    ?.substringBeforeLast(".") ?: obfuscatedName
            } else if (playlist.size == 1 && !currentTitle.isNullOrBlank()) {
                currentTitle
            } else {
                File(path.removePrefix("file://")).nameWithoutExtension
            }
        }

        return Session(
            playlist = playlist,
            titles = titles,
            currentIndex = currentIndex,
            positionMs = controller.currentPosition.coerceAtLeast(0L)
        )
    }

    fun sendToCast(
        controller: MediaController?,
        castManager: DLNACastManager,
        session: Session
    ) {
        controller?.pause()
        castManager.castPlaylist(
            session.playlist,
            session.titles,
            session.currentIndex,
            session.positionMs
        )
    }
}
