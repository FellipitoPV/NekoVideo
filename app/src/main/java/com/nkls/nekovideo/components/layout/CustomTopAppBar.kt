package com.nkls.nekovideo.components.layout

import android.content.Context
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.nkls.nekovideo.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width

// NOVA função para detectar se é pasta segura (qualquer pasta com . no início e com marcadores)
private fun isSecureFolder(folderPath: String): Boolean {
    val folder = File(folderPath)
    val hasSecureMarkers = File(folder, ".nomedia").exists() || File(folder, ".nekovideo").exists()
    return folder.name.startsWith(".") && hasSecureMarkers
}

// SIMPLIFICADA - função para detectar se está na raiz
private fun isAtRootLevel(folderPath: String): Boolean {
    val rootPath = android.os.Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')
    val normalizedFolderPath = folderPath.trimEnd('/')
    return normalizedFolderPath == rootPath
}

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
    onSelectAll: () -> Unit,
    showPrivateFolders: Boolean
) {
    val coroutineScope = rememberCoroutineScope()
    val maxTapInterval = 500L
    var tapCount by remember { mutableStateOf(0) }

    // Detectar se é raiz pela rota
    val currentBackStackEntry = navController.currentBackStackEntryAsState()
    val encodedFolderPath = currentBackStackEntry.value?.arguments?.getString("folderPath") ?: ""
    val isRootByRoute = encodedFolderPath == "root"

    SmallTopAppBar(
        title = {
            when {
                selectedItems.isNotEmpty() -> {
                    Text(
                        text = pluralStringResource(R.plurals.items_selected, selectedItems.size, selectedItems.size),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
                currentRoute == "settings" -> {
                    Text(
                        text = stringResource(R.string.settings_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                currentRoute == "settings/playback" -> {
                    Text(
                        text = stringResource(R.string.playback_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                currentRoute == "settings/interface" -> {
                    Text(
                        text = stringResource(R.string.interface_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                currentRoute == "settings/display" -> {
                    Text(
                        text = stringResource(R.string.settings_display),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                currentRoute == "settings/files" -> {
                    Text(
                        text = stringResource(R.string.settings_files),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                currentRoute == "settings/performance" -> {
                    Text(
                        text = stringResource(R.string.settings_performance),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                currentRoute == "settings/about" -> {
                    Text(
                        text = stringResource(R.string.about_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                currentRoute == "folder" && isRootByRoute -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clickable(enabled = true, onClick = { })
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
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_app_icon),
                            contentDescription = null,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "NekoVideo",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                currentRoute == "folder" -> {
                    // NOVO: Breadcrumb simplificado - trata todas as pastas igual
                    val rootPath = android.os.Environment.getExternalStorageDirectory().absolutePath
                    val relativePath = folderPath.removePrefix(rootPath).trim('/')
                    val pathSegments = relativePath.split('/').filter { it.isNotEmpty() }

                    // LazyRow para breadcrumb scrollable
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        itemsIndexed(pathSegments) { index, segment ->
                            AnimatedVisibility(
                                visible = true,
                                enter = fadeIn(animationSpec = tween(300)),
                                exit = fadeOut(animationSpec = tween(300))
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val displayName = if (segment.startsWith(".")) {
                                        // Remove o ponto inicial para display mais limpo
                                        segment.drop(1)
                                    } else {
                                        segment
                                    }

                                    Text(
                                        text = displayName,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = if (segment.startsWith(".")) {
                                            // Cor diferente para pastas seguras
                                            Color(0xFFFF6B35)
                                        } else {
                                            MaterialTheme.colorScheme.primary
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .clickable {
                                                // Constrói o caminho até o segmento clicado
                                                val targetSegments = pathSegments.take(index + 1)
                                                val targetPath = "$rootPath/${targetSegments.joinToString("/")}"
                                                val encodedPath = Uri.encode(targetPath)
                                                navController.navigate("folder/$encodedPath") {
                                                    popUpTo("folder/root") { inclusive = false }
                                                    launchSingleTop = true
                                                }
                                            }
                                            .padding(horizontal = 4.dp, vertical = 2.dp)
                                    )

                                    if (index < pathSegments.size - 1) {
                                        Text(
                                            text = ">",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                // Para o caso quando está na raiz (com triple tap)
                currentRoute == "folder" && isRootByRoute -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clickable(enabled = true, onClick = { })
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
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_app_icon),
                            contentDescription = null,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "NekoVideo",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

// Para o caso else (também com triple tap)
                else -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clickable(enabled = true, onClick = { })
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
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_app_icon),
                            contentDescription = null,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "NekoVideo",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
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
                            contentDescription = stringResource(R.string.cancel_selection),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                currentRoute?.startsWith("settings") == true -> {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                currentRoute == "folder" && !isRootByRoute -> {
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
        },
        actions = {
            if (selectedItems.isNotEmpty() && currentRoute == "folder") {
                IconButton(onClick = onSelectAll) {
                    Icon(
                        imageVector = Icons.Default.SelectAll,
                        contentDescription = stringResource(R.string.select_all),
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