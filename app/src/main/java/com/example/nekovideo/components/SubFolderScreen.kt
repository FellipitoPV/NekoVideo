@file:OptIn(ExperimentalFoundationApi::class)

package com.example.nekovideo.components

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.util.LruCache
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import java.io.File
import kotlin.random.Random

@Immutable
data class MediaItem(
    val path: String,
    val uri: Uri?,
    val isFolder: Boolean,
    val duration: String? = null,
    val name: String = File(path).name,
    val id: String = path.hashCode().toString()
)

// Cache otimizado
private val durationCache = LruCache<String, String?>(200)
private val colorCache = LruCache<String, Color>(1000)
private val thumbnailCache = LruCache<String, Bitmap?>(100)

private fun generateRandomColor(path: String): Color {
    return colorCache.get(path) ?: run {
        val random = Random(path.hashCode())
        val colors = listOf(
            Color(0xFF6366F1), Color(0xFF8B5CF6), Color(0xFFEC4899),
            Color(0xFFEF4444), Color(0xFFF97316), Color(0xFFF59E0B),
            Color(0xFF10B981), Color(0xFF06B6D4), Color(0xFF3B82F6)
        )
        val selectedColor = colors[random.nextInt(colors.size)]
        colorCache.put(path, selectedColor)
        selectedColor
    }
}

fun getVideosAndSubfolders(context: Context, folderPath: String, recursive: Boolean = false): List<MediaItem> {
    val mediaItems = mutableListOf<MediaItem>()
    val folder = File(folderPath)

    folder.listFiles { file -> file.isDirectory }?.forEach { subfolder ->
        val hasVideos = context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Video.Media.DATA),
            "${MediaStore.Video.Media.DATA} LIKE ?",
            arrayOf("${subfolder.absolutePath}/%"),
            null
        )?.use { cursor -> cursor.count > 0 } ?: false

        val isAppCreatedFolder = File(subfolder, ".nekovideo").exists()

        if (hasVideos || isAppCreatedFolder) {
            mediaItems.add(MediaItem(subfolder.absolutePath, uri = null, isFolder = true))
        }
    }

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

    if (recursive) {
        folder.listFiles { file -> file.isDirectory }?.forEach { subfolder ->
            mediaItems.addAll(getVideosAndSubfolders(context, subfolder.absolutePath, recursive))
        }
    }

    return mediaItems.sortedWith(compareBy<MediaItem> { !it.isFolder }
        .thenComparator { a, b ->
            compareNatural(a.name, b.name)
        })
}

// Função para dividir lista em chunks de 3 itens
private fun <T> List<T>.chunked3(): List<List<T>> {
    return this.chunked(3)
}

@Composable
fun SubFolderScreen(
    folderPath: String,
    onFolderClick: (String) -> Unit,
    selectedItems: MutableList<String>,
    onSelectionChange: (List<String>) -> Unit,
    renameTrigger: Int,
    deletedVideoPath: String? = null,
    showThumb: Boolean = true
) {
    val context = LocalContext.current
    val mediaItems = remember { mutableStateListOf<MediaItem>() }
    val lazyListState = rememberLazyListState()
    var isScrollingFast by remember { mutableStateOf(false) }

    // Detecta velocidade de rolagem - otimizado
    LaunchedEffect(lazyListState) {
        snapshotFlow {
            lazyListState.isScrollInProgress to lazyListState.firstVisibleItemScrollOffset
        }
            .distinctUntilChanged()
            .collect { (isScrolling, offset) ->
                isScrollingFast = isScrolling && offset > 30
            }
    }

    // Carregamento inicial otimizado
    LaunchedEffect(folderPath, renameTrigger) {
        Log.d("NekoVideo", "Carregando items para $folderPath")
        val items = withContext(Dispatchers.IO) {
            getVideosAndSubfolders(context, folderPath)
        }
        mediaItems.clear()
        mediaItems.addAll(items)
        Log.d("NekoVideo", "Carregados ${items.size} items")
    }

    // Remoção de item otimizada
    LaunchedEffect(deletedVideoPath) {
        deletedVideoPath?.let { deletedPath ->
            val indexToRemove = mediaItems.indexOfFirst { it.path == deletedPath }
            if (indexToRemove != -1) {
                mediaItems.removeAt(indexToRemove)
                selectedItems.remove(deletedPath)
                onSelectionChange(selectedItems.toList())
            }
        }
    }

    // LazyColumn com Row manual (copiado do SecureFolderScreen)
    LazyColumn(
        state = lazyListState,
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        val chunkedItems = mediaItems.chunked3()
        items(
            items = chunkedItems,
            key = { chunk -> chunk.joinToString(",") { it.id } }
        ) { rowItems ->
            MediaItemRow(
                items = rowItems,
                selectedItems = selectedItems,
                isScrollingFast = isScrollingFast,
                showThumb = showThumb,
                onFolderClick = onFolderClick,
                onSelectionChange = onSelectionChange
            )
        }
    }
}

