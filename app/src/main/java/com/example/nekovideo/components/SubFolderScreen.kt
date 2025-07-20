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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import androidx.compose.ui.unit.sp
import com.example.nekovideo.components.settings.SettingsManager
import com.example.nekovideo.services.FolderVideoScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.flow.first
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

// SIMPLIFICADO - Enum de ordenação
enum class SortType { NAME_ASC, NAME_DESC, DATE_NEWEST, DATE_OLDEST, SIZE_LARGEST, SIZE_SMALLEST }

// SIMPLIFICADO - Data class do item
@Immutable
data class MediaItem(
    val path: String,
    val uri: Uri?,
    val isFolder: Boolean,
    val name: String = File(path).name,
    val displayName: String = if (File(path).name.startsWith(".")) File(path).name.drop(1) else File(path).name,
    val lastModified: Long = 0L,
    val sizeInBytes: Long = 0L
)

// SIMPLIFICADO - Cache e constantes
private val colorCache = LruCache<String, Color>(500)
val videoExtensions = setOf("mp4", "mkv", "webm", "avi", "mov", "wmv", "m4v", "3gp", "flv")

// SIMPLIFICADO - Gerador de cores
private fun getRandomColor(path: String): Color {
    return colorCache.get(path) ?: run {
        val colors = listOf(
            Color(0xFF6366F1), Color(0xFF8B5CF6), Color(0xFFEC4899),
            Color(0xFFEF4444), Color(0xFFF97316), Color(0xFF10B981)
        )
        val color = colors[Random(path.hashCode()).nextInt(colors.size)]
        colorCache.put(path, color)
        color
    }
}

// SIMPLIFICADO - Função principal de carregamento
fun loadFolderContent(
    context: Context,
    folderPath: String,
    sortType: SortType,
    isSecureMode: Boolean,
    isRootLevel: Boolean,
    showPrivateFolders: Boolean
): List<MediaItem> {
    return if (isSecureMode) {
        loadSecureContent(folderPath, sortType)
    } else {
        loadNormalContentFromCache(context, folderPath, sortType, isRootLevel, showPrivateFolders)
    }
}

// SIMPLIFICADO - Carregamento secure
private fun loadSecureContent(folderPath: String, sortType: SortType): List<MediaItem> {
    val folder = File(folderPath)
    if (!folder.exists() || !folder.isDirectory) return emptyList()

    val items = folder.listFiles()?.mapNotNull { file ->
        when {
            file.name in listOf(".nomedia", ".nekovideo") -> null
            file.isDirectory -> MediaItem(file.absolutePath, null, true, lastModified = file.lastModified(), sizeInBytes = getFolderSize(file))
            file.isFile && file.extension.lowercase() in videoExtensions -> MediaItem(file.absolutePath, null, false, lastModified = file.lastModified(), sizeInBytes = file.length())
            else -> null
        }
    } ?: emptyList()

    return applySorting(items, sortType)
}

private fun isSecureFolder(folderPath: String): Boolean {
    val folder = File(folderPath)
    return File(folder, ".nomedia").exists() || File(folder, ".nekovideo").exists()
}

