package com.example.nekovideo.components

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.LruCache
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.nekovideo.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

data class MediaItem(
    val path: String,
    val uri: Uri?,
    val isFolder: Boolean,
    val duration: String? = null
)

fun getVideosAndSubfolders(context: Context, folderPath: String, recursive: Boolean = false): List<MediaItem> {
    val mediaItems = mutableListOf<MediaItem>()
    val folder = File(folderPath)

    // Adicionar subpastas
    folder.listFiles { file -> file.isDirectory }?.forEach { subfolder ->
        val hasVideos = context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Video.Media.DATA),
            "${MediaStore.Video.Media.DATA} LIKE ?",
            arrayOf("${subfolder.absolutePath}/%"),
            null
        )?.use { cursor -> cursor.count > 0 } ?: false

        // Verificar se é uma pasta criada pelo app (tem o arquivo marcador)
        val isAppCreatedFolder = File(subfolder, ".nekovideo").exists()

        // Mostrar pasta se tiver vídeos OU for criada pelo app
        if (hasVideos || isAppCreatedFolder) {
            mediaItems.add(MediaItem(subfolder.absolutePath, uri = null, isFolder = true))
        }
    }

    // Adicionar vídeos diretamente na pasta
    val projection = arrayOf(MediaStore.Video.Media.DATA, MediaStore.Video.Media._ID)
    val selection = "${MediaStore.Video.Media.DATA} LIKE ? AND ${MediaStore.Video.Media.DATA} NOT LIKE ?"
    val selectionArgs = arrayOf("$folderPath/%", "$folderPath%/%/%")
    val cursor = context.contentResolver.query(
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        projection,
        selection,
        selectionArgs,
        null
    )
    cursor?.use {
        val dataColumn = it.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
        val idColumn = it.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
        while (it.moveToNext()) {
            val path = it.getString(dataColumn)
            val id = it.getLong(idColumn)
            if (path.startsWith(folderPath)) {
                val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                mediaItems.add(MediaItem(path, uri = uri, isFolder = false))
            }
        }
    }

    // Buscar vídeos em subpastas recursivamente, se necessário
    if (recursive) {
        folder.listFiles { file -> file.isDirectory }?.forEach { subfolder ->
            mediaItems.addAll(getVideosAndSubfolders(context, subfolder.absolutePath, recursive))
        }
    }

    return mediaItems.sortedWith(compareBy({ !it.isFolder }, { it.path }))
}

private val thumbnailCache = LruCache<String, Bitmap?>(50)
private val durationCache = LruCache<String, String?>(50)

private val thumbnailSemaphore = Semaphore(6)

