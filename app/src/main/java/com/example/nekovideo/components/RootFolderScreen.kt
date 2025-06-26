package com.example.nekovideo.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun RootFolderScreen(
    onFolderClick: (String) -> Unit,
    selectedItems: MutableList<String>,
    onSelectionChange: (List<String>) -> Unit
) {
    val context = LocalContext.current
    val videoFolders = remember { mutableStateListOf<FolderData>() }
    val gridState = rememberLazyGridState()
    var hasStoragePermission by remember { mutableStateOf(false) }
    var needsAllFilesAccess by remember { mutableStateOf(false) }
    var hasMediaPermission by remember { mutableStateOf(false) }
    var isLoaded by remember { mutableStateOf(false) }

    val requiredPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        android.Manifest.permission.READ_MEDIA_VIDEO
    } else {
        android.Manifest.permission.READ_EXTERNAL_STORAGE
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasMediaPermission = isGranted
        if (!isGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            needsAllFilesAccess = true
        }
    }

    val settingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            Environment.isExternalStorageManager()
        ) {
            hasStoragePermission = true
            needsAllFilesAccess = false
        }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            hasStoragePermission = Environment.isExternalStorageManager()
            if (!hasStoragePermission) {
                needsAllFilesAccess = true
            }
        } else {
            hasStoragePermission = true
        }

        if (ContextCompat.checkSelfPermission(
                context,
                requiredPermission
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            hasMediaPermission = true
        } else {
            permissionLauncher.launch(requiredPermission)
        }
    }

    LaunchedEffect(hasStoragePermission, hasMediaPermission) {
        if (hasStoragePermission && hasMediaPermission) {
            val folders = withContext(Dispatchers.IO) {
                getOptimizedVideoFolders(context)
            }
            videoFolders.clear()
            videoFolders.addAll(folders)
            delay(100)
            isLoaded = true
        }
    }

    if (!hasStoragePermission && needsAllFilesAccess && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        PermissionScreen(
            title = "All files access required",
            buttonText = "Grant Access",
            onGrantClick = {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                settingsLauncher.launch(intent)
            }
        )
    } else if (!hasMediaPermission) {
        PermissionScreen(
            title = "Storage permission required",
            buttonText = "Grant Permission",
            onGrantClick = { permissionLauncher.launch(requiredPermission) }
        )
    } else {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            state = gridState,
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(
                items = videoFolders,
                key = { _, folder -> folder.path }
            ) { index, folderData ->
                AnimatedVisibility(
                    visible = isLoaded,
                    enter = fadeIn(
                        animationSpec = tween(
                            durationMillis = 300,
                            delayMillis = index * 50
                        )
                    ) + scaleIn(
                        animationSpec = tween(
                            durationMillis = 300,
                            delayMillis = index * 50
                        ),
                        initialScale = 0.8f
                    )
                ) {
                    SimpleFolderItem(
                        folderData = folderData,
                        isSelected = folderData.path in selectedItems,
                        onLongPress = {
                            if (folderData.path in selectedItems) {
                                selectedItems.remove(folderData.path)
                            } else {
                                selectedItems.add(folderData.path)
                            }
                            onSelectionChange(selectedItems.toList())
                        },
                        onTap = {
                            if (selectedItems.isNotEmpty()) {
                                if (folderData.path in selectedItems) {
                                    selectedItems.remove(folderData.path)
                                } else {
                                    selectedItems.add(folderData.path)
                                }
                                onSelectionChange(selectedItems.toList())
                            } else {
                                onFolderClick(folderData.path)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionScreen(
    title: String,
    buttonText: String,
    onGrantClick: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Button(
                onClick = onGrantClick,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text(
                    text = buttonText,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

@Composable
fun SimpleFolderItem(
    folderData: FolderData,
    isSelected: Boolean,
    onLongPress: () -> Unit,
    onTap: () -> Unit
) {
    val folderName = File(folderData.path).name
    var isPressed by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = 0.6f)
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .scale(scale)
            .pointerInput(folderData.path) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        tryAwaitRelease()
                        isPressed = false
                    },
                    onLongPress = { onLongPress() },
                    onTap = { onTap() }
                )
            }
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Ícone da pasta
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = "Folder Icon",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(48.dp)
                        .weight(1f)
                )

                // Nome da pasta
                Text(
                    text = folderName,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Medium
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 8.dp)
                )
            }

            // Badge de contagem
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            ) {
                Text(
                    text = folderData.itemCount.toString(),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            // Indicador de seleção
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

data class FolderData(
    val path: String,
    val itemCount: Int,
    val lastModified: Long
)

private suspend fun getOptimizedVideoFolders(context: Context): List<FolderData> =
    withContext(Dispatchers.IO) {
        val projection = arrayOf(
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.DATE_MODIFIED
        )

        val cursor = context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Video.Media.DATE_MODIFIED} DESC"
        )

        val folderMap = mutableMapOf<String, MutableList<Long>>()
        val rootPath = Environment.getExternalStorageDirectory().absolutePath

        cursor?.use {
            val dataColumn = it.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
            val modifiedColumn = it.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)

            while (it.moveToNext()) {
                val path = it.getString(dataColumn)
                val modified = it.getLong(modifiedColumn)

                val parent = File(path).parent
                if (parent != null && parent.startsWith(rootPath)) {
                    val relativePath = parent.removePrefix(rootPath).trim('/')
                    val parentFolder = relativePath.split('/').firstOrNull() ?: ""
                    if (parentFolder.isNotEmpty()) {
                        val folderPath = "$rootPath/$parentFolder"
                        folderMap.getOrPut(folderPath) { mutableListOf() }.add(modified)
                    }
                }
            }
        }

        folderMap.map { (folderPath, videos) ->
            val lastModified = videos.maxOrNull() ?: 0L
            FolderData(
                path = folderPath,
                itemCount = videos.size,
                lastModified = lastModified
            )
        }.sortedWith { a, b ->
            compareNatural(File(a.path).name, File(b.path).name)
        }
    }

fun compareNatural(a: String, b: String): Int {
    val regex = Regex("(\\d+|\\D+)")
    val partsA = regex.findAll(a).map { it.value }.toList()
    val partsB = regex.findAll(b).map { it.value }.toList()

    for (i in 0 until minOf(partsA.size, partsB.size)) {
        val partA = partsA[i]
        val partB = partsB[i]

        val numA = partA.toIntOrNull()
        val numB = partB.toIntOrNull()

        val comparison = if (numA != null && numB != null) {
            numA.compareTo(numB)
        } else {
            partA.compareTo(partB)
        }

        if (comparison != 0) return comparison
    }

    return partsA.size.compareTo(partsB.size)
}