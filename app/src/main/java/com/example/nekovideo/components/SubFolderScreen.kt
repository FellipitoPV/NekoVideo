package com.example.nekovideo.components

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.LruCache
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.random.Random

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

        val isAppCreatedFolder = File(subfolder, ".nekovideo").exists()

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

    if (recursive) {
        folder.listFiles { file -> file.isDirectory }?.forEach { subfolder ->
            mediaItems.addAll(getVideosAndSubfolders(context, subfolder.absolutePath, recursive))
        }
    }

    return mediaItems.sortedWith(compareBy<MediaItem> { !it.isFolder }
        .thenComparator { a, b ->
            compareNatural(File(a.path).name, File(b.path).name)
        })
}

private val durationCache = LruCache<String, String?>(100)
private val colorCache = LruCache<String, Color>(500)

// Função para gerar cor aleatória baseada no path do arquivo
private fun generateRandomColor(path: String): Color {
    return colorCache.get(path) ?: run {
        val random = Random(path.hashCode())
        val colors = listOf(
            Color(0xFF6366F1), Color(0xFF8B5CF6), Color(0xFFEC4899),
            Color(0xFFEF4444), Color(0xFFF97316), Color(0xFFF59E0B),
            Color(0xFF10B981), Color(0xFF06B6D4), Color(0xFF3B82F6),
            Color(0xFF6366F1), Color(0xFF8B5CF6), Color(0xFFA855F7)
        )
        val selectedColor = colors[random.nextInt(colors.size)]
        colorCache.put(path, selectedColor)
        selectedColor
    }
}