// SIMPLIFICADO - Carregamento normal
private fun loadNormalContentFromCache(
    context: Context,
    folderPath: String,
    sortType: SortType,
    isRootLevel: Boolean,
    showPrivateFolders: Boolean
): List<MediaItem> {
    val items = mutableListOf<MediaItem>()
    val folder = File(folderPath)
    val folderCache = FolderVideoScanner.cache.value

    // Carregar pastas usando o cache
    folder.listFiles()?.filter { it.isDirectory }?.forEach { subfolder ->
        if (subfolder.name in listOf(".nomedia", ".nekovideo")) return@forEach

        val folderInfo = folderCache[subfolder.absolutePath]
        // 🔧 CORREÇÃO: Verifica diretamente se é pasta segura mesmo que não esteja no cache
        val isSecure = folderInfo?.isSecure ?: (File(subfolder, ".nomedia").exists() || File(subfolder, ".nekovideo").exists())
        val hasVideos = folderInfo?.hasVideos ?: false

        val shouldShow = when {
            subfolder.name.startsWith(".") -> {
                if (isRootLevel) {
                    // 🔧 Na raiz, respeita showPrivateFolders
                    showPrivateFolders && (isSecure || hasVideos)
                } else {
                    // 🔧 Em subpastas, sempre mostra se é segura ou tem vídeos
                    isSecure || hasVideos
                }
            }
            hasVideos -> true
            isSecure -> {
                if (isRootLevel) {
                    showPrivateFolders
                } else {
                    true // Em subpastas, sempre mostra pastas seguras
                }
            }
            else -> false
        }

        if (shouldShow) {
            items.add(MediaItem(
                subfolder.absolutePath,
                null,
                true,
                lastModified = folderInfo?.lastModified ?: subfolder.lastModified(),
                sizeInBytes = getFolderSize(subfolder)
            ))
        }
    }

    // Carregar vídeos (mantém a mesma lógica)
    val projection = arrayOf(
        MediaStore.Video.Media.DATA,
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DATE_MODIFIED,
        MediaStore.Video.Media.SIZE
    )
    val selection = "${MediaStore.Video.Media.DATA} LIKE ? AND ${MediaStore.Video.Media.DATA} NOT LIKE ?"
    val selectionArgs = arrayOf("$folderPath/%", "$folderPath%/%/%")

    context.contentResolver.query(
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        projection,
        selection,
        selectionArgs,
        null
    )?.use { cursor ->
        val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
        val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
        val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)

        while (cursor.moveToNext()) {
            val path = cursor.getString(dataColumn)
            if (path.startsWith(folderPath)) {
                val uri = ContentUris.withAppendedId(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    cursor.getLong(idColumn)
                )
                items.add(MediaItem(
                    path,
                    uri,
                    false,
                    lastModified = cursor.getLong(dateColumn) * 1000L,
                    sizeInBytes = cursor.getLong(sizeColumn)
                ))
            }
        }
    }

    return applySorting(items, sortType)
}

// SIMPLIFICADO - Helpers
private fun hasVideosInFolder(context: Context, folderPath: String): Boolean {
    return context.contentResolver.query(
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Video.Media.DATA),
        "${MediaStore.Video.Media.DATA} LIKE ?",
        arrayOf("$folderPath/%"),
        null
    )?.use { it.count > 0 } ?: false
}

private fun getFolderSize(folder: File): Long {
    return try {
        folder.listFiles()?.sumOf { if (it.isDirectory) getFolderSize(it) else it.length() } ?: 0L
    } catch (e: Exception) { 0L }
}

private fun applySorting(items: List<MediaItem>, sortType: SortType): List<MediaItem> {
    val folders = items.filter { it.isFolder }
    val videos = items.filter { !it.isFolder }

    val comparator: Comparator<MediaItem> = when (sortType) {
        SortType.NAME_ASC -> compareBy { it.displayName.lowercase() }
        SortType.NAME_DESC -> compareByDescending { it.displayName.lowercase() }
        SortType.DATE_NEWEST -> compareByDescending { it.lastModified }
        SortType.DATE_OLDEST -> compareBy { it.lastModified }
        SortType.SIZE_LARGEST -> compareByDescending { it.sizeInBytes }
        SortType.SIZE_SMALLEST -> compareBy { it.sizeInBytes }
    }

    return folders.sortedWith(comparator) + videos.sortedWith(comparator)
}

