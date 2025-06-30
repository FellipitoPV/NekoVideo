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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sort
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

// Enum para tipos de ordenação
enum class SortType {
    NAME_ASC,
    NAME_DESC,
    DATE_NEWEST,
    DATE_OLDEST,
    SIZE_LARGEST,
    SIZE_SMALLEST
}

@Immutable
data class MediaItem(
    val path: String,
    val uri: Uri?, // null para secure mode
    val isFolder: Boolean,
    val duration: String? = null,
    val name: String = File(path).name,
    val id: String = path.hashCode().toString(),
    val lastModified: Long = 0L,
    val sizeInBytes: Long = 0L
)

// Cache otimizado
private val colorCache = LruCache<String, Color>(1000)

// Extensões de vídeo para secure mode
val videoExtensions = listOf(
    "mp4", "mkv", "webm", "avi", "mov", "wmv",
    "m4v", "mpg", "mpeg", "mp2", "mpe", "mpv",
    "3gp", "3g2", "flv", "f4v",
    "asf", "rm", "rmvb", "vob", "ogv", "drc", "mxf"
)

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

// Função para calcular tamanho da pasta
private fun getFolderSize(folder: File): Long {
    var size = 0L
    try {
        folder.listFiles()?.forEach { file ->
            size += if (file.isDirectory) {
                getFolderSize(file)
            } else {
                file.length()
            }
        }
    } catch (e: Exception) {
        // Em caso de erro de acesso, retorna 0
    }
    return size
}

// Função de ordenação
private fun applySorting(items: List<MediaItem>, sortType: SortType): List<MediaItem> {
    val folders = items.filter { it.isFolder }
    val videos = items.filter { !it.isFolder }

    val sortedFolders = when (sortType) {
        SortType.NAME_ASC -> folders.sortedWith { a, b -> compareNatural(a.name, b.name) }
        SortType.NAME_DESC -> folders.sortedWith { a, b -> compareNatural(b.name, a.name) }
        SortType.DATE_NEWEST -> folders.sortedByDescending { it.lastModified }
        SortType.DATE_OLDEST -> folders.sortedBy { it.lastModified }
        SortType.SIZE_LARGEST -> folders.sortedByDescending { it.sizeInBytes }
        SortType.SIZE_SMALLEST -> folders.sortedBy { it.sizeInBytes }
    }

    val sortedVideos = when (sortType) {
        SortType.NAME_ASC -> videos.sortedWith { a, b -> compareNatural(a.name, b.name) }
        SortType.NAME_DESC -> videos.sortedWith { a, b -> compareNatural(b.name, a.name) }
        SortType.DATE_NEWEST -> videos.sortedByDescending { it.lastModified }
        SortType.DATE_OLDEST -> videos.sortedBy { it.lastModified }
        SortType.SIZE_LARGEST -> videos.sortedByDescending { it.sizeInBytes }
        SortType.SIZE_SMALLEST -> videos.sortedBy { it.sizeInBytes }
    }

    return sortedFolders + sortedVideos
}

// FUNÇÃO UNIFICADA - funciona para ambos os modos
fun getVideosAndSubfolders(
    context: Context,
    folderPath: String,
    recursive: Boolean = false,
    sortType: SortType = SortType.NAME_ASC,
    isSecureMode: Boolean = false
): List<MediaItem> {
    return if (isSecureMode) {
        getSecureFolderContents(context, folderPath, sortType)
    } else {
        getMediaStoreFolderContents(context, folderPath, recursive, sortType)
    }
}

// Secure mode - acesso direto ao filesystem
private fun getSecureFolderContents(
    context: Context,
    folderPath: String,
    sortType: SortType
): List<MediaItem> {
    val mediaItems = mutableListOf<MediaItem>()
    val folder = File(folderPath)

    if (!folder.exists() || !folder.isDirectory) {
        return mediaItems
    }

    folder.listFiles()?.forEach { file ->
        if (file.name in listOf(".nomedia", ".nekovideo")) return@forEach

        if (file.isDirectory) {
            mediaItems.add(
                MediaItem(
                    path = file.absolutePath,
                    uri = null, // Secure mode não usa Uri
                    isFolder = true,
                    lastModified = file.lastModified(),
                    sizeInBytes = getFolderSize(file)
                )
            )
        } else if (file.isFile && file.extension.lowercase() in videoExtensions) {
            mediaItems.add(
                MediaItem(
                    path = file.absolutePath,
                    uri = null, // Secure mode não usa Uri
                    isFolder = false,
                    lastModified = file.lastModified(),
                    sizeInBytes = file.length()
                )
            )
        }
    }

    return applySorting(mediaItems, sortType)
}

