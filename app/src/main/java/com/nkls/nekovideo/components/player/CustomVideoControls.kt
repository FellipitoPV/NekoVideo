package com.nkls.nekovideo.components.player

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.StayCurrentLandscape
import androidx.compose.material.icons.filled.StayCurrentPortrait
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.session.MediaController
import com.nkls.nekovideo.MediaPlaybackService
import com.nkls.nekovideo.components.helpers.FilesManager
import com.nkls.nekovideo.components.helpers.FolderLockManager
import com.nkls.nekovideo.components.helpers.LockedPlaybackSession
import com.nkls.nekovideo.components.helpers.PlaylistManager
import com.nkls.nekovideo.components.helpers.PlaylistNavigator
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun CustomVideoControls(
    mediaController: MediaController?,
    currentPosition: Long,
    duration: Long,
    isPlaying: Boolean,
    videoTitle: String,
    onSeekStart: () -> Unit,
    onSeekEnd: () -> Unit,
    onDeleteClick: () -> Unit,
    onBackClick: () -> Unit,
    resetUITimer: () -> Unit,
    repeatMode: RepeatMode,
    onRepeatModeChange: (RepeatMode) -> Unit,
    isCasting: Boolean,
    onCastClick: () -> Unit,
    rotationMode: RotationMode,
    onRotationModeChange: (RotationMode) -> Unit,
    hasSubtitles: Boolean,
    subtitlesEnabled: Boolean,
    onSubtitlesClick: () -> Unit,
    onPiPClick: () -> Unit,
    needsMetadataFix: Boolean = false,
    onFixMetadataClick: () -> Unit = {}
) {
    val controller = mediaController ?: return
    val context = androidx.compose.ui.platform.LocalContext.current

    // ✅ CENTRALIZADO: Calcular índice global baseado no player + PlaylistManager
    // índice_global = windowStartIndex + currentMediaItemIndex_do_player
    val currentGlobalIndex = PlaylistManager.getWindowStartIndex() + controller.currentMediaItemIndex
    val totalPlaylistSize = PlaylistManager.getTotalSize()

    Box(modifier = Modifier.fillMaxSize()) {
        // Header com gradiente
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.8f),
                            Color.Transparent
                        )
                    )
                )
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBackClick,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                        .size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Text(
                    text = videoTitle,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp)
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Botão de aviso para vídeos com metadados corrompidos
                    if (needsMetadataFix) {
                        IconButton(
                            onClick = {
                                onFixMetadataClick()
                                resetUITimer()
                            },
                            modifier = Modifier
                                .background(Color(0xFFFF5722).copy(alpha = 0.9f), CircleShape)
                                .size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Fix video metadata",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    // Botão PIP
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        IconButton(
                            onClick = onPiPClick, // ✅ SIMPLIFICAR
                            modifier = Modifier
                                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                                .size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PictureInPicture,
                                contentDescription = "Picture in Picture",
                                tint = Color(0xFF00BCD4),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    // Casting
                    AndroidView(
                        factory = { context ->
                            val themedContext = android.view.ContextThemeWrapper(
                                context,
                                com.google.android.material.R.style.Theme_Material3_Dark
                            )

                            androidx.mediarouter.app.MediaRouteButton(themedContext).apply {
                                com.google.android.gms.cast.framework.CastButtonFactory.setUpMediaRouteButton(
                                    themedContext, this
                                )

                                background = android.graphics.drawable.GradientDrawable().apply {
                                    shape = android.graphics.drawable.GradientDrawable.OVAL
                                    setColor(android.graphics.Color.parseColor("#80000000"))
                                }

                                setPadding(12, 12, 12, 12)

                                setOnTouchListener { _, event ->
                                    if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                                        mediaController?.pause()
                                    }
                                    false
                                }

                                post {
                                    if (this is android.widget.ImageView) {
                                        drawable?.let {
                                            androidx.core.graphics.drawable.DrawableCompat.setTint(
                                                it,
                                                android.graphics.Color.WHITE
                                            )
                                        }
                                    }
                                }
                            }
                        },
                        modifier = Modifier.size(48.dp)
                    )

                    IconButton(
                        onClick = onDeleteClick,
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                            .size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }

        // Controles centrais
        Row(
            modifier = Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ✅ BOTÃO PREVIOUS - Usa PlaylistNavigator centralizado
            IconButton(
                onClick = {
                    resetUITimer()
                    PlaylistNavigator.previous(context)
                },
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                    .size(56.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = "Previous",
                    tint = Color.White,
                    modifier = Modifier.size(64.dp)
                )
            }

            // Botão Play/Pause
            IconButton(
                onClick = {
                    if (isPlaying) {
                        controller.pause()
                    } else {
                        controller.play()
                    }
                },
                modifier = Modifier
                    .background(Color.White.copy(alpha = 0.9f), CircleShape)
                    .size(72.dp)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = Color.Black,
                    modifier = Modifier.size(40.dp)
                )
            }

            // ✅ BOTÃO NEXT - Usa PlaylistNavigator centralizado
            IconButton(
                onClick = {
                    resetUITimer()
                    PlaylistNavigator.next(context)
                },
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                    .size(56.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = "Next",
                    tint = Color.White,
                    modifier = Modifier.size(64.dp)
                )
            }
        }

        // Bottom controls
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.8f)
                        )
                    )
                )
                .padding(16.dp)
        ) {
            // Seek bar
            if (duration > 0) {
                var tempPosition by remember { mutableStateOf(currentPosition) }
                var isDragging by remember { mutableStateOf(false) }

                Slider(
                    value = if (isDragging) tempPosition.toFloat() else currentPosition.toFloat(),
                    onValueChange = { newValue ->
                        tempPosition = newValue.toLong()
                        if (!isDragging) {
                            isDragging = true
                            onSeekStart()
                        }
                    },
                    onValueChangeFinished = {
                        controller.seekTo(tempPosition)
                        isDragging = false
                        onSeekEnd()
                    },
                    valueRange = 0f..duration.toFloat(),
                    thumb = {
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .background(Color.White, CircleShape)
                        )
                    },
                    track = { sliderState ->
                        SliderDefaults.Track(
                            sliderState = sliderState,
                            modifier = Modifier.height(4.dp),
                            colors = SliderDefaults.colors(
                                activeTrackColor = Color.White,
                                inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                            ),
                            thumbTrackGapSize = 0.dp,
                            trackInsideCornerSize = 0.dp,
                            drawStopIndicator = null
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatTime(if (isDragging) tempPosition else currentPosition),
                        color = Color.White,
                        fontSize = 14.sp
                    )

                    // ✅ CENTRALIZADO: Indicador calculado diretamente do player + PlaylistManager
                    if (totalPlaylistSize > 1) {
                        Text(
                            text = "${currentGlobalIndex + 1}/${totalPlaylistSize}",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .background(
                                    Color.Black.copy(alpha = 0.3f),
                                    androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = formatTime(duration),
                            color = Color.White,
                            fontSize = 16.sp
                        )

                        // Legendas
                        if (hasSubtitles) {
                            IconButton(
                                onClick = {
                                    onSubtitlesClick()
                                    resetUITimer()
                                },
                                modifier = Modifier
                                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                                    .size(40.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Subtitles,
                                    contentDescription = "Legendas",
                                    tint = if (subtitlesEnabled) Color.Yellow else Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }

                        // Rotação
                        IconButton(
                            onClick = {
                                val nextMode = when (rotationMode) {
                                    RotationMode.AUTO -> RotationMode.PORTRAIT
                                    RotationMode.PORTRAIT -> RotationMode.LANDSCAPE
                                    RotationMode.LANDSCAPE -> RotationMode.AUTO
                                }
                                onRotationModeChange(nextMode)
                                resetUITimer()
                            },
                            modifier = Modifier
                                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                                .size(40.dp)
                        ) {
                            val (icon, contentDescription, iconColor) = when (rotationMode) {
                                RotationMode.AUTO -> Triple(
                                    Icons.Default.ScreenRotation,
                                    "Auto Rotation",
                                    Color(0xFFFF9800)
                                )
                                RotationMode.PORTRAIT -> Triple(
                                    Icons.Default.StayCurrentPortrait,
                                    "Portrait Lock",
                                    Color(0xFF2196F3)
                                )
                                RotationMode.LANDSCAPE -> Triple(
                                    Icons.Default.StayCurrentLandscape,
                                    "Landscape Lock",
                                    Color(0xFF4CAF50)
                                )
                            }

                            Icon(
                                imageVector = icon,
                                contentDescription = contentDescription,
                                tint = iconColor,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        // Repeat mode
                        IconButton(
                            onClick = {
                                val nextMode = when (repeatMode) {
                                    RepeatMode.NONE -> RepeatMode.REPEAT_ALL
                                    RepeatMode.REPEAT_ALL -> RepeatMode.REPEAT_ONE
                                    RepeatMode.REPEAT_ONE -> RepeatMode.NONE
                                }
                                onRepeatModeChange(nextMode)
                                resetUITimer()
                            },
                            modifier = Modifier
                                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                                .size(40.dp)
                        ) {
                            val (icon, contentDescription, iconColor) = when (repeatMode) {
                                RepeatMode.NONE -> Triple(
                                    Icons.Default.PlaylistPlay,
                                    "Normal Play",
                                    Color.White.copy(alpha = 0.7f)
                                )
                                RepeatMode.REPEAT_ALL -> Triple(
                                    Icons.Default.Repeat,
                                    "Repeat Playlist",
                                    Color(0xFF4CAF50)
                                )
                                RepeatMode.REPEAT_ONE -> Triple(
                                    Icons.Default.RepeatOne,
                                    "Repeat One",
                                    Color(0xFF2196F3)
                                )
                            }

                            Icon(
                                imageVector = icon,
                                contentDescription = contentDescription,
                                tint = iconColor,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

suspend fun deleteCurrentVideo(
    context: Context,
    videoPath: String,
    mediaController: MediaController?,
    onVideoDeleted: (String) -> Unit
) {
    try {
        val controller = mediaController ?: return

        val file = File(videoPath)
        val parentPath = file.parent
        val isLockedVideo = parentPath != null && FolderLockManager.isLocked(parentPath)
        val secureFolderPath = FilesManager.SecureStorage.getSecureFolderPath(context)
        val isSecureVideo = videoPath.startsWith(secureFolderPath)

        val success = when {
            isLockedVideo -> deleteLockedFile(context, videoPath)
            isSecureVideo -> deleteSecureFile(context, videoPath)
            else -> deleteRegularFile(context, videoPath)
        }

        if (success) {
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                onVideoDeleted(videoPath)
            }

            when (val result = PlaylistManager.removeCurrent()) {
                is PlaylistManager.RemovalResult.Success -> {
                    // SEMPRE atualizar o player após exclusão usando a função correta
                    val newWindow = PlaylistManager.getCurrentWindow()
                    val currentInWindow = PlaylistManager.getCurrentIndexInWindow()

                    MediaPlaybackService.updatePlaylistAfterDeletion(
                        context,
                        newWindow,
                        currentInWindow
                    )
                    // Não precisa de delay/prepare/play manual -
                    // updatePlaylistAfterDeletion já faz prepare() e playWhenReady = true
                }
                PlaylistManager.RemovalResult.PlaylistEmpty -> {
                    MediaPlaybackService.stopService(context)
                }
                else -> {}
            }

            withContext(kotlinx.coroutines.Dispatchers.Main) {
                android.widget.Toast.makeText(
                    context,
                    "Video deleted successfully",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        } else {
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                android.widget.Toast.makeText(
                    context,
                    "Failed to delete video",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                controller.play()
            }
        }

    } catch (e: Exception) {
        Log.e("VideoPlayer", "Erro ao deletar vídeo", e)
        withContext(kotlinx.coroutines.Dispatchers.Main) {
            android.widget.Toast.makeText(
                context,
                "Error deleting video: ${e.message}",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            mediaController?.play()
        }
    }
}

private fun formatTime(timeMs: Long): String {
    val totalSeconds = timeMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d", minutes, seconds)
}

suspend fun deleteRegularFile(context: Context, videoPath: String): Boolean =
    withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val file = File(videoPath)
            file.delete()
        } catch (e: Exception) {
            Log.e("VideoPlayer", "Erro ao deletar arquivo regular", e)
            false
        }
    }

suspend fun deleteLockedFile(context: Context, videoPath: String): Boolean =
    withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val file = File(videoPath)
            val folderPath = file.parent ?: return@withContext false
            val obfuscatedName = file.name

            // Delete the obfuscated video file
            if (!file.delete()) return@withContext false

            // Update manifest and delete thumbnail
            val password = LockedPlaybackSession.sessionPassword
            if (password != null) {
                val updatedManifest = FolderLockManager.removeFileFromManifest(
                    context, folderPath, obfuscatedName, password
                )
                if (updatedManifest != null) {
                    LockedPlaybackSession.updateManifest(folderPath, updatedManifest)
                }
            }

            true
        } catch (e: Exception) {
            Log.e("VideoPlayer", "Erro ao deletar arquivo de pasta trancada", e)
            false
        }
    }

suspend fun deleteSecureFile(context: Context, videoPath: String): Boolean =
    withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val file = File(videoPath)
            file.delete()
        } catch (e: Exception) {
            Log.e("VideoPlayer", "Erro ao deletar arquivo seguro", e)
            false
        }
    }