@Composable
fun VideoListScreen(
    folderPath: String,
    onFolderClick: (String) -> Unit,
    selectedItems: MutableList<String>,
    onSelectionChange: (List<String>) -> Unit,
    renameTrigger: Int
) {
    val context = LocalContext.current
    val mediaItems = remember { mutableStateListOf<MediaItem>() }
    val lazyGridState = rememberLazyGridState()

    LaunchedEffect(folderPath, renameTrigger) {
        val items = withContext(Dispatchers.IO) {
            getVideosAndSubfolders(context, folderPath)
        }
        mediaItems.clear()
        mediaItems.addAll(items)
    }

    LaunchedEffect(lazyGridState) {
        snapshotFlow {
            lazyGridState.layoutInfo.visibleItemsInfo to lazyGridState.firstVisibleItemIndex
        }.collect { (visibleItems, firstVisibleIndex) ->
            val preloadRange = (firstVisibleIndex - 6).coerceAtLeast(0)..(firstVisibleIndex + visibleItems.size + 6).coerceAtMost(mediaItems.size - 1)
            preloadRange.forEach { index ->
                val mediaItem = mediaItems.getOrNull(index) ?: return@forEach
                if (!mediaItem.isFolder && thumbnailCache.get(mediaItem.path) == null) {
                    thumbnailSemaphore.withPermit {
                        withContext(Dispatchers.IO) {
                            try {
                                val thumb = ThumbnailManager.getVideoThumbnail(context, mediaItem.path, mediaItem.uri)
                                thumbnailCache.put(mediaItem.path, thumb)
                                val dur = ThumbnailManager.getVideoDuration(context, mediaItem.path)
                                durationCache.put(mediaItem.path, dur)
                                val currentIndex = mediaItems.indexOf(mediaItem)
                                if (currentIndex != -1) {
                                    mediaItems[currentIndex] = mediaItem.copy(duration = dur)
                                }
                            } catch (e: Exception) {
                                println("Error loading for ${mediaItem.path}: ${e.message}")
                            }
                        }
                    }
                }
            }
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), // Mais espaço
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
        state = lazyGridState
    ) {
        items(mediaItems, key = { it.path }) { mediaItem ->
            MediaItemCard(
                mediaItem = mediaItem,
                isSelected = mediaItem.path in selectedItems,
                onClick = { if (selectedItems.isEmpty()) onFolderClick(mediaItem.path) },
                onLongPress = {
                    if (!mediaItem.isFolder) {
                        if (mediaItem.path in selectedItems) {
                            selectedItems.remove(mediaItem.path)
                        } else {
                            selectedItems.add(mediaItem.path)
                        }
                        onSelectionChange(selectedItems.toList())
                    }
                },
                onTap = {
                    if (selectedItems.isNotEmpty() && !mediaItem.isFolder) {
                        if (mediaItem.path in selectedItems) {
                            selectedItems.remove(mediaItem.path)
                        } else {
                            selectedItems.add(mediaItem.path)
                        }
                        onSelectionChange(selectedItems.toList())
                    } else {
                        onFolderClick(mediaItem.path)
                    }
                }
            )
        }
    }
}

@Composable
fun MediaItemCard(
    mediaItem: MediaItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onTap: () -> Unit
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = { onLongPress() },
                    onTap = { onTap() }
                )
            }
            .clip(RoundedCornerShape(16.dp)) // Cantos mais arredondados
            .shadow(4.dp, RoundedCornerShape(16.dp)), // Sombra suave
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (mediaItem.isFolder) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f)),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = "Folder Icon",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .size(48.dp) // Ícone menor e mais elegante
                            .weight(1f)
                    )
                    Text(
                        text = File(mediaItem.path).name,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Medium,
                            shadow = Shadow(
                                color = Color.Black.copy(alpha = 0.3f),
                                offset = Offset(2f, 2f),
                                blurRadius = 4f
                            )
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(thumbnailCache.get(mediaItem.path) ?: R.drawable.default_thumbnail)
                            .size(96, 96)
                            .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                            .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                            .placeholder(R.drawable.default_thumbnail)
                            .error(R.drawable.default_thumbnail)
                            .build(),
                        contentDescription = "Video Thumbnail",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(16.dp))
                            .alpha(if (isSelected) 0.7f else 1.0f)
                    )
                    if (!isSelected) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.4f))
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Play Icon",
                                tint = Color.White,
                                modifier = Modifier
                                    .size(20.dp)
                                    .align(Alignment.Center)
                            )
                        }
                        Text(
                            text = File(mediaItem.path).name,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Medium,
                                shadow = Shadow(
                                    color = Color.Black.copy(alpha = 0.3f),
                                    offset = Offset(2f, 2f),
                                    blurRadius = 4f
                                )
                            ),
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                        mediaItem.duration?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Light
                                ),
                                color = Color.White,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(8.dp)
                                    .background(
                                        Color.Black.copy(alpha = 0.4f),
                                        shape = RoundedCornerShape(4.dp)
                                    )
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Selected",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .align(Alignment.BottomEnd) // Movido para canto inferior
                                .padding(8.dp)
                                .size(20.dp) // Ícone menor
                        )
                    }
                }
            }
        }
    }
}