// Normal mode - usando MediaStore
private fun getMediaStoreFolderContents(
    context: Context,
    folderPath: String,
    recursive: Boolean,
    sortType: SortType
): List<MediaItem> {
    val mediaItems = mutableListOf<MediaItem>()
    val folder = File(folderPath)

    // Busca pastas
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
            mediaItems.add(
                MediaItem(
                    path = subfolder.absolutePath,
                    uri = null,
                    isFolder = true,
                    lastModified = subfolder.lastModified(),
                    sizeInBytes = getFolderSize(subfolder)
                )
            )
        }
    }

    // Busca vídeos com MediaStore
    val projection = arrayOf(
        MediaStore.Video.Media.DATA,
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DATE_MODIFIED,
        MediaStore.Video.Media.SIZE
    )
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
        val dateColumn = it.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
        val sizeColumn = it.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)

        while (it.moveToNext()) {
            val path = it.getString(dataColumn)
            val id = it.getLong(idColumn)
            val dateModified = it.getLong(dateColumn) * 1000L
            val size = it.getLong(sizeColumn)

            if (path.startsWith(folderPath)) {
                val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                mediaItems.add(
                    MediaItem(
                        path = path,
                        uri = uri,
                        isFolder = false,
                        lastModified = dateModified,
                        sizeInBytes = size
                    )
                )
            }
        }
    }

    if (recursive) {
        folder.listFiles { file -> file.isDirectory }?.forEach { subfolder ->
            mediaItems.addAll(getMediaStoreFolderContents(context, subfolder.absolutePath, recursive, sortType))
        }
    }

    return applySorting(mediaItems, sortType)
}

// Função para dividir lista em chunks de 3 itens
private fun <T> List<T>.chunked3(): List<List<T>> {
    return this.chunked(3)
}

// Composable de filtro
@Composable
fun SortFilterRow(
    currentSort: SortType,
    onSortChange: (SortType) -> Unit,
    modifier: Modifier = Modifier
) {
    var showDropdown by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Sort,
            contentDescription = "Ordenar",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Box {
            TextButton(
                onClick = { showDropdown = true },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text(
                    text = when (currentSort) {
                        SortType.NAME_ASC -> "Nome A-Z"
                        SortType.NAME_DESC -> "Nome Z-A"
                        SortType.DATE_NEWEST -> "Mais Recente"
                        SortType.DATE_OLDEST -> "Mais Antigo"
                        SortType.SIZE_LARGEST -> "Maior Tamanho"
                        SortType.SIZE_SMALLEST -> "Menor Tamanho"
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            }

            DropdownMenu(
                expanded = showDropdown,
                onDismissRequest = { showDropdown = false }
            ) {
                SortType.values().forEach { sortOption ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                when (sortOption) {
                                    SortType.NAME_ASC -> "Nome A-Z"
                                    SortType.NAME_DESC -> "Nome Z-A"
                                    SortType.DATE_NEWEST -> "Mais Recente"
                                    SortType.DATE_OLDEST -> "Mais Antigo"
                                    SortType.SIZE_LARGEST -> "Maior Tamanho"
                                    SortType.SIZE_SMALLEST -> "Menor Tamanho"
                                }
                            )
                        },
                        onClick = {
                            onSortChange(sortOption)
                            showDropdown = false
                        },
                        leadingIcon = if (currentSort == sortOption) {
                            { Icon(Icons.Default.Check, contentDescription = null) }
                        } else null
                    )
                }
            }
        }
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
    isSecureMode: Boolean = false // NOVO PARÂMETRO
) {
    val context = LocalContext.current
    val mediaItems = remember { mutableStateListOf<MediaItem>() }
    val lazyListState = rememberLazyListState()
    var isScrollingFast by remember { mutableStateOf(false) }
    var currentSortType by remember { mutableStateOf(SortType.NAME_ASC) }

    // Configurações
    val showThumbnails by remember {
        derivedStateOf { OptimizedThumbnailManager.isShowThumbnailsEnabled(context) }
    }
    val showDurations by remember {
        derivedStateOf { OptimizedThumbnailManager.isShowDurationsEnabled(context) }
    }
    val showFileSizes by remember {
        derivedStateOf { OptimizedThumbnailManager.isShowFileSizesEnabled(context) }
    }

    var settingsVersion by remember { mutableStateOf(0) }

    LaunchedEffect(showThumbnails, showDurations, showFileSizes) {
        settingsVersion++
    }

    val selectionType = remember(selectedItems.size, mediaItems.size) {
        if (selectedItems.isEmpty()) {
            null
        } else {
            mediaItems.find { it.path in selectedItems }?.isFolder
        }
    }

    // Detecta velocidade de rolagem
    LaunchedEffect(lazyListState) {
        snapshotFlow {
            lazyListState.isScrollInProgress to lazyListState.firstVisibleItemScrollOffset
        }
            .distinctUntilChanged()
            .collect { (isScrolling, offset) ->
                isScrollingFast = isScrolling && offset > 30
            }
    }

    // Carregamento unificado
    LaunchedEffect(folderPath, renameTrigger, currentSortType, isSecureMode) {
        Log.d("NekoVideo", "Carregando items para $folderPath (secure: $isSecureMode)")
        val items = withContext(Dispatchers.IO) {
            getVideosAndSubfolders(
                context = context,
                folderPath = folderPath,
                sortType = currentSortType,
                isSecureMode = isSecureMode
            )
        }
        mediaItems.clear()
        mediaItems.addAll(items)
        Log.d("NekoVideo", "Carregados ${items.size} items")
    }

    // Remoção de item
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

    Column(modifier = Modifier.fillMaxSize()) {
        // Barra de filtros
        SortFilterRow(
            currentSort = currentSortType,
            onSortChange = { newSort ->
                currentSortType = newSort
            }
        )

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            thickness = 0.5.dp
        )

        // Lista
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
                    selectionType = selectionType,
                    isScrollingFast = isScrollingFast,
                    showThumbnails = showThumbnails,
                    showDurations = showDurations,
                    showFileSizes = showFileSizes,
                    settingsVersion = settingsVersion,
                    isSecureMode = isSecureMode,
                    onFolderClick = onFolderClick,
                    onSelectionChange = onSelectionChange
                )
            }
        }
    }
}

