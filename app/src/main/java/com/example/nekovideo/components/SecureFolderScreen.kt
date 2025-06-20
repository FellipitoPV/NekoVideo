package com.example.nekovideo.components

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import java.io.File

data class SecureMediaItem(
    val path: String,
    val isFolder: Boolean,
    val duration: String? = null,
    val id: String = path.hashCode().toString() // ID otimizado para key
)

val videoExtensions = listOf(
    "mp4", "mkv", "webm", "avi", "mov", "wmv",
    "m4v", "mpg", "mpeg", "mp2", "mpe", "mpv",
    "3gp", "3g2", "flv", "f4v",
    "asf", "rm", "rmvb", "vob", "ogv", "drc", "mxf"
)

fun getSecureFolderContents(context: Context, folderPath: String): List<SecureMediaItem> {
    val mediaItems = mutableListOf<SecureMediaItem>()
    val folder = File(folderPath)

    if (!folder.exists() || !folder.isDirectory) {
        return mediaItems
    }

    folder.listFiles()?.forEach { file ->
        if (file.name in listOf(".nomedia", ".nekovideo")) return@forEach

        if (file.isDirectory) {
            mediaItems.add(SecureMediaItem(path = file.absolutePath, isFolder = true))
        } else if (file.isFile && file.extension.lowercase() in videoExtensions) {
            mediaItems.add(SecureMediaItem(path = file.absolutePath, isFolder = false))
        }
    }

    return mediaItems.sortedWith(compareBy<SecureMediaItem> { !it.isFolder }
        .thenComparator { a, b ->
            compareNatural(File(a.path).name, File(b.path).name)
        })
}

val secureDurationCache = LruCache<String, String?>(100)

// Função para dividir lista em chunks de 3 itens
private fun <T> List<T>.chunked3(): List<List<T>> {
    return this.chunked(3)
}

@Composable
fun SecureFolderScreen(
    folderPath: String,
    onFolderClick: (String) -> Unit,
    selectedItems: MutableList<String>,
    onSelectionChange: (List<String>) -> Unit,
    renameTrigger: Int,
    deletedVideoPath: String? = null
) {
    val context = LocalContext.current
    val mediaItems = remember { mutableStateListOf<SecureMediaItem>() }
    val lazyListState = rememberLazyListState() // Mudança: LazyColumn em vez de Grid
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
        val items = withContext(Dispatchers.IO) {
            getSecureFolderContents(context, folderPath)
        }
        mediaItems.clear()
        mediaItems.addAll(items)
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

    // Limpa cache quando sai da tela
    DisposableEffect(Unit) {
        onDispose {
            OptimizedThumbnailManager.clearCache()
        }
    }

    // LazyColumn com Row manual (mais performático que Grid)
    LazyColumn(
        state = lazyListState,
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        val chunkedItems = mediaItems.chunked3()
        items(
            items = chunkedItems,
            key = { chunk -> chunk.joinToString(",") { it.id } } // Key otimizada
        ) { rowItems ->
            SecureMediaItemRow(
                items = rowItems,
                selectedItems = selectedItems,
                isScrollingFast = isScrollingFast,
                onFolderClick = onFolderClick,
                onSelectionChange = onSelectionChange
            )
        }
    }
}

@Composable
private fun SecureMediaItemRow(
    items: List<SecureMediaItem>,
    selectedItems: MutableList<String>,
    isScrollingFast: Boolean,
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
            OptimizedSecureMediaItemCard(
                mediaItem = mediaItem,
                isSelected = mediaItem.path in selectedItems,
                isScrollingFast = isScrollingFast,
                modifier = Modifier.weight(1f),
                onClick = { if (selectedItems.isEmpty()) onFolderClick(mediaItem.path) },
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OptimizedSecureMediaItemCard(
    mediaItem: SecureMediaItem,
    isSelected: Boolean,
    isScrollingFast: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onTap: () -> Unit
) {
    val context = LocalContext.current
    var thumbnailBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var videoDuration by remember { mutableStateOf<String?>(null) }
    var thumbnailState by remember { mutableStateOf(ThumbnailState.IDLE) }

    // Carregamento condicional otimizado - MELHORADO
    LaunchedEffect(mediaItem.id, isScrollingFast, thumbnailState) {
        if (!mediaItem.isFolder && !isScrollingFast) {
            // Verifica cache em memória primeiro
            val cachedThumbnail = OptimizedThumbnailManager.getCachedThumbnail(mediaItem.path)
            if (cachedThumbnail != null) {
                thumbnailBitmap = cachedThumbnail
                thumbnailState = ThumbnailState.LOADED
                videoDuration = secureDurationCache.get(mediaItem.path)
                return@LaunchedEffect
            }

            // Se ainda está IDLE e não tem thumbnail, inicia carregamento
            if (thumbnailState == ThumbnailState.IDLE && thumbnailBitmap == null) {
                thumbnailState = ThumbnailState.WAITING

                val fileUri = Uri.fromFile(File(mediaItem.path))

                OptimizedThumbnailManager.loadVideoMetadataWithDelay(
                    context = context,
                    videoUri = fileUri,
                    videoPath = mediaItem.path,
                    imageLoader = null, // Não usado no Glide
                    delayMs = 200L, // Reduzido para melhor responsividade
                    onMetadataLoaded = { metadata ->
                        thumbnailBitmap = metadata.thumbnail
                        videoDuration = metadata.duration
                        thumbnailState = ThumbnailState.LOADED
                        metadata.duration?.let {
                            secureDurationCache.put(mediaItem.path, it)
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
        }
    }

    // Card otimizado sem shadow para melhor performance
    Card(
        modifier = modifier
            .aspectRatio(1f)
            .combinedClickable( // Otimizado: combinedClickable em vez de pointerInput
                onClick = onTap,
                onLongClick = onLongPress
            )
            .clip(RoundedCornerShape(12.dp)) // Reduzido radius para melhor performance
            .border(
                width = if (isSelected) 2.dp else 0.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = RoundedCornerShape(12.dp)
            ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp) // Reduzido elevation
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (mediaItem.isFolder) {
                // PASTA OTIMIZADA
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f)),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null, // Otimizado: removido description desnecessário
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .size(40.dp)
                            .weight(1f)
                    )
                    Text(
                        text = File(mediaItem.path).name,
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                }
            } else {
                // VÍDEO OTIMIZADO
                Box(modifier = Modifier.fillMaxSize()) {
                    when {
                        thumbnailBitmap != null -> {
                            Image(
                                bitmap = thumbnailBitmap!!.asImageBitmap(),
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
                                    modifier = Modifier.size(20.dp), // Reduzido
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

                    // Overlay otimizado
                    if (!isSelected) {
                        // Ícone play - apenas quando tem thumbnail
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

                        // Nome do arquivo - sempre visível
                        Text(
                            text = File(mediaItem.path).name,
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
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

                        // Duração - apenas quando tem thumbnail
                        if (thumbnailBitmap != null) {
                            val duration = videoDuration ?: secureDurationCache.get(mediaItem.path)
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
        }
    }
}