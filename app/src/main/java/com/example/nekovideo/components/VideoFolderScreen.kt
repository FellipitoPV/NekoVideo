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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
fun VideoFolderScreen(
    onFolderClick: (String) -> Unit,
    selectedItems: MutableList<String>,
    onSelectionChange: (List<String>) -> Unit
) {
    val context = LocalContext.current
    val videoFolders = remember { mutableStateListOf<String>() }
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
        // Verificar MANAGE_EXTERNAL_STORAGE para API 30+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            hasStoragePermission = Environment.isExternalStorageManager()
            if (!hasStoragePermission) {
                needsAllFilesAccess = true
            }
        } else {
            hasStoragePermission = true // Para APIs menores, usamos READ/WRITE_EXTERNAL_STORAGE
        }

        // Verificar READ_MEDIA_VIDEO ou READ_EXTERNAL_STORAGE
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
                getVideoFolders(context)
            }
            println("Folders found: $folders")
            videoFolders.clear()
            videoFolders.addAll(folders)
        }
    }

    if (!hasStoragePermission && needsAllFilesAccess && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "All files access required",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Button(
                    onClick = {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        settingsLauncher.launch(intent)
                    },
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp)),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text(
                        text = "Grant Access",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    } else if (!hasMediaPermission) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
            contentAlignment = Alignment.Center
        ) {
            Text("Storage permission required")
        }
    } else {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
        ) {
            items(videoFolders) { folderPath ->
                FolderItem(
                    folderPath = folderPath,
                    isSelected = folderPath in selectedItems,
                    onClick = { onFolderClick(folderPath) },
                    onLongPress = {
                        if (folderPath in selectedItems) {
                            selectedItems.remove(folderPath)
                        } else {
                            selectedItems.add(folderPath)
                        }
                        onSelectionChange(selectedItems.toList())
                    },
                    onTap = {
                        if (selectedItems.isNotEmpty()) {
                            if (folderPath in selectedItems) {
                                selectedItems.remove(folderPath)
                            } else {
                                selectedItems.add(folderPath)
                            }
                            onSelectionChange(selectedItems.toList())
                        } else {
                            onFolderClick(folderPath)
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun FolderItem(
    folderPath: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onTap: () -> Unit
) {
    val context = LocalContext.current
    val folderName = File(folderPath).name
    val itemCount = remember { mutableStateOf(0) }

    LaunchedEffect(folderPath) {
        withContext(Dispatchers.IO) {
            itemCount.value = getItemCount(context, folderPath)
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .pointerInput(Unit) {
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
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = "Folder Icon",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(48.dp) // Ícone menor
                        .weight(1f)
                )
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
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(20.dp) // Círculo menor
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.8f))
            ) {
                Text(
                    text = itemCount.value.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.BottomEnd) // Movido para canto inferior
                        .padding(8.dp)
                        .size(20.dp)
                )
            }
        }
    }
}

private fun getVideoFolders(context: Context): List<String> {
    val projection = arrayOf(MediaStore.Video.Media.DATA)
    val cursor = context.contentResolver.query(
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        projection,
        null,
        null,
        null
    )
    val folders = mutableSetOf<String>()
    val rootPath = Environment.getExternalStorageDirectory().absolutePath // ex.: /storage/emulated/0
    cursor?.use {
        val dataColumn = it.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
        while (it.moveToNext()) {
            val path = it.getString(dataColumn)
            val parent = File(path).parent
            if (parent != null && parent.startsWith(rootPath)) {
                val relativePath = parent.removePrefix(rootPath).trim('/')
                // Extrai a pasta de primeiro nível após /storage/emulated/0
                val parentFolder = relativePath.split('/').firstOrNull() ?: ""
                if (parentFolder.isNotEmpty()) {
                    folders.add("$rootPath/$parentFolder")
                }
            }
        }
    }
    return folders.sorted()
}

private fun getItemCount(context: Context, folderPath: String): Int {
    var count = 0
    // Contar vídeos usando MediaStore
    val projection = arrayOf(MediaStore.Video.Media.DATA)
    val selection = "${MediaStore.Video.Media.DATA} LIKE ?"
    val selectionArgs = arrayOf("$folderPath/%")
    val cursor = context.contentResolver.query(
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        projection,
        selection,
        selectionArgs,
        null
    )
    cursor?.use {
        val dataColumn = it.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
        while (it.moveToNext()) {
            val path = it.getString(dataColumn)
            // Contar vídeos em qualquer subpasta
            if (path.startsWith(folderPath)) {
                count++
            }
        }
    }
    // Contar subpastas com vídeos
    val folder = File(folderPath)
    folder.listFiles { file -> file.isDirectory }?.forEach { subfolder ->
        val hasVideos = context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Video.Media.DATA),
            "${MediaStore.Video.Media.DATA} LIKE ?",
            arrayOf("${subfolder.absolutePath}/%"),
            null
        )?.use { subCursor ->
            subCursor.moveToFirst()
        } ?: false
        if (hasVideos) {
            count++
        }
    }
    return count
}