// SIMPLIFICADO - Componente de ordenação
@Composable
fun SortRow(currentSort: SortType, onSortChange: (SortType) -> Unit) {
    var showDropdown by remember { mutableStateOf(false) }

    Row(modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Sort, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.width(8.dp))

        Box {
            TextButton(onClick = { showDropdown = true }) {
                Text(when (currentSort) {
                    SortType.NAME_ASC -> "Nome A-Z"
                    SortType.NAME_DESC -> "Nome Z-A"
                    SortType.DATE_NEWEST -> "Mais Recente"
                    SortType.DATE_OLDEST -> "Mais Antigo"
                    SortType.SIZE_LARGEST -> "Maior"
                    SortType.SIZE_SMALLEST -> "Menor"
                })
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }

            DropdownMenu(expanded = showDropdown, onDismissRequest = { showDropdown = false }) {
                SortType.values().forEach { sort ->
                    DropdownMenuItem(
                        text = { Text(when (sort) {
                            SortType.NAME_ASC -> "Nome A-Z"
                            SortType.NAME_DESC -> "Nome Z-A"
                            SortType.DATE_NEWEST -> "Mais Recente"
                            SortType.DATE_OLDEST -> "Mais Antigo"
                            SortType.SIZE_LARGEST -> "Maior"
                            SortType.SIZE_SMALLEST -> "Menor"
                        })},
                        onClick = { onSortChange(sort); showDropdown = false },
                        leadingIcon = if (currentSort == sort) {{ Icon(Icons.Default.Check, contentDescription = null) }} else null
                    )
                }
            }
        }
    }
}

// COMPONENTE PRINCIPAL COM LOADING
@Composable
fun SubFolderScreen(
    folderPath: String,
    onFolderClick: (String) -> Unit,
    selectedItems: MutableList<String>,
    onSelectionChange: (List<String>) -> Unit,
    renameTrigger: Int,
    deletedVideoPath: String? = null,
    isSecureMode: Boolean = false,
    isRootLevel: Boolean = false,
    showPrivateFolders: Boolean = false
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current

    var items by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var sortType by remember { mutableStateOf(SortType.NAME_ASC) }
    val lazyListState = rememberLazyListState()

    // 🆕 Manual pull-to-refresh states
    var isRefreshing by remember { mutableStateOf(false) }
    var pullOffset by remember { mutableStateOf(0f) }
    val pullThreshold = with(density) { 80.dp.toPx() }

    val shouldRefresh by remember { derivedStateOf { pullOffset >= pullThreshold } }

    // Observa o estado do scanner
    val scannerCache by FolderVideoScanner.cache.collectAsState()
    val isScanning by FolderVideoScanner.isScanning.collectAsState()

    // Configurações
    val showThumbnails by remember { derivedStateOf { OptimizedThumbnailManager.isShowThumbnailsEnabled(context) }}
    val showDurations by remember { derivedStateOf { OptimizedThumbnailManager.isShowDurationsEnabled(context) }}
    val showFileSizes by remember { derivedStateOf { OptimizedThumbnailManager.isShowFileSizesEnabled(context) }}
    val gridColumns by remember { derivedStateOf { SettingsManager.getGridColumns(context) }}

    // Inicia o scanner se necessário
    LaunchedEffect(Unit) {
        if (scannerCache.isEmpty() && !isScanning) {
            FolderVideoScanner.startScan(context, coroutineScope)
        }
    }

    // 🆕 Função de refresh
    fun performRefresh() {
        if (!isRefreshing) {
            coroutineScope.launch {
                try {
                    isRefreshing = true
                    FolderVideoScanner.startScan(context, coroutineScope)

                    while (FolderVideoScanner.isScanning.value) {
                        delay(100)
                    }

                    delay(300)
                } catch (e: Exception) {
                    Log.e("SubFolderScreen", "Erro no refresh", e)
                } finally {
                    isRefreshing = false
                    pullOffset = 0f
                }
            }
        }
    }

    // Carregamento com loading
    LaunchedEffect(folderPath, sortType, renameTrigger, isSecureMode, showPrivateFolders, scannerCache) {
        isLoading = true
        items = withContext(Dispatchers.IO) {
            loadFolderContent(context, folderPath, sortType, isSecureMode, isRootLevel, showPrivateFolders)
        }
        isLoading = false
    }

    // Remoção de item deletado
    LaunchedEffect(deletedVideoPath) {
        deletedVideoPath?.let { path ->
            items = items.filterNot { it.path == path }
            selectedItems.remove(path)
            onSelectionChange(selectedItems.toList())
        }
    }

    // Tela de loading
    if (isLoading || (scannerCache.isEmpty() && isScanning)) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp),
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = when {
                        isScanning && isRefreshing -> "Atualizando índice..."
                        isScanning -> "Indexando vídeos..."
                        else -> "Carregando..."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    } else {
        // 🆕 Conteúdo com pull-to-refresh manual
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .offset { IntOffset(0, pullOffset.roundToInt()) }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragEnd = {
                                if (shouldRefresh && !isRefreshing) {
                                    performRefresh()
                                } else {
                                    pullOffset = 0f
                                }
                            }
                        ) { _, dragAmount ->
                            val newOffset = pullOffset + dragAmount.y
                            pullOffset = newOffset.coerceAtLeast(0f).coerceAtMost(pullThreshold * 1.5f)
                        }
                    }
            ) {
                SortRow(sortType) { sortType = it }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)

                LazyColumn(
                    state = lazyListState,
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(items.chunked(gridColumns), key = { chunk -> chunk.joinToString { it.path }}) { rowItems ->
                        MediaRow(
                            items = rowItems,
                            gridColumns = gridColumns,
                            selectedItems = selectedItems,
                            showThumbnails = showThumbnails,
                            showDurations = showDurations,
                            showFileSizes = showFileSizes,
                            isSecureMode = isSecureMode,
                            onFolderClick = onFolderClick,
                            onSelectionChange = onSelectionChange
                        )
                    }
                }
            }

            // 🆕 Indicador de refresh no topo
            if (pullOffset > 0 || isRefreshing) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp)
                        .offset { IntOffset(0, (pullOffset * 0.5f).roundToInt()) }
                ) {
                    if (isRefreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        val alpha = (pullOffset / pullThreshold).coerceAtMost(1f)
                        CircularProgressIndicator(
                            progress = alpha,
                            modifier = Modifier.size(32.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = alpha)
                        )
                    }
                }
            }

            // Indicador quando scan roda em background
            if (isScanning && !isRefreshing) {
                Card(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 60.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp, 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Atualizando índice...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }
    }
}