@Composable
private fun MediaItemRow(
    items: List<MediaItem>,
    selectedItems: MutableList<String>,
    selectionType: Boolean?,
    isScrollingFast: Boolean,
    showThumbnails: Boolean,
    showDurations: Boolean,
    showFileSizes: Boolean,
    settingsVersion: Int,
    isSecureMode: Boolean,
    onFolderClick: (String) -> Unit,
    onSelectionChange: (List<String>) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items.forEach { mediaItem ->
            OptimizedMediaItemCard(
                mediaItem = mediaItem,
                isSelected = mediaItem.path in selectedItems,
                isScrollingFast = isScrollingFast,
                showThumbnails = showThumbnails,
                showDurations = showDurations,
                showFileSizes = showFileSizes,
                settingsVersion = settingsVersion,
                isSecureMode = isSecureMode,
                modifier = Modifier.weight(1f),
                onLongPress = {
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
                        if (selectionType == mediaItem.isFolder) {
                            if (mediaItem.path in selectedItems) {
                                selectedItems.remove(mediaItem.path)
                            } else {
                                selectedItems.add(mediaItem.path)
                            }
                            onSelectionChange(selectedItems.toList())
                        }
                    } else {
                        onFolderClick(mediaItem.path)
                    }
                }
            )
        }

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
    showThumbnails: Boolean,
    showDurations: Boolean,
    showFileSizes: Boolean,
    settingsVersion: Int,
    isSecureMode: Boolean,
    modifier: Modifier = Modifier,
    onLongPress: () -> Unit,
    onTap: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var thumbnailBitmap by remember(mediaItem.id, settingsVersion) { mutableStateOf<Bitmap?>(null) }
    var videoDuration by remember(mediaItem.id, settingsVersion) { mutableStateOf<String?>(null) }
    var fileSize by remember(mediaItem.id, settingsVersion) { mutableStateOf<String?>(null) }
    var thumbnailState by remember(mediaItem.id, settingsVersion) { mutableStateOf(ThumbnailState.IDLE) }
    var thumbnailJob by remember(mediaItem.id, settingsVersion) { mutableStateOf<Job?>(null) }

    val randomColor = remember(mediaItem.path, showThumbnails, settingsVersion) {
        if (!showThumbnails && !mediaItem.isFolder) {
            generateRandomColor(mediaItem.path)
        } else Color.Transparent
    }

    // Carregamento adaptado para ambos os modos
    LaunchedEffect(mediaItem.id, isScrollingFast, thumbnailState, showThumbnails, showDurations, showFileSizes, settingsVersion) {
        if (!mediaItem.isFolder && !isScrollingFast) {
            thumbnailJob?.cancel()

            val cachedThumbnail = if (showThumbnails) OptimizedThumbnailManager.thumbnailCache.get(mediaItem.path) else null
            val cachedDuration = if (showDurations) OptimizedThumbnailManager.durationCache.get(mediaItem.path) else null
            val cachedFileSize = if (showFileSizes) OptimizedThumbnailManager.fileSizeCache.get(mediaItem.path) else null

            if (cachedThumbnail != null || cachedDuration != null || cachedFileSize != null) {
                thumbnailBitmap = cachedThumbnail
                videoDuration = cachedDuration
                fileSize = cachedFileSize
                thumbnailState = ThumbnailState.LOADED
                return@LaunchedEffect
            }

            if (thumbnailState == ThumbnailState.IDLE &&
                (showThumbnails || showDurations || showFileSizes)) {
                thumbnailState = ThumbnailState.WAITING

                thumbnailJob = coroutineScope.launch(Dispatchers.IO) {
                    try {
                        delay(200L)

                        // URI adaptado para o modo
                        val videoUri = if (isSecureMode) {
                            Uri.fromFile(File(mediaItem.path))
                        } else {
                            mediaItem.uri
                        }

                        videoUri?.let { uri ->
                            OptimizedThumbnailManager.loadVideoMetadataWithDelay(
                                context = context,
                                videoUri = uri,
                                videoPath = mediaItem.path,
                                imageLoader = null,
                                delayMs = 0L,
                                onMetadataLoaded = { metadata ->
                                    if (showThumbnails) {
                                        thumbnailBitmap = metadata.thumbnail
                                        metadata.thumbnail?.let { OptimizedThumbnailManager.thumbnailCache.put(mediaItem.path, it) }
                                    }
                                    if (showDurations) {
                                        videoDuration = metadata.duration
                                        metadata.duration?.let { OptimizedThumbnailManager.durationCache.put(mediaItem.path, it) }
                                    }
                                    if (showFileSizes) {
                                        fileSize = metadata.fileSize
                                        metadata.fileSize?.let { OptimizedThumbnailManager.fileSizeCache.put(mediaItem.path, it) }
                                    }
                                    thumbnailState = ThumbnailState.LOADED
                                },
                                onCancelled = {
                                    if (OptimizedThumbnailManager.thumbnailCache.get(mediaItem.path) == null) {
                                        thumbnailBitmap = null
                                        videoDuration = null
                                        fileSize = null
                                        thumbnailState = ThumbnailState.IDLE
                                    }
                                },
                                onStateChanged = { newState ->
                                    thumbnailState = newState
                                }
                            )
                        }
                    } catch (e: Exception) {
                        thumbnailState = ThumbnailState.IDLE
                    }
                }
            }
        }
    }

    DisposableEffect(mediaItem.id) {
        onDispose { thumbnailJob?.cancel() }
    }

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
                    fileSize = fileSize,
                    thumbnailState = thumbnailState,
                    isSelected = isSelected,
                    showThumbnails = showThumbnails,
                    showDurations = showDurations,
                    showFileSizes = showFileSizes,
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
    fileSize: String?,
    thumbnailState: ThumbnailState,
    isSelected: Boolean,
    showThumbnails: Boolean,
    showDurations: Boolean,
    showFileSizes: Boolean,
    randomColor: Color
) {
    Box(modifier = Modifier.fillMaxSize()) {
        when {
            !showThumbnails -> {
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

        if (!isSelected) {
            if (thumbnailBitmap != null && showThumbnails) {
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

            if (showDurations && videoDuration != null) {
                Text(
                    text = videoDuration,
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

            if (showFileSizes && fileSize != null) {
                Text(
                    text = fileSize,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .background(
                            color = Color.Black.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }
        }

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

// Funções de conveniência para compatibilidade
@Composable
fun SecureFolderScreen(
    folderPath: String,
    onFolderClick: (String) -> Unit,
    selectedItems: MutableList<String>,
    onSelectionChange: (List<String>) -> Unit,
    renameTrigger: Int,
    deletedVideoPath: String? = null
) {
    SubFolderScreen(
        folderPath = folderPath,
        onFolderClick = onFolderClick,
        selectedItems = selectedItems,
        onSelectionChange = onSelectionChange,
        renameTrigger = renameTrigger,
        deletedVideoPath = deletedVideoPath,
        isSecureMode = true
    )
}