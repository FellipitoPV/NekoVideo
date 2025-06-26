package com.example.nekovideo.components

import android.content.Context
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallTopAppBar
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.nekovideo.components.helpers.FilesManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun CustomTopAppBar(
    currentRoute: String?,
    selectedItems: List<String>,
    folderPath: String,
    context: Context,
    navController: NavController,
    onPasswordDialog: () -> Unit,
    onSelectionClear: () -> Unit,
    onSelectAll: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val maxTapInterval = 500L
    var tapCount by remember { mutableStateOf(0) }

    SmallTopAppBar(
        title = {
            when {
                selectedItems.isNotEmpty() -> {
                    Text(
                        text = "${selectedItems.size} item(s) selected",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
                currentRoute == "settings" -> {
                    Text(
                        text = "Configurações",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                currentRoute == "settings/playback" -> {
                    Text(
                        text = "Reprodução",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                currentRoute == "settings/interface" -> {
                    Text(
                        text = "Interface",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                currentRoute == "settings/about" -> {
                    Text(
                        text = "Sobre",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                currentRoute == "video_folders" -> {
                    Text(
                        text = "NekoVideo",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clickable(
                                enabled = true,
                                onClick = { /* Handled by pointerInput */ }
                            )
                            .pointerInput(Unit) {
                                detectTapGestures { _ ->
                                    tapCount++
                                    if (tapCount == 1) {
                                        coroutineScope.launch {
                                            delay(maxTapInterval)
                                            if (tapCount < 3) {
                                                tapCount = 0
                                            }
                                        }
                                    }
                                    if (tapCount >= 3) {
                                        tapCount = 0
                                        onPasswordDialog()
                                    }
                                }
                            }
                    )
                }
                else -> {
                    val secureFolderPath = FilesManager.SecureStorage.getSecureFolderPath(context)
                    val rootPath = android.os.Environment.getExternalStorageDirectory().absolutePath
                    val basePath = if (currentRoute == "secure_folder") secureFolderPath else rootPath
                    val relativePath = folderPath.removePrefix(basePath).trim('/')
                    val pathSegments = relativePath.split('/').filter { it.isNotEmpty() }
                    val displayPath = if (currentRoute == "secure_folder" && relativePath.isEmpty()) {
                        listOf("secure_video")
                    } else {
                        if (currentRoute == "secure_folder") listOf("secure_video") + pathSegments else pathSegments
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        displayPath.forEachIndexed { index, segment ->
                            AnimatedVisibility(
                                visible = true,
                                enter = fadeIn(animationSpec = tween(300)),
                                exit = fadeOut(animationSpec = tween(300))
                            ) {
                                Text(
                                    text = segment,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .clickable {
                                            val targetPath = if (currentRoute == "secure_folder" && index == 0) {
                                                secureFolderPath
                                            } else {
                                                val segmentsUpToIndex = displayPath.take(index + 1)
                                                val prefix = if (currentRoute == "secure_folder") secureFolderPath else rootPath
                                                if (currentRoute == "secure_folder" && segmentsUpToIndex[0] == "secure_video") {
                                                    "$secureFolderPath/${segmentsUpToIndex.drop(1).joinToString("/")}"
                                                } else {
                                                    "$prefix/${segmentsUpToIndex.joinToString("/")}"
                                                }
                                            }
                                            val encodedPath = Uri.encode(targetPath)
                                            val targetRoute = if (currentRoute == "secure_folder") "secure_folder" else "video_list"
                                            navController.navigate("$targetRoute/$encodedPath") {
                                                popUpTo("video_folders") { inclusive = false }
                                                launchSingleTop = true
                                            }
                                        }
                                        .padding(end = 8.dp)
                                )
                            }
                            if (index < displayPath.size - 1) {
                                Text(
                                    text = ">",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        navigationIcon = {
            when {
                selectedItems.isNotEmpty() -> {
                    IconButton(onClick = onSelectionClear) {
                        Icon(
                            imageVector = Icons.Default.Cancel,
                            contentDescription = "Cancel Selection",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                currentRoute?.startsWith("settings") == true -> {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                currentRoute == "video_list" || currentRoute == "secure_folder" -> {
                    val rootPath = android.os.Environment.getExternalStorageDirectory().absolutePath
                    val secureFolderPath = FilesManager.SecureStorage.getSecureFolderPath(context)

                    if ((currentRoute == "video_list" && folderPath == rootPath) ||
                        (currentRoute == "secure_folder" && folderPath == secureFolderPath)) {
                        IconButton(onClick = {
                            navController.navigate("video_folders") {
                                popUpTo("video_folders") { inclusive = true }
                                launchSingleTop = true
                            }
                        }) {
                            Icon(
                                imageVector = Icons.Default.ArrowBack,
                                contentDescription = "Back",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    } else {
                        IconButton(onClick = {
                            navController.popBackStack()
                        }) {
                            Icon(
                                imageVector = Icons.Default.ArrowBack,
                                contentDescription = "Back",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        },
        actions = {
            // AGORA FUNCIONA TANTO PARA video_list QUANTO secure_folder
            if (selectedItems.isNotEmpty() &&
                (currentRoute == "video_list" || currentRoute == "secure_folder")) {
                IconButton(onClick = onSelectAll) {
                    Icon(
                        imageVector = Icons.Default.SelectAll,
                        contentDescription = "Select All",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        },
        colors = TopAppBarDefaults.smallTopAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurface
        )
    )
}