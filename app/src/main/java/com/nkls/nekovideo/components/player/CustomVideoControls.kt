package com.nkls.nekovideo.components.player

import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
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
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.nkls.nekovideo.MediaPlaybackService
import com.nkls.nekovideo.components.helpers.FilesManager
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.StayCurrentPortrait
import androidx.compose.material.icons.filled.StayCurrentLandscape
import androidx.compose.material.icons.filled.Subtitles

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
    // NOVOS PARÂMETROS PARA LEGENDAS:
    hasSubtitles: Boolean,
    subtitlesEnabled: Boolean,
    onSubtitlesClick: () -> Unit
) {
    val controller = mediaController ?: return

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

                // Botões do header: Repetição e Delete
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {

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

                                // CORRIGIDO: Usar setOnTouchListener para interceptar antes do click
                                setOnTouchListener { _, event ->
                                    if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                                        mediaController?.pause()
                                    }
                                    false // Retornar false permite o evento continuar para o botão
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

                    // Ícone delete
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
            // Botão Previous
            IconButton(
                onClick = {
                    if (controller.hasPreviousMediaItem()) {
                        resetUITimer()
                        controller.seekToPreviousMediaItem()
                    }
                },
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                    .size(56.dp),
                enabled = controller.hasPreviousMediaItem()
            ) {
                Icon(
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = "Previous",
                    tint = if (controller.hasPreviousMediaItem()) Color.White else Color.Gray,
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

            // Botão Next
            IconButton(
                onClick = {
                    if (controller.hasNextMediaItem()) {
                        resetUITimer()
                        controller.seekToNextMediaItem()
                    }
                },
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                    .size(56.dp),
                enabled = controller.hasNextMediaItem()
            ) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = "Next",
                    tint = if (controller.hasNextMediaItem()) Color.White else Color.Gray,
                    modifier = Modifier.size(64.dp)
                )
            }
        }



        // Bottom controls com gradiente
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
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                // Time display com botão de repetição
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

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)  // Espaçamento maior também
                    ) {
                        Text(
                            text = formatTime(duration),
                            color = Color.White,
                            fontSize = 16.sp  // Era 14.sp
                        )

                        // Botão de legendas (só aparece se houver legendas disponíveis)
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
                                    imageVector = Icons.Default.Subtitles,  // Ícone nativo do Material
                                    contentDescription = "Legendas",
                                    tint = if (subtitlesEnabled) Color.Yellow else Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }

                        // Botão de rotação
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
                                .size(40.dp)  // Era 32.dp
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
                                modifier = Modifier.size(24.dp)  // Era 18.dp
                            )
                        }

                        // Botão de modo de repetição
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
                                .size(40.dp)  // Era 32.dp
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
                                modifier = Modifier.size(24.dp)  // Era 18.dp
                            )
                        }
                    }
                }
            }
        }
    }
}

// Função para deletar vídeo atual e atualizar playlist (mesmo código anterior)
suspend fun deleteCurrentVideo(
    context: Context,
    videoPath: String,
    mediaController: MediaController?,
    onVideoDeleted: (String) -> Unit
) {
    try {
        val controller = mediaController ?: return
        val currentIndex = controller.currentMediaItemIndex
        val totalItems = controller.mediaItemCount

        val secureFolderPath = FilesManager.SecureStorage.getSecureFolderPath(context)
        val isSecureVideo = videoPath.startsWith(secureFolderPath)

        val updatedPlaylist = mutableListOf<String>()
        for (i in 0 until totalItems) {
            if (i != currentIndex) {
                val itemUri = controller.getMediaItemAt(i).localConfiguration?.uri.toString()
                updatedPlaylist.add(itemUri)
            }
        }

        val nextIndex = when {
            updatedPlaylist.isEmpty() -> {
                return
            }
            currentIndex >= updatedPlaylist.size -> updatedPlaylist.size - 1
            else -> currentIndex
        }

        val success = if (isSecureVideo) {
            deleteSecureFile(context, videoPath)
        } else {
            deleteRegularFile(context, videoPath)
        }

        if (success) {

            withContext(kotlinx.coroutines.Dispatchers.Main) {
                onVideoDeleted(videoPath)
            }

            if (updatedPlaylist.isNotEmpty()) {
                MediaPlaybackService.updatePlaylistAfterDeletion(context, updatedPlaylist, nextIndex)
            } else {
                MediaPlaybackService.stopService(context)
            }

            withContext(kotlinx.coroutines.Dispatchers.Main) {
                android.widget.Toast.makeText(context, "Video deleted successfully", android.widget.Toast.LENGTH_SHORT).show()
            }
        } else {
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                android.widget.Toast.makeText(context, "Failed to delete video", android.widget.Toast.LENGTH_SHORT).show()
                controller.play()
            }
        }

    } catch (e: Exception) {
        Log.e("VideoPlayer", "Erro ao deletar vídeo", e)
        withContext(kotlinx.coroutines.Dispatchers.Main) {
            android.widget.Toast.makeText(context, "Error deleting video: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
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