@Composable
fun SubFolderScreen(
    folderPath: String,
    onFolderClick: (String) -> Unit,
    selectedItems: MutableList<String>,
    onSelectionChange: (List<String>) -> Unit,
    renameTrigger: Int,
    deletedVideoPath: String? = null,
    showThumb: Boolean = true // Nova variável para controlar thumbnails
) {
    val context = LocalContext.current
    val mediaItems = remember { mutableStateListOf<MediaItem>() }
    val lazyGridState = rememberLazyGridState()
    val imageLoader = OptimizedThumbnailManager.rememberVideoImageLoader()
    var isScrollingFast by remember { mutableStateOf(false) }

    // Detecta velocidade de rolagem para suspender carregamento
    LaunchedEffect(lazyGridState) {
        snapshotFlow { lazyGridState.isScrollInProgress }
            .collect { isScrolling ->
                isScrollingFast = isScrolling && lazyGridState.firstVisibleItemScrollOffset > 50
            }
    }

    // Detecta itens visíveis e gerencia carregamento/cancelamento
    LaunchedEffect(lazyGridState, mediaItems.size, showThumb) {
        if (mediaItems.isNotEmpty() && showThumb) {
            snapshotFlow { lazyGridState.layoutInfo.visibleItemsInfo }
                .map { visibleItems ->
                    val visibleIndices = visibleItems.map { it.index }.toSet()
                    val expandedRange = if (visibleIndices.isNotEmpty()) {
                        val min = visibleIndices.minOrNull()!! - 3
                        val max = visibleIndices.maxOrNull()!! + 3
                        (min.coerceAtLeast(0)..max.coerceAtMost(mediaItems.size - 1)).toSet()
                    } else emptySet()
                    expandedRange
                }
                .distinctUntilChanged()
                .collect { safeIndices ->
                    mediaItems.forEachIndexed { index, mediaItem ->
                        if (index !in safeIndices && !mediaItem.isFolder) {
                            // Cancela carregamento dos itens não visíveis
                            OptimizedThumbnailManager.cancelLoading(mediaItem.path)
                        }
                    }
                }
        }
    }

    // NOVO: Trigger inicial para carregar primeiros itens visíveis
    LaunchedEffect(mediaItems.size, showThumb) {
        if (mediaItems.isNotEmpty() && showThumb && !isScrollingFast) {
            // Aguarda um frame para o LazyGrid estabilizar
            kotlinx.coroutines.delay(100)

            // Força carregamento dos primeiros itens visíveis
            val visibleItems = lazyGridState.layoutInfo.visibleItemsInfo
            visibleItems.forEach { itemInfo ->
                val mediaItem = mediaItems.getOrNull(itemInfo.index)
                if (mediaItem != null && !mediaItem.isFolder && mediaItem.uri != null) {
                    // Verifica se já não tem thumbnail cached
                    val cachedThumbnail = OptimizedThumbnailManager.getCachedThumbnail(mediaItem.path)
                    if (cachedThumbnail == null) {
                        // Inicia carregamento se não tem cache
                        OptimizedThumbnailManager.loadVideoMetadataWithDelay(
                            context = context,
                            videoUri = mediaItem.uri,
                            videoPath = mediaItem.path,
                            imageLoader = null,
                            delayMs = 100L, // Delay mínimo para primeiros itens
                            onMetadataLoaded = { metadata ->
                                metadata.duration?.let { duration ->
                                    durationCache.put(mediaItem.path, duration)
                                }
                            },
                            onCancelled = { },
                            onStateChanged = { }
                        )
                    }
                }
            }
        }
    }

    LaunchedEffect(folderPath, renameTrigger) {
        val items = withContext(Dispatchers.IO) {
            getVideosAndSubfolders(context, folderPath)
        }
        mediaItems.clear()
        mediaItems.addAll(items)
    }

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

    // Limpa cache quando sai da tela - só se showThumb = true
    DisposableEffect(showThumb) {
        onDispose {
            if (showThumb) {
                OptimizedThumbnailManager.clearCache()
            }
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = lazyGridState,
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(
            items = mediaItems,
            key = { it.path }
        ) { mediaItem ->
            MediaItemCard(
                mediaItem = mediaItem,
                isSelected = mediaItem.path in selectedItems,
                imageLoader = null,
                isScrollingFast = isScrollingFast,
                showThumb = showThumb,
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
    imageLoader: ImageLoader?,
    isScrollingFast: Boolean,
    showThumb: Boolean, // Nova variável
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onTap: () -> Unit
) {
    val context = LocalContext.current

    // Estados para thumbnail e duração (apenas para vídeos)
    var thumbnailBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var videoDuration by remember { mutableStateOf<String?>(null) }
    var thumbnailState by remember { mutableStateOf(ThumbnailState.IDLE) }

    // Cor aleatória para quando showThumb = false
    val randomColor = remember(mediaItem.path) {
        if (!showThumb && !mediaItem.isFolder) {
            generateRandomColor(mediaItem.path)
        } else Color.Transparent
    }

    // Carrega metadata automaticamente quando item aparece - MELHORADO
    LaunchedEffect(mediaItem.path, isScrollingFast, showThumb, thumbnailState) {
        if (!mediaItem.isFolder && mediaItem.uri != null && showThumb && !isScrollingFast) {
            // Verifica cache em memória primeiro
            val cachedThumbnail = OptimizedThumbnailManager.getCachedThumbnail(mediaItem.path)
            if (cachedThumbnail != null) {
                thumbnailBitmap = cachedThumbnail
                thumbnailState = ThumbnailState.LOADED
                videoDuration = durationCache.get(mediaItem.path)
                return@LaunchedEffect
            }

            // Se ainda está IDLE e não tem thumbnail, inicia carregamento
            if (thumbnailState == ThumbnailState.IDLE && thumbnailBitmap == null) {
                thumbnailState = ThumbnailState.WAITING

                OptimizedThumbnailManager.loadVideoMetadataWithDelay(
                    context = context,
                    videoUri = mediaItem.uri,
                    videoPath = mediaItem.path,
                    imageLoader = imageLoader,
                    delayMs = 200L, // Delay menor para responsividade
                    onMetadataLoaded = { metadata ->
                        thumbnailBitmap = metadata.thumbnail
                        videoDuration = metadata.duration
                        thumbnailState = ThumbnailState.LOADED
                        metadata.duration?.let { duration ->
                            durationCache.put(mediaItem.path, duration)
                        }
                    },
                    onCancelled = {
                        if (OptimizedThumbnailManager.getCachedThumbnail(mediaItem.path) == null) {
                            thumbnailBitmap = null
                            videoDuration = null
                            thumbnailState = ThumbnailState.IDLE
                        }
                    },
                    onStateChanged = { newState ->
                        thumbnailState = newState
                    }
                )
            }
        } else if (!showThumb) {
            // Limpa thumbnail se showThumb = false
            thumbnailBitmap = null
            videoDuration = null
            thumbnailState = ThumbnailState.IDLE
        }
    }

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
            .clip(RoundedCornerShape(16.dp))
            .shadow(4.dp, RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (mediaItem.isFolder) {
                // PASTA - APENAS ÍCONE AZUL, SEM THUMBNAILS
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
                            .size(48.dp)
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
                // VÍDEO
                Box(modifier = Modifier.fillMaxSize()) {
                    when {
                        // Se showThumb = false, mostra cor aleatória
                        !showThumb -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(randomColor.copy(alpha = if (isSelected) 0.7f else 1.0f))
                                    .clip(RoundedCornerShape(16.dp))
                            )
                        }
                        // Se showThumb = true, comportamento normal
                        thumbnailBitmap != null -> {
                            Image(
                                bitmap = thumbnailBitmap!!.asImageBitmap(),
                                contentDescription = "Video Thumbnail",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(16.dp))
                                    .alpha(if (isSelected) 0.7f else 1.0f)
                            )
                        }
                        thumbnailState == ThumbnailState.LOADING && showThumb -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        else -> {
                            // Estado IDLE/WAITING - Mostra ícone de vídeo grande e visível
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                // Ícone de vídeo grande e visível no estado IDLE
                                Icon(
                                    imageVector = Icons.Default.VideoFile,
                                    contentDescription = "Vídeo aguardando carregamento",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    modifier = Modifier.size(56.dp)
                                )
                            }
                        }
                    }

                    // Overlay apenas quando não está selecionado
                    if (!isSelected) {
                        // Ícone play - apenas quando tem thumbnail ou está no modo cor
                        if (thumbnailBitmap != null || !showThumb) {
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
                        }

                        // Nome do arquivo - sempre visível
                        Text(
                            text = File(mediaItem.path).name,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Medium,
                                shadow = Shadow(
                                    color = Color.Black.copy(alpha = 0.5f),
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
                                .background(Color.Black.copy(alpha = 0.4f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )

                        // Duração do vídeo - só mostra se showThumb = true
                        if (showThumb) {
                            val duration = videoDuration ?: durationCache.get(mediaItem.path)
                            duration?.let {
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
                                            color = Color.Black.copy(alpha = 0.4f),
                                            shape = RoundedCornerShape(4.dp)
                                        )
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    // Ícone de seleção
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Selected",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(8.dp)
                                .size(20.dp)
                        )
                    }
                }
            }
        }
    }
}