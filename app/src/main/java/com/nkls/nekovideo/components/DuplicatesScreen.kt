package com.nkls.nekovideo.components

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nkls.nekovideo.R
import com.nkls.nekovideo.services.FolderVideoScanner
import com.nkls.nekovideo.services.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

data class VideoWithDuration(
    val videoInfo: VideoInfo,
    val durationMs: Long,
    val durationFormatted: String
)

data class DuplicateGroup(
    val durationMs: Long,
    val durationFormatted: String,
    val videos: List<VideoWithDuration>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicatesScreen(
    onBack: () -> Unit,
    onDeleteVideos: (List<String>) -> Unit,
    showPrivateFolders: Boolean = false
) {
    val context = LocalContext.current
    val scannerCache by FolderVideoScanner.cache.collectAsState()

    // BackHandler para voltar com o botão de voltar do sistema
    androidx.activity.compose.BackHandler(enabled = true) {
        onBack()
    }

    var isLoading by remember { mutableStateOf(false) }
    var hasSearched by remember { mutableStateOf(false) }
    var duplicateGroups by remember { mutableStateOf<List<DuplicateGroup>>(emptyList()) }
    var selectedVideos by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    // Função para iniciar a busca
    fun startSearch() {
        isLoading = true
        hasSearched = true
    }

    // Executa a busca quando isLoading muda para true
    LaunchedEffect(isLoading) {
        if (isLoading) {
            duplicateGroups = withContext(Dispatchers.IO) {
                findDuplicates(context, scannerCache, showPrivateFolders)
            }
            isLoading = false
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.duplicates_title))
                        if (duplicateGroups.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.duplicates_found_count, duplicateGroups.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (selectedVideos.isNotEmpty()) {
                        Text(
                            text = "${selectedVideos.size}",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.action_delete),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    } else if (hasSearched && !isLoading) {
                        // Botão para refazer busca
                        IconButton(onClick = { startSearch() }) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.refresh),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                // Estado inicial - aguardando busca
                !hasSearched && !isLoading -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(72.dp),
                            tint = Color(0xFF9C27B0).copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = stringResource(R.string.duplicates_ready_title),
                            style = MaterialTheme.typography.titleLarge,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(
                                if (showPrivateFolders) R.string.duplicates_ready_desc_with_private
                                else R.string.duplicates_ready_desc
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 32.dp)
                        )
                        Spacer(modifier = Modifier.height(32.dp))
                        Button(
                            onClick = { startSearch() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF9C27B0)
                            ),
                            modifier = Modifier.padding(horizontal = 32.dp)
                        ) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.duplicates_search_button))
                        }
                    }
                }

                isLoading -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.duplicates_scanning),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                duplicateGroups.isEmpty() -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = Color(0xFF4CAF50)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.duplicates_none_found),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.duplicates_none_found_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 32.dp)
                        )
                    }
                }

                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(duplicateGroups, key = { it.durationMs }) { group ->
                            DuplicateGroupCard(
                                group = group,
                                selectedVideos = selectedVideos,
                                onVideoSelected = { path ->
                                    selectedVideos = if (path in selectedVideos) {
                                        selectedVideos - path
                                    } else {
                                        selectedVideos + path
                                    }
                                }
                            )
                        }

                        item {
                            Spacer(modifier = Modifier.height(80.dp))
                        }
                    }
                }
            }
        }
    }

    // Dialog de confirmação de exclusão
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.confirm_deletion)) },
            text = {
                Text(stringResource(R.string.delete_confirmation_message, selectedVideos.size))
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteVideos(selectedVideos.toList())
                        selectedVideos = emptySet()
                        showDeleteDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun DuplicateGroupCard(
    group: DuplicateGroup,
    selectedVideos: Set<String>,
    onVideoSelected: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header do grupo
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                Icon(
                    Icons.Default.AccessTime,
                    contentDescription = null,
                    tint = Color(0xFF9C27B0),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.duplicates_duration, group.durationFormatted),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.weight(1f))
                Surface(
                    color = Color(0xFF9C27B0).copy(alpha = 0.2f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.duplicates_video_count, group.videos.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF9C27B0),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // Lista horizontal de vídeos
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(group.videos, key = { it.videoInfo.path }) { video ->
                    DuplicateVideoCard(
                        video = video,
                        isSelected = video.videoInfo.path in selectedVideos,
                        onSelect = { onVideoSelected(video.videoInfo.path) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DuplicateVideoCard(
    video: VideoWithDuration,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    val context = LocalContext.current
    var thumbnail by remember(video.videoInfo.path) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(video.videoInfo.path) {
        thumbnail = withContext(Dispatchers.IO) {
            loadThumbnail(context, video.videoInfo.uri, video.videoInfo.path)
        }
    }

    Card(
        modifier = Modifier
            .width(140.dp)
            .clickable { onSelect() }
            .then(
                if (isSelected) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                } else {
                    Modifier
                }
            ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column {
            // Thumbnail
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                if (thumbnail != null && !thumbnail!!.isRecycled) {
                    Image(
                        bitmap = thumbnail!!.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.VideoFile,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                // Checkbox de seleção
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                ) {
                    Surface(
                        modifier = Modifier.size(24.dp),
                        shape = CircleShape,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.5f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (isSelected) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                // Tamanho do arquivo
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(4.dp),
                    color = Color.Black.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = formatFileSize(video.videoInfo.sizeInBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }

            // Info do vídeo
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    text = File(video.videoInfo.path).name,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = File(video.videoInfo.path).parentFile?.name ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// Função para verificar se uma pasta é privada (.nekovideo)
private fun isPrivateFolder(path: String): Boolean {
    val file = File(path)
    val parentFolder = file.parentFile ?: return false

    // Verifica se a pasta pai ou algum ancestral é .nekovideo
    var current: File? = parentFolder
    while (current != null) {
        if (current.name == ".nekovideo" || current.name.startsWith(".")) {
            return true
        }
        // Verifica se existe .nomedia na pasta
        if (File(current, ".nomedia").exists()) {
            return true
        }
        current = current.parentFile
    }
    return false
}

// Função para encontrar duplicatas
private suspend fun findDuplicates(
    context: Context,
    cache: Map<String, com.nkls.nekovideo.services.FolderInfo>,
    showPrivateFolders: Boolean
): List<DuplicateGroup> {
    val allVideos = mutableListOf<VideoWithDuration>()

    // Coleta todos os vídeos do cache
    cache.values.forEach { folderInfo ->
        folderInfo.videos.forEach { videoInfo ->
            // Filtra pastas privadas se showPrivateFolders for false
            if (!showPrivateFolders && isPrivateFolder(videoInfo.path)) {
                return@forEach // Pula este vídeo
            }

            try {
                val duration = getVideoDuration(context, videoInfo.uri, videoInfo.path)
                if (duration > 0) {
                    allVideos.add(
                        VideoWithDuration(
                            videoInfo = videoInfo,
                            durationMs = duration,
                            durationFormatted = formatDuration(duration)
                        )
                    )
                }
            } catch (e: Exception) {
                // Ignora vídeos com erro
            }
        }
    }

    // Agrupa por duração (arredondada para segundos)
    val groupedByDuration = allVideos.groupBy { it.durationMs / 1000 * 1000 } // Arredonda para segundos

    // Filtra apenas grupos com 2+ vídeos
    return groupedByDuration
        .filter { it.value.size >= 2 }
        .map { (durationMs, videos) ->
            DuplicateGroup(
                durationMs = durationMs,
                durationFormatted = formatDuration(durationMs),
                videos = videos.sortedBy { it.videoInfo.path }
            )
        }
        .sortedByDescending { it.videos.size }
}

private fun getVideoDuration(context: Context, uri: Uri, path: String): Long {
    val retriever = MediaMetadataRetriever()
    return try {
        if (path.isNotEmpty() && File(path).exists()) {
            retriever.setDataSource(path)
        } else {
            retriever.setDataSource(context, uri)
        }
        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        duration?.toLongOrNull() ?: 0L
    } catch (e: Exception) {
        0L
    } finally {
        try {
            retriever.release()
        } catch (e: Exception) {
            // Ignora
        }
    }
}

private fun loadThumbnail(context: Context, uri: Uri, path: String): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        if (path.isNotEmpty() && File(path).exists()) {
            retriever.setDataSource(path)
        } else {
            retriever.setDataSource(context, uri)
        }
        retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
    } catch (e: Exception) {
        null
    } finally {
        try {
            retriever.release()
        } catch (e: Exception) {
            // Ignora
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%d:%02d", minutes, seconds)
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes >= 1_073_741_824 -> String.format("%.1f GB", bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> String.format("%.1f MB", bytes / 1_048_576.0)
        bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