// SIMPLIFICADO - Row de items
@Composable
private fun MediaRow(
    items: List<MediaItem>,
    gridColumns: Int,
    selectedItems: MutableList<String>,
    showThumbnails: Boolean,
    showDurations: Boolean,
    showFileSizes: Boolean,
    isSecureMode: Boolean,
    onFolderClick: (String) -> Unit,
    onSelectionChange: (List<String>) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items.forEach { item ->
            MediaCard(
                item = item,
                isSelected = item.path in selectedItems,
                showThumbnails = showThumbnails,
                showDurations = showDurations,
                showFileSizes = showFileSizes,
                gridColumns = gridColumns,
                isSecureMode = isSecureMode,
                modifier = Modifier.weight(1f),
                onTap = {
                    if (selectedItems.isNotEmpty()) {
                        if (item.path in selectedItems) selectedItems.remove(item.path) else selectedItems.add(item.path)
                        onSelectionChange(selectedItems.toList())
                    } else {
                        onFolderClick(item.path)
                    }
                },
                onLongPress = {
                    if (item.path in selectedItems) selectedItems.remove(item.path) else selectedItems.add(item.path)
                    onSelectionChange(selectedItems.toList())
                }
            )
        }
        repeat(gridColumns - items.size) { Box(modifier = Modifier.weight(1f)) }
    }
}

