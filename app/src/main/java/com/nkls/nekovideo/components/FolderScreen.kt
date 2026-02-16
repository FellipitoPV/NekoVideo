@file:OptIn(ExperimentalFoundationApi::class, ExperimentalAnimationApi::class)

package com.nkls.nekovideo.components

import androidx.compose.animation.ExperimentalAnimationApi

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.util.LruCache
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import com.nkls.nekovideo.components.helpers.FolderLockManager
import com.nkls.nekovideo.components.helpers.LockedPlaybackSession
import com.nkls.nekovideo.services.FolderVideoScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import kotlin.random.Random
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import com.nkls.nekovideo.R
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.nkls.nekovideo.services.FolderInfo

enum class SortType { NAME_ASC, NAME_DESC, DATE_NEWEST, DATE_OLDEST, SIZE_LARGEST, SIZE_SMALLEST }

@Composable
fun PermissionRequestScreen() {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.FolderOpen,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.permission_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.permission_description),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = MaterialTheme.typography.bodyMedium.lineHeight
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                    context.startActivity(intent)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text(
                text = stringResource(R.string.permission_button),
                style = MaterialTheme.typography.labelLarge
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.permission_instructions),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

fun hasManageExternalStoragePermission(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        // Para versões anteriores ao Android 11, não precisa dessa permissão específica
        true
    }
}

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

private val defaultAndroidFolders = setOf("DCIM", "Download", "Downloads", "Movies")

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

private fun naturalComparator(s1: String, s2: String): Int {
    val pattern = "\\d+|\\D+".toRegex()
    val tokens1 = pattern.findAll(s1.lowercase()).map { it.value }.toList()
    val tokens2 = pattern.findAll(s2.lowercase()).map { it.value }.toList()

    for (i in 0 until minOf(tokens1.size, tokens2.size)) {
        val token1 = tokens1[i]
        val token2 = tokens2[i]

        val num1 = token1.toIntOrNull()
        val num2 = token2.toIntOrNull()

        val comparison = if (num1 != null && num2 != null) {
            num1.compareTo(num2)
        } else {
            token1.compareTo(token2)
        }

        if (comparison != 0) return comparison
    }

    return tokens1.size.compareTo(tokens2.size)
}

// SIMPLIFICADO - Carregamento secure
private fun loadSecureContent(folderPath: String, sortType: SortType): List<MediaItem> {
    val folder = File(folderPath)
    if (!folder.exists() || !folder.isDirectory) return emptyList()

    // Check if this is a locked folder with an active session
    if (FolderLockManager.isLocked(folderPath) && LockedPlaybackSession.isActive &&
        LockedPlaybackSession.hasSessionForFolder(folderPath)) {
        val manifest = LockedPlaybackSession.getManifestForFolder(folderPath) ?: return emptyList()
        val items = mutableListOf<MediaItem>()

        // Add videos from manifest
        items.addAll(manifest.files.map { entry ->
            val obfuscatedFile = File(folder, entry.obfuscatedName)
            MediaItem(
                path = obfuscatedFile.absolutePath,
                uri = null,
                isFolder = false,
                name = entry.originalName,
                displayName = entry.originalName,
                lastModified = obfuscatedFile.lastModified(),
                sizeInBytes = entry.originalSize
            )
        })

        // Add subdirectories (locked subfolders show as folders)
        folder.listFiles()?.forEach { file ->
            if (file.isDirectory && file.name !in listOf(".neko_thumbs")) {
                items.add(MediaItem(
                    path = file.absolutePath,
                    uri = null,
                    isFolder = true,
                    lastModified = file.lastModified(),
                    sizeInBytes = getFolderSize(file)
                ))
            }
        }

        return applySorting(items, sortType)
    }

    val items = folder.listFiles()?.mapNotNull { file ->
        when {
            file.name in listOf(".nekovideo", ".neko_locked", ".neko_manifest.enc",
                ".neko_lock_in_progress", ".nomedia", "recovery_hint.txt", ".neko_thumbs") -> null
            file.isDirectory -> MediaItem(file.absolutePath, null, true, lastModified = file.lastModified(), sizeInBytes = getFolderSize(file))
            file.isFile && file.extension.lowercase() in videoExtensions -> MediaItem(file.absolutePath, null, false, lastModified = file.lastModified(), sizeInBytes = file.length())
            else -> null
        }
    } ?: emptyList()

    return applySorting(items, sortType)
}

