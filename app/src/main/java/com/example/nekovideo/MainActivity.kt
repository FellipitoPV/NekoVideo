package com.example.nekovideo

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.OnBackPressedCallback
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallTopAppBar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.nekovideo.components.RenameDialog
import com.example.nekovideo.components.VideoFolderScreen
import com.example.nekovideo.components.VideoListScreen
import com.example.nekovideo.components.VideoPlayerScreen
import com.example.nekovideo.components.getVideosAndSubfolders
import com.example.nekovideo.ui.theme.NekoVideoTheme
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import java.io.File
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Definir orientação padrão como retrato
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        enableEdgeToEdge()
        setContent {
            NekoVideoTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }
}

fun Context.findActivity(): ComponentActivity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is ComponentActivity) return context
        context = context.baseContext
    }
    return null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val navController = rememberNavController()
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route?.substringBefore("/{folderPath}")?.substringBefore("/{playlist}")
    val folderPath = currentBackStackEntry?.arguments?.getString("folderPath")?.let { Uri.decode(it) } ?: ""
    val selectedItems = remember { mutableStateListOf<String>() }
    var showFabMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameTrigger by remember { mutableStateOf(0) }
    val coroutineScope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(currentRoute, folderPath, selectedItems) {
        val activity = context.findActivity()
        if (activity != null) {
            println("Back callback registered for route: $currentRoute")
            activity.onBackPressedDispatcher.addCallback(object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (selectedItems.isNotEmpty()) {
                        selectedItems.clear()
                        showFabMenu = false
                        showRenameDialog = false
                        println("Selection cleared")
                    } else if (currentRoute == "video_player") {
                        println("Navigating back from video player")
                        navController.popBackStack()
                    } else if (currentRoute == "video_list") {
                        val rootPath = android.os.Environment.getExternalStorageDirectory().absolutePath
                        val relativePath = folderPath.removePrefix(rootPath).trim('/')
                        val pathSegments = relativePath.split('/').filter { it.isNotEmpty() }
                        if (pathSegments.size > 1) {
                            val parentPath = "$rootPath/${pathSegments.dropLast(1).joinToString("/")}"
                            val encodedParentPath = Uri.encode(parentPath)
                            println("System back to parent: video_list/$encodedParentPath")
                            navController.navigate("video_list/$encodedParentPath") {
                                popUpTo("video_list/{folderPath}") { inclusive = false }
                                launchSingleTop = true
                            }
                        } else {
                            println("System back to video_folders")
                            navController.navigate("video_folders") {
                                popUpTo("video_folders") { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    } else {
                        println("Finishing activity")
                        activity.finish()
                    }
                }
            })
        } else {
            println("Failed to find ComponentActivity")
        }
    }

    if (showRenameDialog) {
        RenameDialog(
            selectedItems = selectedItems.toList(),
            onDismiss = { showRenameDialog = false },
            onComplete = {
                selectedItems.clear()
                showFabMenu = false
                showRenameDialog = false
                renameTrigger++
            }
        )
    }

    Scaffold(
        topBar = {
            if (currentRoute != "video_player") {
                SmallTopAppBar(
                    title = {
                        if (selectedItems.isNotEmpty()) {
                            Text(
                                text = "${selectedItems.size} item(s) selected",
                                style = MaterialTheme.typography.titleSmall
                            )
                        } else if (currentRoute == "video_folders") {
                            Text(
                                text = "NekoVideo",
                                style = MaterialTheme.typography.titleSmall
                            )
                        } else {
                            val rootPath = android.os.Environment.getExternalStorageDirectory().absolutePath
                            val relativePath = folderPath.removePrefix(rootPath).trim('/')
                            val pathSegments = relativePath.split('/').filter { it.isNotEmpty() }
                            Row {
                                pathSegments.forEachIndexed { index, segment ->
                                    Text(
                                        text = segment,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        textDecoration = TextDecoration.Underline,
                                        modifier = Modifier
                                            .clickable {
                                                val targetPath = "$rootPath/${pathSegments.take(index + 1).joinToString("/")}"
                                                val encodedPath = Uri.encode(targetPath)
                                                println("Navigating to: video_list/$encodedPath, targetPath: $targetPath")
                                                try {
                                                    navController.navigate("video_list/$encodedPath") {
                                                        popUpTo("video_folders") { inclusive = false }
                                                        launchSingleTop = true
                                                    }
                                                } catch (e: Exception) {
                                                    println("Navigation error: ${e.message}")
                                                }
                                            }
                                            .padding(end = 4.dp)
                                    )
                                    if (index < pathSegments.size - 1) {
                                        Text(
                                            text = "/",
                                            style = MaterialTheme.typography.titleSmall,
                                            modifier = Modifier.padding(end = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                    },
                    navigationIcon = {
                        if (selectedItems.isNotEmpty()) {
                            IconButton(onClick = {
                                println("Cancel selection clicked")
                                selectedItems.clear()
                                showFabMenu = false
                                showRenameDialog = false
                            }) {
                                Icon(
                                    imageVector = Icons.Default.Cancel,
                                    contentDescription = "Cancel Selection"
                                )
                            }
                        } else if (currentRoute == "video_list") {
                            IconButton(onClick = {
                                println("Back button clicked")
                                if (selectedItems.isNotEmpty()) {
                                    selectedItems.clear()
                                    showFabMenu = false
                                    showRenameDialog = false
                                    println("Selection cleared")
                                } else {
                                    navController.popBackStack()
                                }
                            }) {
                                Icon(
                                    imageVector = Icons.Default.ArrowBack,
                                    contentDescription = "Back"
                                )
                            }
                        }
                    },
                    actions = {
                        if (selectedItems.isNotEmpty() && currentRoute == "video_list") {
                            IconButton(onClick = {
                                println("Select all clicked")
                                val allItems = getVideosAndSubfolders(context, folderPath)
                                selectedItems.clear()
                                selectedItems.addAll(allItems.map { it.path })
                            }) {
                                Icon(
                                    imageVector = Icons.Default.SelectAll,
                                    contentDescription = "Select All"
                                )
                            }
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            if (currentRoute != "video_player") {
                Box {
                    FloatingActionButton(
                        onClick = {
                            if (selectedItems.isNotEmpty()) {
                                showFabMenu = !showFabMenu
                                println("FAB menu toggled: $showFabMenu")
                            } else {
                                when (currentRoute) {
                                    "video_folders" -> {
                                        println("Settings clicked")
                                    }
                                    "video_list" -> {
                                        println("Shuffle play clicked")
                                        coroutineScope.launch {
                                            val videos = withContext(Dispatchers.IO) {
                                                getVideosAndSubfolders(context, folderPath, recursive = true)
                                                    .filter { !it.isFolder }
                                                    .map { "file://${it.path}" }
                                                    .shuffled()
                                            }
                                            if (videos.isNotEmpty()) {
                                                val encodedPlaylist = Uri.encode(videos.joinToString(","))
                                                navController.navigate("video_player/$encodedPlaylist")
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Icon(
                            imageVector = if (selectedItems.isNotEmpty()) Icons.Default.FolderOpen else when (currentRoute) {
                                "video_folders" -> Icons.Default.Settings
                                else -> Icons.Default.Shuffle
                            },
                            contentDescription = if (selectedItems.isNotEmpty()) "Folder Actions" else when (currentRoute) {
                                "video_folders" -> "Settings"
                                else -> "Shuffle Play"
                            }
                        )
                    }
                    if (showFabMenu && selectedItems.isNotEmpty()) {
                        FloatingActionButton(
                            onClick = {
                                println("Rename action clicked")
                                showRenameDialog = true
                                showFabMenu = false
                            },
                            modifier = Modifier
                                .offset(y = (-56).dp)
                                .size(40.dp)
                                .clip(CircleShape),
                            containerColor = MaterialTheme.colorScheme.secondary
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Rename",
                                tint = MaterialTheme.colorScheme.onSecondary
                            )
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = if (currentRoute == "video_player") 0.dp else max(0.dp, paddingValues.calculateTopPadding() - 16.dp),
                    bottom = paddingValues.calculateBottomPadding(),
                    start = paddingValues.calculateStartPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                    end = paddingValues.calculateEndPadding(androidx.compose.ui.unit.LayoutDirection.Ltr)
                )
        ) {
            NavHost(
                navController = navController,
                startDestination = "video_folders",
                enterTransition = {
                    slideInHorizontally(
                        initialOffsetX = { fullWidth -> fullWidth },
                        animationSpec = tween(durationMillis = 300)
                    )
                },
                exitTransition = {
                    slideOutHorizontally(
                        targetOffsetX = { fullWidth -> -fullWidth },
                        animationSpec = tween(durationMillis = 300)
                    )
                },
                popEnterTransition = {
                    slideInHorizontally(
                        initialOffsetX = { fullWidth -> -fullWidth },
                        animationSpec = tween(durationMillis = 300)
                    )
                },
                popExitTransition = {
                    slideOutHorizontally(
                        targetOffsetX = { fullWidth -> fullWidth },
                        animationSpec = tween(durationMillis = 300)
                    )
                }
            ) {
                composable("video_folders") {
                    VideoFolderScreen(
                        onFolderClick = { folderPath ->
                            val encodedPath = Uri.encode(folderPath)
                            println("Navigating to folder: video_list/$encodedPath")
                            navController.navigate("video_list/$encodedPath")
                        },
                        selectedItems = selectedItems,
                        onSelectionChange = { newSelection ->
                            selectedItems.clear()
                            selectedItems.addAll(newSelection)
                            showFabMenu = false
                            showRenameDialog = false
                        }
                    )
                }
                composable("video_list/{folderPath}") { backStackEntry ->
                    val folderPath = backStackEntry.arguments?.getString("folderPath")?.let { Uri.decode(it) } ?: ""
                    println("Current folder: $folderPath")
                    VideoListScreen(
                        folderPath = folderPath,
                        onFolderClick = { itemPath ->
                            val item = getVideosAndSubfolders(context, folderPath).find { it.path == itemPath }
                            if (item?.isFolder == true) {
                                val encodedSubPath = Uri.encode(itemPath)
                                println("Navigating to subfolder: video_list/$encodedSubPath")
                                navController.navigate("video_list/$encodedSubPath")
                            } else {
                                val videoUri = "file://$itemPath"
                                val encodedPlaylist = Uri.encode(videoUri)
                                println("Navigating to video player: video_player/$encodedPlaylist")
                                navController.navigate("video_player/$encodedPlaylist")
                            }
                        },
                        selectedItems = selectedItems,
                        onSelectionChange = { newSelection ->
                            selectedItems.clear()
                            selectedItems.addAll(newSelection)
                            showFabMenu = false
                            showRenameDialog = false
                        },
                        renameTrigger = renameTrigger
                    )
                }
                composable("video_player/{playlist}") { backStackEntry ->
                    val playlist = backStackEntry.arguments?.getString("playlist")?.let { Uri.decode(it) }
                        ?.split(",") ?: emptyList()
                    VideoPlayerScreen(playlist = playlist)
                }
            }
        }
    }
}