// SIMPLIFICADO - Card do item
@Composable
private fun MediaCard(
    item: MediaItem,
    isSelected: Boolean,
    showThumbnails: Boolean,
    showDurations: Boolean,
    showFileSizes: Boolean,
    gridColumns: Int,
    isSecureMode: Boolean,
    modifier: Modifier = Modifier,
    onTap: () -> Unit,
    onLongPress: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var thumbnail by remember(item.path) { mutableStateOf<Bitmap?>(null) }
    var duration by remember(item.path) { mutableStateOf<String?>(null) }
    var fileSize by remember(item.path) { mutableStateOf<String?>(null) }
    var isLoading by remember(item.path) { mutableStateOf(false) }
    var job by remember(item.path) { mutableStateOf<Job?>(null) }

    val randomColor = remember(item.path) { if (!showThumbnails && !item.isFolder) getRandomColor(item.path) else Color.Transparent }

    // Carregamento de metadados
    LaunchedEffect(item.path, showThumbnails, showDurations, showFileSizes) {
        if (!item.isFolder && (showThumbnails || showDurations || showFileSizes)) {
            job?.cancel()
            isLoading = true

            job = coroutineScope.launch(Dispatchers.IO) {
                try {
                    delay(100)
                    val videoUri = if (isSecureMode) Uri.fromFile(File(item.path)) else item.uri

                    videoUri?.let { uri ->
                        OptimizedThumbnailManager.loadVideoMetadataWithDelay(
                            context = context,
                            videoUri = uri,
                            videoPath = item.path,
                            imageLoader = null,
                            delayMs = 0L,
                            onMetadataLoaded = { metadata ->
                                if (showThumbnails) thumbnail = metadata.thumbnail
                                if (showDurations) duration = metadata.duration
                                if (showFileSizes) fileSize = metadata.fileSize
                                isLoading = false
                            },
                            onCancelled = { isLoading = false },
                            onStateChanged = { }
                        )
                    }
                } catch (e: Exception) {
                    isLoading = false
                }
            }
        }
    }

    DisposableEffect(item.path) { onDispose { job?.cancel() } }

    Card(
        modifier = modifier
            .aspectRatio(1f)
            .combinedClickable(onClick = onTap, onLongClick = onLongPress)
            .border(
                width = if (isSelected) 2.dp else 0.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = RoundedCornerShape(12.dp)
            ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (item.isFolder) {
                FolderContent(item)
            } else {
                VideoContent(item, thumbnail, duration, fileSize, isLoading, isSelected, showThumbnails, randomColor, gridColumns)
            }
        }
    }
}

// SIMPLIFICADO - Conteúdo da pasta
@Composable
private fun FolderContent(item: MediaItem) {
    val isSecure = item.name.startsWith(".")

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f)),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = if (isSecure) Icons.Default.FolderSpecial else Icons.Default.Folder,
            contentDescription = null,
            tint = if (isSecure) Color(0xFFFF6B35) else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(40.dp).weight(1f)
        )

        Text(
            text = item.displayName,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(6.dp, 4.dp)
        )
    }
}

// SIMPLIFICADO - Conteúdo do vídeo
@Composable
private fun VideoContent(
    item: MediaItem,
    thumbnail: Bitmap?,
    duration: String?,
    fileSize: String?,
    isLoading: Boolean,
    isSelected: Boolean,
    showThumbnails: Boolean,
    randomColor: Color,
    gridColumns: Int
) {
    val textSize = when (gridColumns) {
        2 -> 12.sp
        3 -> 10.sp
        4 -> 8.sp
        else -> 10.sp
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            !showThumbnails -> Box(modifier = Modifier.fillMaxSize().background(randomColor).alpha(if (isSelected) 0.7f else 1f))
            thumbnail != null -> Image(
                bitmap = thumbnail.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().alpha(if (isSelected) 0.7f else 1f)
            )
            isLoading -> Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
            }
            else -> Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.VideoFile, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), modifier = Modifier.size(40.dp))
            }
        }

        if (!isSelected) {
            // Play button
            if (thumbnail != null && showThumbnails) {
                Box(modifier = Modifier.align(Alignment.Center).size(28.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f))) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp).align(Alignment.Center))
                }
            }

            // Título
            Text(
                text = item.name,
                fontSize = textSize,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                style = LocalTextStyle.current.copy(shadow = Shadow(Color.Black.copy(alpha = 0.5f), Offset(1f, 1f), 2f)),
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.4f)).padding(6.dp, 2.dp)
            )

            // Duração
            duration?.let {
                Text(
                    text = it,
                    fontSize = textSize,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(4.dp)).padding(4.dp, 2.dp)
                )
            }

            // Tamanho
            fileSize?.let {
                Text(
                    text = it,
                    fontSize = textSize,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp).background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(4.dp)).padding(4.dp, 2.dp)
                )
            }
        }

        // Check de seleção
        if (isSelected) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).size(18.dp))
        }
    }
}