private fun hasSecureSubfolderInCache(
    folderPath: String,
    folderCache: Map<String, FolderInfo>  // ✅ Remove "FolderVideoScanner." aqui
): Boolean {
    return folderCache.values.any { folderInfo ->
        folderInfo.path.startsWith("$folderPath/") &&
                folderInfo.isSecure &&
                folderInfo.hasVideos
    }
}

// ATUALIZAR loadNormalContentFromCache
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
        if (subfolder.name in listOf(".nekovideo")) return@forEach

        val folderInfo = folderCache[subfolder.absolutePath]
        val isSecure = folderInfo?.isSecure ?: (File(subfolder, ".nomedia").exists())
        val isNekoFolder = File(subfolder, ".nekovideo").exists() // Pasta criada pelo app
        val isFolderLocked = folderInfo?.isLocked ?: FolderLockManager.isLocked(subfolder.absolutePath)
        val hasVideos = folderInfo?.hasVideos ?: false
        val hasSecureSubfolders = hasSecureSubfolderInCache(subfolder.absolutePath, folderCache)

        // ✅ NOVO: Verifica se é pasta padrão do Android
        val isDefaultFolder = isRootLevel && subfolder.name in defaultAndroidFolders

        val shouldShow = when {
            // ✅ NOVO: Sempre mostra pastas padrão no root
            isDefaultFolder -> true

            // Locked folders: show only when private folders are visible
            isFolderLocked -> showPrivateFolders

            // ✅ Pastas criadas pelo app (com .nekovideo): respeita showPrivateFolders se for pasta privada
            isNekoFolder -> {
                if (subfolder.name.startsWith(".")) {
                    showPrivateFolders
                } else {
                    true
                }
            }

            subfolder.name.startsWith(".") -> {
                if (isRootLevel) {
                    showPrivateFolders && (isSecure || hasVideos || hasSecureSubfolders)
                } else {
                    isSecure || hasVideos || hasSecureSubfolders
                }
            }
            hasVideos -> true
            isSecure -> {
                if (isRootLevel) {
                    showPrivateFolders
                } else {
                    true
                }
            }
            hasSecureSubfolders -> {
                if (isRootLevel) {
                    showPrivateFolders
                } else {
                    true
                }
            }
            else -> false
        }

        if (shouldShow) {
            items.add(
                MediaItem(
                    subfolder.absolutePath,
                    null,
                    true,
                    lastModified = folderInfo?.lastModified ?: subfolder.lastModified(),
                    sizeInBytes = 0L
                )
            )
        }
    }

    // Carregar vídeos (mantém igual)
    val folderInfo = folderCache[folderPath]
    folderInfo?.videos?.forEach { videoInfo ->
        items.add(
            MediaItem(
                path = videoInfo.path,
                uri = videoInfo.uri,
                isFolder = false,
                lastModified = videoInfo.lastModified,
                sizeInBytes = videoInfo.sizeInBytes
            )
        )
    }

    return applySorting(items, sortType)
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
        SortType.NAME_ASC -> Comparator { a, b -> naturalComparator(a.displayName, b.displayName) }
        SortType.NAME_DESC -> Comparator { a, b -> naturalComparator(b.displayName, a.displayName) }
        SortType.DATE_NEWEST -> compareByDescending { it.lastModified }
        SortType.DATE_OLDEST -> compareBy { it.lastModified }
        SortType.SIZE_LARGEST -> compareByDescending { it.sizeInBytes }
        SortType.SIZE_SMALLEST -> compareBy { it.sizeInBytes }
    }

    return folders.sortedWith(comparator) + videos.sortedWith(comparator)
}

