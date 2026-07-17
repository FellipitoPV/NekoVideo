package com.nkls.nekovideo.components.player

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.nkls.nekovideo.MediaPlaybackService
import com.nkls.nekovideo.components.OptimizedThumbnailManager
import com.nkls.nekovideo.components.helpers.FolderLockManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PlaybackThumbnailCoordinator() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val mediaController by MediaControllerManager.mediaController.collectAsStateWithLifecycle()
    var thumbnailArtworkRefreshJob by remember { mutableStateOf<Job?>(null) }

    fun currentMediaUri(controller: MediaController): String? {
        return controller.currentMediaItem?.localConfiguration?.uri?.toString()
    }

    suspend fun loadExistingThumbnail(videoUri: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            if (videoUri.startsWith("locked://")) {
                FolderLockManager.getLockedThumbnail(videoUri.removePrefix("locked://"))
            } else {
                val cleanPath = videoUri.removePrefix("file://")
                OptimizedThumbnailManager.getCachedThumbnail(cleanPath)
                    ?: OptimizedThumbnailManager.loadThumbnailFromDiskSync(context, cleanPath)
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun generateThumbnailIfMissing(videoUri: String): Boolean = withContext(Dispatchers.IO) {
        try {
            if (videoUri.startsWith("locked://")) {
                val path = videoUri.removePrefix("locked://")
                FolderLockManager.getLockedThumbnail(path)
                    ?: FolderLockManager.generateAndSaveLockedThumbnail(path)
            } else {
                val cleanPath = videoUri.removePrefix("file://")
                OptimizedThumbnailManager.getCachedThumbnail(cleanPath)
                    ?: OptimizedThumbnailManager.loadThumbnailFromDiskSync(context, cleanPath)
                    ?: OptimizedThumbnailManager.generateThumbnailSync(context, cleanPath)
            }
        } catch (_: Exception) {
            null
        } != null
    }

    fun scheduleThumbnailGenerationAndArtworkRefresh(controller: MediaController, mediaUri: String) {
        thumbnailArtworkRefreshJob?.cancel()
        thumbnailArtworkRefreshJob = coroutineScope.launch {
            val existingThumbnail = loadExistingThumbnail(mediaUri)
            if (existingThumbnail != null) {
                return@launch
            }

            val generationJob = async { generateThumbnailIfMissing(mediaUri) }
            delay(3000)
            val generated = generationJob.await()
            val sameVideo = MediaControllerManager.getCurrentController() === controller &&
                currentMediaUri(controller) == mediaUri

            if (generated && sameVideo && loadExistingThumbnail(mediaUri) != null) {
                MediaPlaybackService.refreshPlayer(context)
            }
        }
    }

    LaunchedEffect(Unit) {
        MediaControllerManager.connect(context)
    }

    DisposableEffect(mediaController) {
        val controller = mediaController
        if (controller == null) {
            onDispose {
                thumbnailArtworkRefreshJob?.cancel()
                thumbnailArtworkRefreshJob = null
            }
        } else {
            val listener = object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    mediaItem?.localConfiguration?.uri?.toString()?.let { mediaUri ->
                        scheduleThumbnailGenerationAndArtworkRefresh(controller, mediaUri)
                    }
                }
            }

            controller.addListener(listener)
            currentMediaUri(controller)?.let { mediaUri ->
                scheduleThumbnailGenerationAndArtworkRefresh(controller, mediaUri)
            }

            onDispose {
                thumbnailArtworkRefreshJob?.cancel()
                thumbnailArtworkRefreshJob = null
                controller.removeListener(listener)
            }
        }
    }
}
