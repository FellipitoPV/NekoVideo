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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
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
            items(
                items = videoFolders,
                key = { folder -> folder.path }
            ) { folderData ->
                FolderItem(
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
fun FolderItem(
    folderData: FolderData,
    isSelected: Boolean,
    onLongPress: () -> Unit,
    onTap: () -> Unit
) {
    val folderName = File(folderData.path).name

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .pointerInput(folderData.path) {
                detectTapGestures(
                    onLongPress = { onLongPress() },
                    onTap = { onTap() }
                )
            }
            .clip(RoundedCornerShape(16.dp))
            .shadow(4.dp, RoundedCornerShape(16.dp))
            .border(
                width = if (isSelected) 2.dp else 0.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = RoundedCornerShape(16.dp)
            ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Ícone de pasta fixo
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = "Folder Icon",
                        tint = MaterialTheme.colorScheme.primary, // Ícone azul fixo
                        modifier = Modifier.size(48.dp)
                    )
                }

                // Nome da pasta
                Text(
                    text = folderName,
                    style = MaterialTheme.typography.bodyLarge.copy(
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

            // Badge com contagem
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.8f))
            ) {
                Text(
                    text = folderData.itemCount.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center)
                )
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