@Composable
fun SortRow(
    currentSort: SortType,
    onSortChange: (SortType) -> Unit,
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    searchQuery: String = "",
    isSearchExpanded: Boolean = false,
    onSearchQueryChange: (String) -> Unit = {},
    onSearchExpandChange: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("nekovideo_settings", Context.MODE_PRIVATE) }

    var showTutorial by remember { mutableStateOf(!prefs.getBoolean("pull_to_refresh_tutorial_shown", false)) }
    var tutorialAlpha by remember { mutableStateOf(0f) }
    var showDropdown by remember { mutableStateOf(false) }
    var pullOffset by remember { mutableStateOf(0f) }
    var isReadyToRefresh by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val refreshThreshold = with(density) { 80.dp.toPx() }

    LaunchedEffect(showTutorial) {
        if (showTutorial && tutorialAlpha == 0f) {
            for (i in 0..10) {
                tutorialAlpha = i / 10f
                delay(30)
            }
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(isRefreshing) {
                if (!isRefreshing) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            if (isReadyToRefresh) {
                                onRefresh()
                                if (showTutorial) {
                                    kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                                        for (i in 10 downTo 0) {
                                            tutorialAlpha = i / 10f
                                            delay(20)
                                        }
                                        showTutorial = false
                                        prefs
                                            .edit()
                                            .putBoolean("pull_to_refresh_tutorial_shown", true)
                                            .apply()
                                    }
                                }
                            }
                            pullOffset = 0f
                            isReadyToRefresh = false
                        },
                        onDragCancel = {
                            pullOffset = 0f
                            isReadyToRefresh = false
                        },
                        onVerticalDrag = { _, dragAmount ->
                            if (dragAmount > 0) {
                                pullOffset = (pullOffset + dragAmount * 0.5f).coerceIn(0f, refreshThreshold * 1.5f)
                                isReadyToRefresh = pullOffset >= refreshThreshold
                            } else if (dragAmount < 0 && pullOffset > 0) {
                                pullOffset = (pullOffset + dragAmount * 0.5f).coerceAtLeast(0f)
                                isReadyToRefresh = pullOffset >= refreshThreshold
                            }
                        }
                    )
                }
            }
    ) {
        @Suppress("UnusedBoxWithConstraintsScope")
        val isCompact = this.maxWidth > 600.dp

        Column {
            if (!isCompact && !isRefreshing && pullOffset == 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Box(
                        modifier = Modifier
                            .width(40.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))
                    )
                }
            }

            if (pullOffset > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(with(density) { pullOffset.toDp() })
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (isRefreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Icon(
                            imageVector = if (isReadyToRefresh) Icons.Default.Refresh else Icons.Default.ArrowDownward,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .size(24.dp)
                                .alpha((pullOffset / refreshThreshold).coerceIn(0f, 1f))
                        )
                    }
                }
            }

            // Linha única com sort e busca
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = if (isCompact) 2.dp else 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Ícone de sort sempre visível
                Icon(
                    Icons.Default.Sort,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Dropdown de ordenação
                Box(modifier = Modifier.weight(1f, fill = !isSearchExpanded)) {
                    TextButton(onClick = { showDropdown = true }) {
                        Text(
                            when (currentSort) {
                                SortType.NAME_ASC -> stringResource(R.string.sort_name_asc)
                                SortType.NAME_DESC -> stringResource(R.string.sort_name_desc)
                                SortType.DATE_NEWEST -> stringResource(R.string.sort_date_newest)
                                SortType.DATE_OLDEST -> stringResource(R.string.sort_date_oldest)
                                SortType.SIZE_LARGEST -> stringResource(R.string.sort_size_largest)
                                SortType.SIZE_SMALLEST -> stringResource(R.string.sort_size_smallest)
                            }
                        )
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }

                    DropdownMenu(
                        expanded = showDropdown,
                        onDismissRequest = { showDropdown = false }
                    ) {
                        SortType.values().forEach { sort ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        when (sort) {
                                            SortType.NAME_ASC -> stringResource(R.string.sort_name_asc)
                                            SortType.NAME_DESC -> stringResource(R.string.sort_name_desc)
                                            SortType.DATE_NEWEST -> stringResource(R.string.sort_date_newest)
                                            SortType.DATE_OLDEST -> stringResource(R.string.sort_date_oldest)
                                            SortType.SIZE_LARGEST -> stringResource(R.string.sort_size_largest)
                                            SortType.SIZE_SMALLEST -> stringResource(R.string.sort_size_smallest)
                                        }
                                    )
                                },
                                onClick = {
                                    onSortChange(sort)
                                    showDropdown = false
                                },
                                leadingIcon = if (currentSort == sort) {
                                    { Icon(Icons.Default.Check, contentDescription = null) }
                                } else null
                            )
                        }
                    }
                }

                // Campo de busca expansível
                AnimatedVisibility(
                    visible = isSearchExpanded,
                    enter = fadeIn(animationSpec = tween(200)) + expandHorizontally(animationSpec = tween(200)),
                    exit = fadeOut(animationSpec = tween(200)) + shrinkHorizontally(animationSpec = tween(200))
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        modifier = Modifier.width(200.dp),
                        placeholder = { Text("Buscar...") },
                        singleLine = true,
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { onSearchQueryChange("") }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Limpar")
                                }
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        )
                    )
                }

                // Botão de busca
                IconButton(
                    onClick = {
                        if (isSearchExpanded) {
                            onSearchQueryChange("")
                            onSearchExpandChange(false)
                        } else {
                            onSearchExpandChange(true)
                        }
                    }
                ) {
                    Icon(
                        imageVector = if (isSearchExpanded) Icons.Default.Close else Icons.Default.Search,
                        contentDescription = if (isSearchExpanded) "Fechar busca" else "Buscar",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Tutorial
        if (showTutorial && tutorialAlpha > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(y = with(density) { pullOffset.toDp() })
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .alpha(tutorialAlpha)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .border(
                            width = 3.dp,
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(12.dp)
                        )
                        .background(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.SwipeDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .size(28.dp)
                                .offset(y = (tutorialAlpha * 8).dp)
                        )

                        Spacer(modifier = Modifier.width(12.dp))

                        Text(
                            text = stringResource(R.string.pull_to_refresh_tutorial),
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}


@Composable
fun FolderScreen(
    folderPath: String,
    onFolderClick: (String) -> Unit,
    selectedItems: MutableList<String>,
    onSelectionChange: (List<String>) -> Unit,
    renameTrigger: Int,
    deletedVideoPath: String? = null,
    isSecureMode: Boolean = false,
    isRootLevel: Boolean = false,
    showPrivateFolders: Boolean = false,
    isMoveMode: Boolean = false,
    itemsToMove: List<String> = emptyList()
) {
    var hasPermission by remember { mutableStateOf(hasManageExternalStoragePermission()) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasPermission = hasManageExternalStoragePermission()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Cache de items por pasta para evitar flicker durante transições
    var itemsByPath by remember { mutableStateOf<Map<String, List<MediaItem>>>(emptyMap()) }
    var loadingPaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    var sortType by remember { mutableStateOf(SortType.NAME_ASC) }

    // Items da pasta atual (para compatibilidade com indicador de scan)
    val items = itemsByPath[folderPath] ?: emptyList()

    val scannerCache by FolderVideoScanner.cache.collectAsState()
    val isScanning by FolderVideoScanner.isScanning.collectAsState()

    val showThumbnails by remember { derivedStateOf { OptimizedThumbnailManager.isShowThumbnailsEnabled(context) }}
    val showDurations by remember { derivedStateOf { OptimizedThumbnailManager.isShowDurationsEnabled(context) }}
    val showFileSizes by remember { derivedStateOf { OptimizedThumbnailManager.isShowFileSizesEnabled(context) }}

    var searchQuery by remember { mutableStateOf("") }
    var isSearchExpanded by remember { mutableStateOf(false) }

    // ✅ Função de refresh simplificada
    fun performRefresh() {
        coroutineScope.launch {
            FolderVideoScanner.startScan(context, coroutineScope, forceRefresh = true)
        }
    }

    // ✅ Auto-refresh quando volta para a tela (opcional)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && scannerCache.isNotEmpty()) {
                // Refresh suave em background quando volta para o app
                coroutineScope.launch {
                    delay(500)
                    FolderVideoScanner.startScan(context, coroutineScope, forceRefresh = false)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (!hasPermission) {
        PermissionRequestScreen()
        return
    }

    // Sincroniza thumbnails ao entrar na pasta (background, não bloqueia UI)
    LaunchedEffect(folderPath) {
        OptimizedThumbnailManager.syncThumbnails(context, folderPath)
    }

    // Carrega items para a pasta atual e mantém cache das anteriores
    LaunchedEffect(folderPath, sortType, renameTrigger, isSecureMode, showPrivateFolders, scannerCache, searchQuery) {
        // Marca esta pasta como carregando
        loadingPaths = loadingPaths + folderPath

        val loadedItems = withContext(Dispatchers.IO) {
            val allItems = loadFolderContent(context, folderPath, sortType, isSecureMode, isRootLevel, showPrivateFolders)

            // Filtro de busca
            if (searchQuery.isNotEmpty()) {
                allItems.filter { item ->
                    item.name.contains(searchQuery, ignoreCase = true)
                }
            } else {
                allItems
            }
        }

        // Atualiza o cache mantendo apenas pastas relevantes (limita memória)
        val parentPath = File(folderPath).parent ?: ""
        itemsByPath = itemsByPath
            .filterKeys { path ->
                path == folderPath ||
                path == parentPath ||
                folderPath.startsWith("$path/")
            }
            .toMutableMap()
            .apply { put(folderPath, loadedItems) }

        loadingPaths = loadingPaths - folderPath
    }

    LaunchedEffect(deletedVideoPath) {
        deletedVideoPath?.let { path ->
            // Atualiza o cache removendo o item deletado
            itemsByPath = itemsByPath.mapValues { (_, itemList) ->
                itemList.filterNot { it.path == path }
            }
            selectedItems.remove(path)
            onSelectionChange(selectedItems.toList())
        }
    }

    // ✅ Loading inicial grande (primeira vez)
    if (scannerCache.isEmpty() && isScanning) {
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
                    text = stringResource(R.string.indexing_videos),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    } else {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            @Suppress("UnusedBoxWithConstraintsScope")
            val gridColumns = (this.maxWidth.value.toInt() / 130).coerceAtLeast(2)
            Log.d("FolderGrid", "maxWidth=${this.maxWidth}, gridColumns=$gridColumns")

            Column(modifier = Modifier.fillMaxSize()) {
                SortRow(
                    currentSort = sortType,
                    onSortChange = { sortType = it },
                    isRefreshing = isScanning,
                    onRefresh = { performRefresh() },
                    searchQuery = searchQuery,
                    isSearchExpanded = isSearchExpanded,
                    onSearchQueryChange = { searchQuery = it },
                    onSearchExpandChange = { isSearchExpanded = it }
                )

                // AnimatedContent para transição suave entre pastas
                AnimatedContent(
                    targetState = folderPath,
                    transitionSpec = {
                        // Determina a direção baseada na profundidade do path
                        val isGoingDeeper = targetState.length > initialState.length

                        if (isGoingDeeper) {
                            // Navegando para subpasta: slide da direita
                            (slideInHorizontally(
                                animationSpec = tween(250),
                                initialOffsetX = { fullWidth -> fullWidth / 3 }
                            ) + fadeIn(animationSpec = tween(200))) togetherWith
                            (slideOutHorizontally(
                                animationSpec = tween(250),
                                targetOffsetX = { fullWidth -> -fullWidth / 3 }
                            ) + fadeOut(animationSpec = tween(150)))
                        } else {
                            // Voltando para pasta anterior: slide da esquerda
                            (slideInHorizontally(
                                animationSpec = tween(250),
                                initialOffsetX = { fullWidth -> -fullWidth / 3 }
                            ) + fadeIn(animationSpec = tween(200))) togetherWith
                            (slideOutHorizontally(
                                animationSpec = tween(250),
                                targetOffsetX = { fullWidth -> fullWidth / 3 }
                            ) + fadeOut(animationSpec = tween(150)))
                        }
                    },
                    label = "FolderTransition"
                ) { targetPath ->
                    // Usa os items específicos deste path do cache
                    val pathItems = itemsByPath[targetPath] ?: emptyList()
                    val isPathLoading = targetPath in loadingPaths
                    val pathEmptyState = pathItems.isEmpty() && !isPathLoading && !isScanning

                    Column(modifier = Modifier.fillMaxSize()) {
                        // Loading enquanto carrega os items desta pasta
                        if (isPathLoading && pathItems.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(120.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(32.dp),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        } else if (pathEmptyState) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(120.dp)
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.VideoLibrary,
                                        contentDescription = null,
                                        modifier = Modifier.size(32.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = stringResource(R.string.no_videos_found),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = stringResource(R.string.pull_down_to_scan),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }

                        LazyColumn(
                            contentPadding = PaddingValues(8.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(pathItems.chunked(gridColumns), key = { chunk -> "${targetPath}_${chunk.joinToString { it.path }}" }) { rowItems ->
                                MediaRow(
                                    items = rowItems,
                                    gridColumns = gridColumns,
                                    selectedItems = selectedItems,
                                    showThumbnails = showThumbnails,
                                    showDurations = showDurations,
                                    showFileSizes = showFileSizes,
                                    isSecureMode = isSecureMode,
                                    isMoveMode = isMoveMode,
                                    itemsToMove = itemsToMove,
                                    onFolderClick = onFolderClick,
                                    onSelectionChange = onSelectionChange
                                )
                            }

                            if (pathEmptyState) {
                                item { Spacer(modifier = Modifier.height(200.dp)) }
                            }
                        }
                    }
                }
            }

            // ✅ ÚNICO indicador de scan em background (discreto, no topo)
            if (isScanning && items.isNotEmpty()) {
                Card(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
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
                            text = "Atualizando...",
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
    isMoveMode: Boolean,
    itemsToMove: List<String>,
    onFolderClick: (String) -> Unit,
    onSelectionChange: (List<String>) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items.forEach { item ->
            MediaCard(
                item = item,
                isSelected = item.path in selectedItems,
                isBeingMoved = isMoveMode && item.path in itemsToMove,
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
    isBeingMoved: Boolean = false,
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

    // ✅ NOVO: Estado para controlar tentativas de regeneração
    var retryCount by remember(item.path) { mutableStateOf(0) }
    var hasError by remember(item.path) { mutableStateOf(false) }

    val randomColor = remember(item.path) {
        if (!item.isFolder) getRandomColor(item.path) else Color.Transparent
    }

    // ✅ FUNÇÃO PARA LIMPAR CACHE E REGENERAR
    fun regenerateThumbnail() {
        if (retryCount < 2) { // Máximo 2 tentativas
            // Limpa cache do ThumbnailManager
            OptimizedThumbnailManager.clearCacheForPath(item.path)

            // Limpa estado local
            thumbnail = null
            duration = null
            fileSize = null
            hasError = false
            retryCount++

            // Força recarregamento
            isLoading = true
        } else {
            hasError = true
            isLoading = false
        }
    }

    LaunchedEffect(item.path, showThumbnails, showDurations, showFileSizes, retryCount) {
        if (!item.isFolder && (showThumbnails || showDurations || showFileSizes) && !hasError) {
            job?.cancel()
            isLoading = true

            job = coroutineScope.launch(Dispatchers.IO) {
                try {
                    delay(100)

                    // Check for saved thumbnail from locked folder
                    if (showThumbnails) {
                        val lockedThumb = FolderLockManager.getLockedThumbnail(item.path)
                        if (lockedThumb != null) {
                            withContext(Dispatchers.Main) {
                                thumbnail = lockedThumb
                                isLoading = false
                            }
                            return@launch
                        }
                    }

                    val videoUri = if (isSecureMode) Uri.fromFile(File(item.path)) else item.uri

                    videoUri?.let { uri ->
                        OptimizedThumbnailManager.loadVideoMetadataWithDelay(
                            context = context,
                            videoUri = uri,
                            videoPath = item.path,
                            imageLoader = null,
                            delayMs = 0L,
                            onMetadataLoaded = { metadata ->
                                // ✅ VERIFICAÇÃO DUPLA: Válido E não reciclado
                                if (showThumbnails && metadata.thumbnail != null && !metadata.thumbnail.isRecycled) {
                                    thumbnail = metadata.thumbnail
                                } else if (showThumbnails && metadata.thumbnail?.isRecycled == true) {
                                    // ✅ DETECTOU BITMAP RECICLADO → REGENERAR
                                    regenerateThumbnail()
                                    return@loadVideoMetadataWithDelay
                                }

                                if (showDurations) duration = metadata.duration
                                if (showFileSizes) fileSize = metadata.fileSize
                                isLoading = false
                            },
                            onCancelled = {
                                thumbnail = null
                                duration = null
                                fileSize = null
                                isLoading = false
                            },
                            onStateChanged = { }
                        )
                    }
                } catch (e: Exception) {
                    // ✅ EM CASO DE ERRO → TENTAR REGENERAR
                    if (retryCount < 2) {
                        regenerateThumbnail()
                    } else {
                        hasError = true
                        thumbnail = null
                        duration = null
                        fileSize = null
                        isLoading = false
                    }
                }
            }
        }
    }

    DisposableEffect(item.path) {
        onDispose {
            job?.cancel()
        }
    }

    Card(
        modifier = modifier
            .aspectRatio(1f)
            .combinedClickable(onClick = onTap, onLongClick = onLongPress)
            .border(
                width = when {
                    isSelected -> 2.dp
                    isBeingMoved -> 2.dp
                    else -> 0.dp
                },
                color = when {
                    isSelected -> MaterialTheme.colorScheme.primary
                    isBeingMoved -> Color(0xFFFF9800)
                    else -> Color.Transparent
                },
                shape = RoundedCornerShape(12.dp)
            ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (item.isFolder) {
                FolderContent(item)
            } else {
                VideoContent(
                    item = item,
                    thumbnail = thumbnail,
                    duration = duration,
                    fileSize = fileSize,
                    isLoading = isLoading,
                    isSelected = isSelected,
                    isBeingMoved = isBeingMoved,
                    showThumbnails = showThumbnails,
                    randomColor = randomColor,
                    gridColumns = gridColumns,
                    hasError = hasError,
                    retryCount = retryCount,
                    onRetry = { regenerateThumbnail() }
                )
            }
        }
    }
}

// SIMPLIFICADO - Conteúdo da pasta
@Composable
private fun FolderContent(item: MediaItem) {
    val isSecure = item.name.startsWith(".")
    val isLocked = FolderLockManager.isLocked(item.path)

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f)),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (isLocked) {
            // Locked folder: folder icon with lock badge
            Box(
                modifier = Modifier.size(40.dp).weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    tint = Color(0xFFE91E63),
                    modifier = Modifier.size(40.dp)
                )
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .size(16.dp)
                        .align(Alignment.Center)
                        .offset(y = 3.dp)
                )
            }
        } else {
            Icon(
                imageVector = when {
                    isSecure -> Icons.Default.FolderSpecial
                    else -> Icons.Default.Folder
                },
                contentDescription = null,
                tint = when {
                    isSecure -> Color(0xFFFF6B35)
                    else -> MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.size(40.dp).weight(1f)
            )
        }

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
    isBeingMoved: Boolean,
    showThumbnails: Boolean,
    randomColor: Color,
    gridColumns: Int,
    hasError: Boolean = false,
    retryCount: Int = 0,
    onRetry: () -> Unit = {}
) {
    val textSize = when {
        gridColumns <= 2 -> 12.sp
        gridColumns <= 3 -> 10.sp
        gridColumns <= 5 -> 8.sp
        else -> 7.sp
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            !showThumbnails -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(randomColor)
                    .alpha(when {
                        isSelected -> 0.7f
                        isBeingMoved -> 0.8f
                        else -> 1f
                    })
            )
            // ✅ VERIFICAÇÃO: Se bitmap reciclado durante renderização → tentar regenerar
            thumbnail != null && !thumbnail.isRecycled -> {
                Image(
                    bitmap = thumbnail.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(when {
                            isSelected -> 0.7f
                            isBeingMoved -> 0.8f
                            else -> 1f
                        })
                )
            }
            thumbnail != null && thumbnail.isRecycled -> {
                // ✅ BITMAP RECICLADO DETECTADO → Tentar regenerar automaticamente
                LaunchedEffect(Unit) {
                    onRetry()
                }
                // Mostra cor aleatória enquanto regenera
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(randomColor),
                    contentAlignment = Alignment.Center
                ) {
                    if (retryCount > 0) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    }
                }
            }
            isLoading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(randomColor.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
            }
            // ✅ FALLBACK FINAL: Só usa cor aleatória quando realmente tem erro
            hasError -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(randomColor),
                contentAlignment = Alignment.Center
            ) {
                // Opcional: Pequeno ícone indicando erro
                Icon(
                    Icons.Default.ImageNotSupported,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(24.dp)
                )
            }
            // ✅ FALLBACK: Quando não tem thumbnails habilitado
            else -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.VideoFile,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.size(40.dp)
                )
            }
        }

        // Overlay para vídeo sendo movido
        if (isBeingMoved && !isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFFFF9800).copy(alpha = 0.3f))
            )
        }

        if (!isSelected) {
            // Play button - só mostra se tem thumbnail válido
            if (thumbnail != null && !thumbnail.isRecycled && showThumbnails) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.4f))
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier
                            .size(16.dp)
                            .align(Alignment.Center)
                    )
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

        if (isBeingMoved && !isSelected) {
            Icon(
                imageVector = Icons.Default.DriveFileMove,
                contentDescription = "Sendo movido",
                tint = Color(0xFFFF9800),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .size(18.dp)
                    .background(Color.White.copy(alpha = 0.9f), CircleShape)
                    .padding(2.dp)
            )
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

    // If this folder is locked and has a session, read videos from manifest
    if (FolderLockManager.isLocked(folderPath) && LockedPlaybackSession.hasSessionForFolder(folderPath)) {
        val manifest = LockedPlaybackSession.getManifestForFolder(folderPath)
        if (manifest != null) {
            manifest.files.forEach { entry ->
                val obfuscatedFile = File(folder, entry.obfuscatedName)
                allItems.add(MediaItem(
                    path = obfuscatedFile.absolutePath,
                    uri = null,
                    isFolder = false,
                    name = entry.originalName,
                    displayName = entry.originalName
                ))
            }
        }
        // Continue recursion into subdirectories
        folder.listFiles()?.forEach { file ->
            if (file.isDirectory && file.name !in listOf(".neko_thumbs")) {
                loadSecureContentRecursive(file.absolutePath, allItems)
            }
        }
        return
    }

    folder.listFiles()?.forEach { file ->
        when {
            file.name in listOf(".nekovideo") -> return@forEach
            file.isDirectory -> {
                loadSecureContentRecursive(file.absolutePath, allItems)
            }
            file.isFile && file.extension.lowercase() in videoExtensions -> {
                allItems.add(MediaItem(file.absolutePath, null, false))
            }
        }
    }
}