@Composable
private fun MediaItemRow(
    items: List<MediaItem>,
    selectedItems: MutableList<String>,
    isScrollingFast: Boolean,
    showThumb: Boolean,
    onFolderClick: (String) -> Unit,
    onSelectionChange: (List<String>) -> Unit
) {
    // Determina o tipo de seleção baseado no primeiro item selecionado
    val selectionType = remember(selectedItems.size) {
        if (selectedItems.isEmpty()) {
            null
        } else {
            // Busca o primeiro item selecionado para determinar se é pasta ou vídeo
            items.find { it.path in selectedItems }?.isFolder
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items.forEach { mediaItem ->
            OptimizedMediaItemCard(
                mediaItem = mediaItem,
                isSelected = mediaItem.path in selectedItems,
                isScrollingFast = isScrollingFast,
                showThumb = showThumb,
                modifier = Modifier.weight(1f),
                onLongPress = {
                    // Permite seleção apenas se não há itens selecionados ou se é do mesmo tipo
                    if (selectedItems.isEmpty() || selectionType == mediaItem.isFolder) {
                        if (mediaItem.path in selectedItems) {
                            selectedItems.remove(mediaItem.path)
                        } else {
                            selectedItems.add(mediaItem.path)
                        }
                        onSelectionChange(selectedItems.toList())
                    }
                },
                onTap = {
                    if (selectedItems.isNotEmpty()) {
                        // Se há itens selecionados, verifica se pode selecionar este item
                        if (selectionType == mediaItem.isFolder) {
                            if (mediaItem.path in selectedItems) {
                                selectedItems.remove(mediaItem.path)
                            } else {
                                selectedItems.add(mediaItem.path)
                            }
                            onSelectionChange(selectedItems.toList())
                        }
                        // Se não for do mesmo tipo, ignora o clique
                    } else {
                        // Comportamento normal quando não há seleção
                        onFolderClick(mediaItem.path)
                    }
                }
            )
        }

        // Preenche espaços vazios se a linha não tiver 3 itens
        repeat(3 - items.size) {
            Box(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun OptimizedMediaItemCard(
    mediaItem: MediaItem,
    isSelected: Boolean,
    isScrollingFast: Boolean,
    showThumb: Boolean,
    modifier: Modifier = Modifier,
    onLongPress: () -> Unit,
    onTap: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var thumbnailBitmap by remember(mediaItem.id) { mutableStateOf<Bitmap?>(null) }
    var videoDuration by remember(mediaItem.id) { mutableStateOf<String?>(null) }
    var thumbnailState by remember(mediaItem.id) { mutableStateOf(ThumbnailState.IDLE) }
    var thumbnailJob by remember(mediaItem.id) { mutableStateOf<Job?>(null) }

    // Pre-computed valores
    val randomColor = remember(mediaItem.path, showThumb) {
        if (!showThumb && !mediaItem.isFolder) {
            generateRandomColor(mediaItem.path)
        } else Color.Transparent
    }

    // Carregamento condicional otimizado (copiado do SecureFolderScreen)
    LaunchedEffect(mediaItem.id, isScrollingFast, thumbnailState) {
        if (!mediaItem.isFolder && mediaItem.uri != null && showThumb && !isScrollingFast) {
            // Cancela job anterior
            thumbnailJob?.cancel()

            // Verifica cache primeiro
            val cachedThumbnail = thumbnailCache.get(mediaItem.path)
            if (cachedThumbnail != null) {
                thumbnailBitmap = cachedThumbnail
                thumbnailState = ThumbnailState.LOADED
                videoDuration = durationCache.get(mediaItem.path)
                return@LaunchedEffect
            }

            if (thumbnailState == ThumbnailState.IDLE && thumbnailBitmap == null) {
                thumbnailState = ThumbnailState.WAITING

                thumbnailJob = coroutineScope.launch(Dispatchers.IO) {
                    try {
                        delay(200L)

                        OptimizedThumbnailManager.loadVideoMetadataWithDelay(
                            context = context,
                            videoUri = mediaItem.uri,
                            videoPath = mediaItem.path,
                            imageLoader = null,
                            delayMs = 0L,
                            onMetadataLoaded = { metadata ->
                                thumbnailBitmap = metadata.thumbnail
                                videoDuration = metadata.duration
                                thumbnailState = ThumbnailState.LOADED
                                metadata.thumbnail?.let { thumbnailCache.put(mediaItem.path, it) }
                                metadata.duration?.let { durationCache.put(mediaItem.path, it) }
                            },
                            onCancelled = {
                                if (thumbnailCache.get(mediaItem.path) == null) {
                                    thumbnailBitmap = null
                                    videoDuration = null
                                    thumbnailState = ThumbnailState.IDLE
                                }
                            },
                            onStateChanged = { newState ->
                                thumbnailState = newState
                            }
                        )
                    } catch (e: Exception) {
                        thumbnailState = ThumbnailState.IDLE
                    }
                }
            }
        }
    }

    // Cleanup job
    DisposableEffect(mediaItem.id) {
        onDispose { thumbnailJob?.cancel() }
    }

    // Card otimizado (copiado do SecureFolderScreen)
    Card(
        modifier = modifier
            .aspectRatio(1f)
            .combinedClickable(
                onClick = onTap,
                onLongClick = onLongPress
            )
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = if (isSelected) 2.dp else 0.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = RoundedCornerShape(12.dp)
            ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (mediaItem.isFolder) {
                FolderContent(mediaItem.name)
            } else {
                VideoContent(
                    mediaItem = mediaItem,
                    thumbnailBitmap = thumbnailBitmap,
                    videoDuration = videoDuration,
                    thumbnailState = thumbnailState,
                    isSelected = isSelected,
                    showThumb = showThumb,
                    randomColor = randomColor
                )
            }
        }
    }
}

@Composable
private fun FolderContent(name: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f)),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .size(40.dp)
                .weight(1f)
        )
        Text(
            text = name,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun VideoContent(
    mediaItem: MediaItem,
    thumbnailBitmap: Bitmap?,
    videoDuration: String?,
    thumbnailState: ThumbnailState,
    isSelected: Boolean,
    showThumb: Boolean,
    randomColor: Color
) {
    Box(modifier = Modifier.fillMaxSize()) {
        when {
            !showThumb -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(randomColor)
                        .alpha(if (isSelected) 0.7f else 1.0f)
                )
            }
            thumbnailBitmap != null -> {
                Image(
                    bitmap = thumbnailBitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(12.dp))
                        .alpha(if (isSelected) 0.7f else 1.0f)
                )
            }
            thumbnailState == ThumbnailState.LOADING -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            else -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.VideoFile,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.size(56.dp)
                    )
                }
            }
        }

        // Overlay otimizado
        if (!isSelected) {
            // Play button
            if (thumbnailBitmap != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.4f))
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier
                            .size(16.dp)
                            .align(Alignment.Center)
                    )
                }
            }

            // Nome do arquivo
            Text(
                text = mediaItem.name,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = FontWeight.Medium,
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.5f),
                        offset = Offset(1f, 1f),
                        blurRadius = 2f
                    )
                ),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )

            // Duração
            if (thumbnailBitmap != null) {
                val duration = videoDuration ?: durationCache.get(mediaItem.path)
                duration?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .background(
                                color = Color.Black.copy(alpha = 0.4f),
                                shape = RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }
        }

        // Ícone de seleção
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .size(18.dp)
            )
        }
    }
}