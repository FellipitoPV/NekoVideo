package com.nkls.nekovideo.components.layout

import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.nkls.nekovideo.R
import com.nkls.nekovideo.billing.BillingManager
import com.nkls.nekovideo.billing.PremiumManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun TopBar(
    currentRoute: String?,
    selectedItems: List<String>,
    folderPath: String,
    navController: NavController,
    onPasswordDialog: () -> Unit,
    onSelectionClear: () -> Unit,
    onSelectAll: () -> Unit,
    premiumManager: PremiumManager,
    billingManager: BillingManager,
    isAtRootLevel: Boolean = false,
    onNavigateToPath: (String) -> Unit = {},
    onNavigateBack: () -> Unit = {}
) {
    val coroutineScope = rememberCoroutineScope()
    val maxTapInterval = 500L
    var tapCount by remember { mutableIntStateOf(0) }

    val isPremium by premiumManager.isPremium.collectAsState()

    val density = LocalDensity.current
    var isCompact by remember { mutableStateOf(false) }

    TopAppBar(
        modifier = Modifier.onSizeChanged { size ->
            isCompact = with(density) { size.width.toDp() } > 600.dp
        },
        expandedHeight = if (isCompact) 48.dp else 64.dp,
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
                currentRoute == "folder" && isAtRootLevel -> {
                    // Está na raiz: mostra ícone e nome do app com triple tap
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
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
                    // Breadcrumb interativo para navegação entre pastas
                    val rootPath = android.os.Environment.getExternalStorageDirectory().absolutePath
                    val relativePath = folderPath.removePrefix(rootPath).trim('/')
                    val pathSegments = relativePath.split('/').filter { it.isNotEmpty() }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // App icon with triple-tap for private folders toggle
                        Image(
                            painter = painterResource(id = R.drawable.ic_app_icon),
                            contentDescription = null,
                            modifier = Modifier
                                .size(28.dp)
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
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = ">",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 2.dp)
                        )

                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            itemsIndexed(pathSegments) { index, segment ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val displayName = if (segment.startsWith(".")) {
                                        segment.drop(1)
                                    } else {
                                        segment
                                    }

                                    val isCurrentFolder = index == pathSegments.size - 1

                                    Text(
                                        text = displayName,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = if (isCurrentFolder) FontWeight.SemiBold else FontWeight.Medium,
                                        color = when {
                                            segment.startsWith(".") -> Color(0xFFFF6B35)
                                            isCurrentFolder -> MaterialTheme.colorScheme.onSurface
                                            else -> MaterialTheme.colorScheme.primary
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .clickable(enabled = !isCurrentFolder) {
                                                // Construir o caminho até o segmento clicado
                                                val targetPath = rootPath + "/" + pathSegments
                                                    .take(index + 1)
                                                    .joinToString("/")
                                                onNavigateToPath(targetPath)
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
                currentRoute == "folder" && !isAtRootLevel -> {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        },
        actions = {
            IconButton(onClick = {
                if (!isPremium) {
                    billingManager.launchPurchaseFlow(
                        onSuccess = {

                        },
                        onError = { error ->
                            Log.e("TopBar", "Erro na compra: $error")
                        }
                    )
                }
                // Se já é premium, não faz nada
            }) {
                Icon(
                    imageVector = if (isPremium) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = if (isPremium) "Premium Ativo" else "Comprar Premium",
                    tint = if (isPremium) Color(0xFFFFD700) else MaterialTheme.colorScheme.onSurface
                )
            }

            // Botão SelectAll
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
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurface
        )
    )
}