// Função auxiliar para busca recursiva (para shuffle play)
fun loadFolderContentRecursive(
    context: Context,
    folderPath: String,
    isSecureMode: Boolean,
    showPrivateFolders: Boolean = false
): List<MediaItem> {
    if (isSecureMode) {
        val allItems = mutableListOf<MediaItem>()
        loadSecureContentRecursive(folderPath, allItems)
        return allItems
    }

    // Usa o cache para busca recursiva mais eficiente
    val folderCache = FolderVideoScanner.cache.value
    val allItems = mutableListOf<MediaItem>()

    // Busca todas as pastas filhas que têm vídeos
    folderCache.values
        .filter { it.path.startsWith(folderPath) && it.hasVideos }
        .forEach { folderInfo ->
            if (folderInfo.isSecure && !showPrivateFolders) return@forEach

            // Carrega vídeos desta pasta
            val projection = arrayOf(MediaStore.Video.Media.DATA, MediaStore.Video.Media._ID)
            val selection = "${MediaStore.Video.Media.DATA} LIKE ? AND ${MediaStore.Video.Media.DATA} NOT LIKE ?"
            val selectionArgs = arrayOf("${folderInfo.path}/%", "${folderInfo.path}%/%/%")

            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)

                while (cursor.moveToNext()) {
                    val path = cursor.getString(dataColumn)
                    if (path.startsWith(folderInfo.path)) {
                        val uri = ContentUris.withAppendedId(
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                            cursor.getLong(idColumn)
                        )
                        allItems.add(MediaItem(path, uri, false))
                    }
                }
            }
        }

    return allItems
}

private fun loadSecureContentRecursive(folderPath: String, allItems: MutableList<MediaItem>) {
    val folder = File(folderPath)
    if (!folder.exists() || !folder.isDirectory) return

    folder.listFiles()?.forEach { file ->
        when {
            file.name in listOf(".nomedia", ".nekovideo") -> return@forEach
            file.isDirectory -> {
                loadSecureContentRecursive(file.absolutePath, allItems)
            }
            file.isFile && file.extension.lowercase() in videoExtensions -> {
                allItems.add(MediaItem(file.absolutePath, null, false))
            }
        }
    }
}

private fun loadNormalContentRecursive(
    context: Context,
    folderPath: String,
    allItems: MutableList<MediaItem>,
    showPrivateFolders: Boolean
) {
    val folder = File(folderPath)
    if (!folder.exists() || !folder.isDirectory) return

    // Adicionar vídeos desta pasta
    val projection = arrayOf(MediaStore.Video.Media.DATA, MediaStore.Video.Media._ID)
    val selection = "${MediaStore.Video.Media.DATA} LIKE ? AND ${MediaStore.Video.Media.DATA} NOT LIKE ?"
    val selectionArgs = arrayOf("$folderPath/%", "$folderPath%/%/%")

    context.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection, selection, selectionArgs, null)?.use { cursor ->
        val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)

        while (cursor.moveToNext()) {
            val path = cursor.getString(dataColumn)
            if (path.startsWith(folderPath)) {
                val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cursor.getLong(idColumn))
                allItems.add(MediaItem(path, uri, false))
            }
        }
    }

    // Recursão nas subpastas
    folder.listFiles()?.filter { it.isDirectory }?.forEach { subfolder ->
        if (subfolder.name in listOf(".nomedia", ".nekovideo")) return@forEach

        // Incluir pastas privadas se necessário
        if (subfolder.name.startsWith(".") && !showPrivateFolders) return@forEach

        loadNormalContentRecursive(context, subfolder.absolutePath, allItems, showPrivateFolders)
    }
}