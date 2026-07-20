package com.nkls.nekovideo.components.pages.mainscreen

import android.content.Intent
import android.content.Context
import android.os.Build
import android.util.Log
import android.widget.Toast
import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.nkls.nekovideo.MainActivity
import com.nkls.nekovideo.MediaPlaybackService
import com.nkls.nekovideo.R
import com.nkls.nekovideo.components.CreateFolderDialog
import com.nkls.nekovideo.components.DeleteConfirmationDialog
import com.nkls.nekovideo.components.EnableBiometricDialog
import com.nkls.nekovideo.components.PasswordDialog
import com.nkls.nekovideo.components.ProcessingDialog
import com.nkls.nekovideo.components.ShuffleTagsDialog
import com.nkls.nekovideo.components.ShuffleTagFilter
import com.nkls.nekovideo.components.VideoTagsDialog
import com.nkls.nekovideo.components.helpers.BiometricHelper
import com.nkls.nekovideo.components.LockedRenameDialog
import com.nkls.nekovideo.components.RenameDialog
import com.nkls.nekovideo.components.SortType
import com.nkls.nekovideo.components.FolderScreen
import com.nkls.nekovideo.components.InlineStatusMessage
import com.nkls.nekovideo.components.helpers.FilesManager
import com.nkls.nekovideo.components.helpers.FolderLockManager
import com.nkls.nekovideo.components.helpers.LockedFolderOperations
import com.nkls.nekovideo.components.helpers.LockedPlaybackSession
import com.nkls.nekovideo.components.helpers.PlaylistManager
import com.nkls.nekovideo.components.helpers.SortRowMessageCenter
import com.nkls.nekovideo.components.helpers.TagEntity
import com.nkls.nekovideo.components.helpers.TagScope
import com.nkls.nekovideo.components.helpers.VideoProgressStore
import com.nkls.nekovideo.components.helpers.VideoTagStore
import com.nkls.nekovideo.components.helpers.rememberFolderNavigationState
import com.nkls.nekovideo.components.layout.ActionFAB
import com.nkls.nekovideo.components.layout.ActionType
import com.nkls.nekovideo.components.layout.TopBar
import com.nkls.nekovideo.components.loadFolderContent
import com.nkls.nekovideo.components.player.MiniPlayerImproved
import com.nkls.nekovideo.components.player.PlaybackThumbnailCoordinator
import com.nkls.nekovideo.components.player.VideoPlayerOverlay
import com.nkls.nekovideo.components.settings.AboutSettingsScreen
import com.nkls.nekovideo.components.settings.StorageSettingsScreen
import com.nkls.nekovideo.components.settings.TagsSettingsScreen
import com.nkls.nekovideo.components.settings.DisplaySettingsScreen
import com.nkls.nekovideo.components.settings.InterfaceSettingsScreen
import com.nkls.nekovideo.components.settings.PlaybackSettingsScreen
import com.nkls.nekovideo.components.settings.SecuritySettingsScreen
import com.nkls.nekovideo.components.settings.SettingsScreen
import com.nkls.nekovideo.findActivity
import com.nkls.nekovideo.services.FolderVideoScanner
import com.nkls.nekovideo.components.helpers.DLNACastManager
import com.nkls.nekovideo.components.helpers.FolderNavigationState
import com.nkls.nekovideo.theme.ThemeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun MainScreen(
    intent: Intent?,
    themeManager: ThemeManager,
    notificationReceived: Boolean = false,
    lastAction: String? = null,
    lastTime: Long = 0,
    externalVideoReceived: Boolean = false,
    autoOpenOverlay: Boolean = false,
    openFolderPath: String? = null,
    onFolderPathConsumed: () -> Unit = {}
) {
    PlaybackThumbnailCoordinator()

    val navController = rememberNavController()
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route?.substringBefore("/{folderPath}")?.substringBefore("/{playlist}")

    // Estado de navegação de pastas (gerencia pilha internamente)
    val folderNavState = rememberFolderNavigationState()
    val folderPath = folderNavState.currentPath

    // Abre a pasta dos cortes ao clicar na notificação de conclusão
    LaunchedEffect(openFolderPath) {
        if (openFolderPath != null) {
            folderNavState.navigateToPath(openFolderPath)
            onFolderPathConsumed()  // reseta para null → próxima notificação volta a disparar
        }
    }
    val isAtRootLevel = folderNavState.isAtRoot
    val selectedItems = remember { mutableStateListOf<String>() }
    var showFabMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var renameTrigger by remember { mutableStateOf(0) }
    val coroutineScope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    var showPlayerOverlay by remember { mutableStateOf(false) }
    var deletedVideoPath by remember { mutableStateOf<String?>(null) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    // Back press centralizado no BackHandler abaixo

    var isInPiPMode by remember { mutableStateOf(false) }

    var showPrivateFolders by remember {
        mutableStateOf(FilesManager.SecureFoldersVisibility.areSecureFoldersVisible(context))
    }

    var isMoveMode by remember { mutableStateOf(false) }
    var itemsToMove by remember { mutableStateOf<List<String>>(emptyList()) }
    var moveSourceLockedFolder by remember { mutableStateOf<String?>(null) }
    var showFolderActions by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var isLocking by remember { mutableStateOf(false) }
    var isUnlocking by remember { mutableStateOf(false) }
    var isMoving by remember { mutableStateOf(false) }
    var isDeleting by remember { mutableStateOf(false) }
    var isShuffling by remember { mutableStateOf(false) }
    var moveProgress by remember { mutableStateOf("") }
    var deleteProgress by remember { mutableStateOf("") }
    var lockProgress by remember { mutableStateOf("") }
    // Senha da sessao atual (armazenada apos triple-tap)
    var sessionPassword by remember { mutableStateOf<String?>(null) }
    var showLockPasswordDialog by remember { mutableStateOf(false) }
    var pendingLockAction by remember { mutableStateOf<List<String>?>(null) }
    var pendingActionIsUnlock by remember { mutableStateOf(false) }
    var pendingSecureItems by remember { mutableStateOf<List<String>?>(null) }
    var showSecurePasswordDialog by remember { mutableStateOf(false) }
    var showBiometricOfferDialog by remember { mutableStateOf(false) }
    var biometricOfferPassword by remember { mutableStateOf("") }

    var showVideoTagsDialog by remember { mutableStateOf(false) }
    var showShuffleTagsDialog by remember { mutableStateOf(false) }
    var availableTags by remember { mutableStateOf<List<TagEntity>>(emptyList()) }
    var commonSelectedTagIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var cachedNormalTags by remember { mutableStateOf<List<TagEntity>?>(null) }
    var cachedPrivateTags by remember { mutableStateOf<List<TagEntity>?>(null) }
    var showShuffleLongPressHint by remember { mutableStateOf(false) }

    fun invalidateTagCaches() {
        cachedNormalTags = null
        cachedPrivateTags = null
    }

    val tagChangeEvent by VideoTagStore.tagChangeEvent.collectAsState()
    LaunchedEffect(tagChangeEvent) {
        invalidateTagCaches()
    }

    suspend fun getCachedTags(scope: TagScope): List<TagEntity> {
        val cachedTags = when (scope) {
            TagScope.NORMAL -> cachedNormalTags
            TagScope.PRIVATE -> cachedPrivateTags
        }
        if (cachedTags != null) return cachedTags

        val loadedTags = withContext(Dispatchers.IO) {
            VideoTagStore.getAllTags(context, scope)
        }
        when (scope) {
            TagScope.NORMAL -> cachedNormalTags = loadedTags
            TagScope.PRIVATE -> cachedPrivateTags = loadedTags
        }
        return loadedTags
    }

    fun isSecureFolder(folderPath: String): Boolean {
        val secureFolderPath = FilesManager.SecureStorage.getSecureFolderPath(context)
        val nekoPrivatePath = FilesManager.SecureStorage.getNekoPrivateFolderPath()
        return folderPath.startsWith(secureFolderPath) ||
                folderPath.startsWith(nekoPrivatePath) ||
                folderPath.contains("/.private/") ||
                folderPath.contains("/secure/") ||
                folderPath.contains(".secure_videos") ||
                folderPath.endsWith(".secure_videos") ||
                File(folderPath, ".secure").exists() ||
                File(folderPath, ".nomedia").exists()
    }

    fun isPrivateTagContext(folderPath: String): Boolean {
        val secureFolderPath = FilesManager.SecureStorage.getSecureFolderPath(context)
        val nekoPrivatePath = FilesManager.SecureStorage.getNekoPrivateFolderPath()
        return folderPath.startsWith(secureFolderPath) ||
                folderPath.startsWith(nekoPrivatePath) ||
                folderPath.contains("/.private/") ||
                folderPath.contains("/secure/") ||
                folderPath.contains(".secure_videos") ||
                folderPath.endsWith(".secure_videos") ||
                File(folderPath, ".secure").exists() ||
                FolderLockManager.isLocked(folderPath)
    }

    fun currentTagScope(): TagScope {
        return if (isPrivateTagContext(folderPath)) {
            TagScope.PRIVATE
        } else {
            TagScope.NORMAL
        }
    }

    suspend fun refreshShuffleLongPressHintEligibility() {
        val prefs = context.getSharedPreferences("nekovideo_settings", Context.MODE_PRIVATE)
        val hintAlreadyShown = prefs.getBoolean("shuffle_tags_hint_shown", false)

        if (!hintAlreadyShown) {
            val hasAnyTags = getCachedTags(TagScope.NORMAL).isNotEmpty() ||
                getCachedTags(TagScope.PRIVATE).isNotEmpty()
            showShuffleLongPressHint = hasAnyTags
        } else {
            showShuffleLongPressHint = false
        }
    }

    LaunchedEffect(Unit) {
        refreshShuffleLongPressHintEligibility()
    }

    fun openVideoFromFolder(
        targetFolderPath: String,
        itemPath: String,
        sortType: SortType = SortType.NAME_ASC,
        resumePositionMs: Long = 0L
    ) {
        val effectiveResumePositionMs = if (resumePositionMs > 0L) {
            resumePositionMs
        } else {
            VideoProgressStore.get(context, itemPath)?.positionMs ?: 0L
        }

        val targetIsSecure = isSecureFolder(targetFolderPath)
        val targetIsRootLevel = targetFolderPath == FolderNavigationState.ROOT_PATH
        val items = loadFolderContent(
            context = context,
            folderPath = targetFolderPath,
            sortType = sortType,
            isSecureMode = targetIsSecure,
            isRootLevel = targetIsRootLevel,
            showPrivateFolders = showPrivateFolders
        )
        val videoItems = items.filter { !it.isFolder }

        if (videoItems.isEmpty()) {
            SortRowMessageCenter.showError("Video nao encontrado")
            return
        }

        val castManager = DLNACastManager.getInstance(context)
        if (castManager.isConnected) {
            if (LockedPlaybackSession.isActive && LockedPlaybackSession.hasSessionForFolder(targetFolderPath)) {
                val videos = videoItems.map { "locked://${it.path}" }
                val titles = videoItems.map { it.displayName }
                val clickedVideoIndex = videos.indexOf("locked://$itemPath")
                if (clickedVideoIndex >= 0) {
                    castManager.castPlaylist(videos, titles, clickedVideoIndex)
                    if (effectiveResumePositionMs > 0L) {
                        coroutineScope.launch {
                            delay(1500)
                            castManager.seekTo(effectiveResumePositionMs)
                        }
                    }
                }
            } else {
                val videos = videoItems.map { "file://${it.path}" }
                val titles = videoItems.map { it.displayName }
                val clickedVideoIndex = videos.indexOf("file://$itemPath")
                if (clickedVideoIndex >= 0) {
                    castManager.castPlaylist(videos, titles, clickedVideoIndex)
                    if (effectiveResumePositionMs > 0L) {
                        coroutineScope.launch {
                            delay(1500)
                            castManager.seekTo(effectiveResumePositionMs)
                        }
                    }
                } else {
                    castManager.castVideo("file://$itemPath", File(itemPath).nameWithoutExtension)
                }
            }
            return
        }

        if (LockedPlaybackSession.isActive && LockedPlaybackSession.hasSessionForFolder(targetFolderPath)) {
            val videos = videoItems.map { "locked://${it.path}" }
            val clickedVideoIndex = videos.indexOf("locked://$itemPath")
            if (clickedVideoIndex >= 0) {
                PlaylistManager.setPlaylist(videos, startIndex = clickedVideoIndex, shuffle = false)
                MediaPlaybackService.startWithPlaylist(context, videos, clickedVideoIndex, effectiveResumePositionMs)
                showPlayerOverlay = true
            }
        } else {
            val videos = videoItems.map { "file://${it.path}" }
            val clickedVideoIndex = videos.indexOf("file://$itemPath")
            if (clickedVideoIndex >= 0) {
                PlaylistManager.setPlaylist(videos, startIndex = clickedVideoIndex, shuffle = false)
                MediaPlaybackService.startWithPlaylist(context, videos, clickedVideoIndex, effectiveResumePositionMs)
                showPlayerOverlay = true
            } else {
                val videoUri = "file://$itemPath"
                PlaylistManager.setPlaylist(listOf(videoUri), startIndex = 0, shuffle = false)
                MediaPlaybackService.startWithPlaylist(context, listOf(videoUri), 0, effectiveResumePositionMs)
                showPlayerOverlay = true
            }
        }
    }

    suspend fun playShuffledVideos(tagFilter: ShuffleTagFilter? = null) {
        val tagScope = currentTagScope()
        val videos = FilesManager.getVideosRecursive(
            context = context,
            folderPath = folderPath,
            isSecureMode = isSecureFolder(folderPath),
            showPrivateFolders = showPrivateFolders,
            selectedItems = selectedItems.toList(),
            sessionPassword = sessionPassword
        )

        val filteredVideos = if (tagFilter == null) {
            videos
        } else {
            val includePaths = withContext(Dispatchers.IO) {
                VideoTagStore.getVideoPathsForAllTagIds(context, tagFilter.includeTagIds, tagScope)
            }
            val excludePaths = withContext(Dispatchers.IO) {
                VideoTagStore.getVideoPathsForAnyTagIds(context, tagFilter.excludeTagIds, tagScope)
            }
            videos.filter { path ->
                val normalizedPath = path.removePrefix("locked://").removePrefix("file://")
                val matchesInclude = tagFilter.includeTagIds.isEmpty() || normalizedPath in includePaths
                val matchesExclude = normalizedPath in excludePaths
                matchesInclude && !matchesExclude
            }
        }

        if (filteredVideos.isNotEmpty()) {
            val lockedVideos = filteredVideos.filter { it.startsWith("locked://") }
            if (lockedVideos.isNotEmpty()) {
                val pwd = sessionPassword
                if (pwd != null) {
                    val lockedFolderPaths = lockedVideos
                        .map { it.removePrefix("locked://") }
                        .mapNotNull { File(it).parent }
                        .distinct()

                    for (lockedPath in lockedFolderPaths) {
                        if (!LockedPlaybackSession.hasSessionForFolder(lockedPath)) {
                            val salt = FolderLockManager.getSalt(lockedPath)
                            val manifest = FolderLockManager.readManifest(lockedPath, pwd)
                            if (salt != null && manifest != null) {
                                val xorKey = FolderLockManager.deriveXorKey(pwd, salt)
                                LockedPlaybackSession.start(xorKey, manifest, lockedPath, pwd)
                            }
                        }
                    }
                }
            }

            val castManager = DLNACastManager.getInstance(context)
            if (castManager.isConnected) {
                val shuffled = filteredVideos.shuffled()
                val titles = shuffled.map { path ->
                    if (path.startsWith("locked://")) {
                        val obfuscatedName = File(path.removePrefix("locked://")).name
                        LockedPlaybackSession.getOriginalName(obfuscatedName)
                            ?.substringBeforeLast(".") ?: obfuscatedName
                    } else {
                        File(path.removePrefix("file://")).nameWithoutExtension
                    }
                }
                castManager.castPlaylist(shuffled, titles, 0)
            } else {
                PlaylistManager.setPlaylist(filteredVideos, startIndex = 0, shuffle = true)
                MediaPlaybackService.startWithPlaylist(context, PlaylistManager.getFullPlaylist(), 0)
                showPlayerOverlay = true
            }
            selectedItems.clear()
        } else {
            SortRowMessageCenter.showInfo(
                context.getString(if (tagFilter == null) R.string.no_videos_found else R.string.shuffle_tags_no_match)
            )
        }
    }

    fun launchShufflePlayback(tagFilter: ShuffleTagFilter? = null) {
        coroutineScope.launch {
            isShuffling = true
            try {
                playShuffledVideos(tagFilter)
            } finally {
                isShuffling = false
            }
        }
    }

    fun togglePrivateFolders() {
        val newState = FilesManager.SecureFoldersVisibility.toggleSecureFoldersVisibility(context)
        showPrivateFolders = newState
        renameTrigger++
    }


    fun quickRefresh() {
        FolderVideoScanner.startScan(context, forceRefresh = true)
    }

    fun refreshAffectedPaths(paths: List<String>) {
        val affectedPaths = paths
            .map { File(it).absolutePath }
            .filter { it.isNotBlank() }
            .distinct()

        if (affectedPaths.isEmpty()) {
            quickRefresh()
            return
        }

        FolderVideoScanner.refreshPaths(context, affectedPaths)
    }

    fun launchFolderLockAction(folders: List<String>, isUnlock: Boolean, password: String) {
        coroutineScope.launch {
            if (isUnlock) {
                LockedFolderOperations.unlockFolders(
                    context = context,
                    folderPaths = folders,
                    password = password,
                    onStateChange = { isUnlocking = it },
                    onProgress = { current, total -> lockProgress = "$current/$total" },
                    onError = { message -> SortRowMessageCenter.showError("Erro: $message") },
                    onFolderUnlocked = { SortRowMessageCenter.showSuccess(context.getString(R.string.folder_unlocked_success)) }
                )
            } else {
                LockedFolderOperations.lockFolders(
                    context = context,
                    folderPaths = folders,
                    password = password,
                    onStateChange = { isLocking = it },
                    onProgress = { current, total -> lockProgress = "$current/$total" },
                    onError = { message -> SortRowMessageCenter.showError("Erro: $message") },
                    onFolderLocked = { SortRowMessageCenter.showSuccess(context.getString(R.string.folder_locked_success)) }
                )
            }

            lockProgress = ""
            selectedItems.clear()
            renameTrigger++
            refreshAffectedPaths(if (isAtRootLevel) listOf(folderPath) else folders)
        }
    }

    fun launchSecureItems(itemsToSecure: List<String>, password: String) {
        coroutineScope.launch {
            val securePath = FilesManager.SecureStorage.getNekoPrivateFolderPath()
            if (!FilesManager.SecureStorage.ensureNekoPrivateFolderExists()) return@launch

            LockedFolderOperations.secureItems(
                context = context,
                itemsToSecure = itemsToSecure,
                securePath = securePath,
                password = password,
                onMoveStateChange = {
                    isMoving = it
                    if (!it) moveProgress = ""
                },
                onMoveProgress = { current, total -> moveProgress = "$current/$total" },
                onLockStateChange = {
                    isLocking = it
                    if (!it) lockProgress = ""
                },
                onLockProgress = { current, total -> lockProgress = "$current/$total" },
                onError = { message -> SortRowMessageCenter.showError("Erro: $message") },
                onSuccess = {
                    SortRowMessageCenter.showSuccess(context.getString(R.string.files_secured))
                    selectedItems.clear()
                    renameTrigger++
                    refreshAffectedPaths(itemsToSecure.mapNotNull { File(it).parent } + securePath)
                }
            )
        }
    }

    fun closePlayerOverlay() {
        showPlayerOverlay = false
        isInPiPMode = false
    }

    val currentTheme by themeManager.themeMode.collectAsState()
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current

    // ✅ DETECTOR DE MODO PIP
    LaunchedEffect(Unit) {
        val activity = context.findActivity() as? MainActivity
        if (activity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Observar mudanças de configuração
            while (true) {
                isInPiPMode = activity.isInPiPMode
                delay(200)
            }
        }
    }

    // ✅ Ao clicar na notificação, NÃO abre o overlay automaticamente
    // O usuário pode usar o mini player e abrir o overlay se desejar
    // (Código removido para evitar problema de navegação com back press)

    LaunchedEffect(currentTheme, configuration.uiMode, showPlayerOverlay) {
        if (!showPlayerOverlay) {
            val activity = context.findActivity() as? ComponentActivity
            if (activity != null) {
                val isDarkTheme = when (currentTheme) {
                    "light" -> false
                    "dark" -> true
                    "system" -> {
                        val nightModeFlags = configuration.uiMode and
                                android.content.res.Configuration.UI_MODE_NIGHT_MASK
                        nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES
                    }
                    else -> {
                        val nightModeFlags = configuration.uiMode and
                                android.content.res.Configuration.UI_MODE_NIGHT_MASK
                        nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES
                    }
                }

                activity.enableEdgeToEdge(
                    statusBarStyle = if (isDarkTheme) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT
                        )
                    },
                    navigationBarStyle = if (isDarkTheme) {
                        SystemBarStyle.dark(android.graphics.Color.BLACK)
                    } else {
                        SystemBarStyle.light(
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT
                        )
                    }
                )
            }
        }
    }

    // BackHandlers movidos para DEPOIS do NavHost para ter prioridade (ordem LIFO)

    LaunchedEffect(showPlayerOverlay) {
        val activity = context.findActivity()
        if (activity is MainActivity) {
            activity.keepScreenOn(showPlayerOverlay)
        }
    }

    LaunchedEffect(externalVideoReceived) {
        if (externalVideoReceived) {
            // Aguardar serviço de mídia inicializar
            delay(800)

            showPlayerOverlay = true

            val activity = context.findActivity() as? MainActivity
            activity?.resetExternalVideoFlag()
        }
    }

    LaunchedEffect(autoOpenOverlay) {
        if (autoOpenOverlay) {
            delay(100) // Pequeno delay para estabilizar
            showPlayerOverlay = true
        }
    }

    if (showRenameDialog) {
        val isInsideLockedForRename = FolderLockManager.isLocked(folderPath) &&
                LockedPlaybackSession.isActive &&
                LockedPlaybackSession.hasSessionForFolder(folderPath)

        if (isInsideLockedForRename && selectedItems.size == 1) {
            // Rename inside locked folder: keep the obfuscated on-disk name and only
            // update the user-facing name stored in the manifest.
            val obfuscatedPath = selectedItems.first()
            val isFolderRename = File(obfuscatedPath).isDirectory
            val obfuscatedName = File(obfuscatedPath).name
            val currentManifest = LockedPlaybackSession.getManifestForFolder(folderPath)
            val currentOriginalName = if (isFolderRename) {
                currentManifest?.subfolders
                    ?.find { it.obfuscatedName == obfuscatedName }
                    ?.originalName
                    ?: obfuscatedName
            } else {
                LockedPlaybackSession.getOriginalName(obfuscatedName) ?: obfuscatedName
            }

            LockedRenameDialog(
                currentName = currentOriginalName,
                onDismiss = { showRenameDialog = false },
                onRename = { newName ->
                    coroutineScope.launch {
                        val pwd = sessionPassword ?: LockedPlaybackSession.sessionPassword
                        if (pwd != null) {
                            withContext(Dispatchers.IO) {
                                val updatedManifest = if (isFolderRename) {
                                    FolderLockManager.renameSubfolderInManifest(
                                        context = context,
                                        parentFolderPath = folderPath,
                                        obfuscatedName = obfuscatedName,
                                        newOriginalName = newName,
                                        password = pwd
                                    )
                                } else {
                                    FolderLockManager.renameFileInManifest(
                                        folderPath, obfuscatedName, newName, pwd
                                    )
                                }
                                if (updatedManifest != null) {
                                    LockedPlaybackSession.updateManifest(folderPath, updatedManifest)
                                }
                            }
                        }
                        showRenameDialog = false
                        selectedItems.clear()
                        renameTrigger++
                        refreshAffectedPaths(listOf(folderPath))
                    }
                }
            )
        } else {
            RenameDialog(
                selectedItems = selectedItems.toList(),
                onDismiss = { showRenameDialog = false },
                onComplete = {
                    selectedItems.clear()
                    renameTrigger++
                },
                onRefresh = {
                    refreshAffectedPaths(listOf(folderPath))
                }
            )
        }
    }

    if (showBiometricOfferDialog) {
        EnableBiometricDialog(
            password = biometricOfferPassword,
            onDismiss = { showBiometricOfferDialog = false },
            onEnabled = {
                showBiometricOfferDialog = false
                SortRowMessageCenter.showSuccess(context.getString(R.string.biometric_enabled_success))
            }
        )
    }

    if (showVideoTagsDialog) {
        VideoTagsDialog(
            selectedVideoCount = selectedItems.size,
            tags = availableTags,
            initialSelectedTagIds = commonSelectedTagIds,
            onDismiss = { showVideoTagsDialog = false },
            onManageTags = {
                invalidateTagCaches()
                navController.navigate("settings/tags")
            },
            onSave = { selectedTagIds ->
                val targetVideos = selectedItems.filter { File(it).isFile }
                if (targetVideos.isEmpty()) {
                    Result.failure(IllegalStateException(context.getString(R.string.no_videos_found)))
                } else {
                    withContext(Dispatchers.IO) {
                        VideoTagStore.syncCommonTagsForVideos(
                            context = context,
                            videoPaths = targetVideos,
                            initiallyCommonTagIds = commonSelectedTagIds,
                            selectedTagIds = selectedTagIds
                        )
                    }
                    renameTrigger++
                    selectedItems.clear()
                    SortRowMessageCenter.showSuccess(context.getString(R.string.video_tags_add_success))
                    Result.success(Unit)
                }
            }
        )
    }

    if (showShuffleTagsDialog) {
        ShuffleTagsDialog(
            tags = availableTags,
            onDismiss = { showShuffleTagsDialog = false },
            onConfirm = { filter ->
                showShuffleTagsDialog = false
                launchShufflePlayback(filter)
            }
        )
    }

    if (showPasswordDialog) {
        PasswordDialog(
            onDismiss = { showPasswordDialog = false },
            onFirstTimePasswordCreated = { pwd ->
                if (BiometricHelper.isBiometricAvailable(context)) {
                    biometricOfferPassword = pwd
                    showBiometricOfferDialog = true
                }
            },
            onPasswordVerified = { password ->
                sessionPassword = password
                val encodedFolderPath = currentBackStackEntry?.arguments?.getString("folderPath") ?: ""
                val isAtRoot = encodedFolderPath == "root" || isAtRootLevel

                if (showPrivateFolders) {
                    FilesManager.SecureFoldersVisibility.hideSecureFolders(context)
                    showPrivateFolders = false
                    sessionPassword = null
                    SortRowMessageCenter.showInfo(context.getString(R.string.secure_folders_hidden))
                } else {
                    FilesManager.SecureFoldersVisibility.showSecureFolders(context)
                    showPrivateFolders = true
                    SortRowMessageCenter.showInfo(context.getString(R.string.secure_folders_shown))
                }
                renameTrigger++
                showPasswordDialog = false
            }
        )
    }

    // Lock/Unlock password dialog (when sessionPassword is not available)
    if (showLockPasswordDialog && pendingLockAction != null) {
        PasswordDialog(
            onDismiss = {
                showLockPasswordDialog = false
                pendingLockAction = null
                pendingActionIsUnlock = false
            },
            onFirstTimePasswordCreated = { pwd ->
                if (BiometricHelper.isBiometricAvailable(context)) {
                    biometricOfferPassword = pwd
                    showBiometricOfferDialog = true
                }
            },
            onPasswordVerified = { password ->
                showLockPasswordDialog = false
                sessionPassword = password
                val folders = pendingLockAction ?: return@PasswordDialog
                val isUnlock = pendingActionIsUnlock
                pendingLockAction = null
                pendingActionIsUnlock = false

                launchFolderLockAction(folders, isUnlock, password)
            }
        )
    }

    // Secure password dialog (SECURE action when sessionPassword is not available)
    if (showSecurePasswordDialog && pendingSecureItems != null) {
        PasswordDialog(
            onDismiss = {
                showSecurePasswordDialog = false
                pendingSecureItems = null
            },
            onFirstTimePasswordCreated = { pwd ->
                if (BiometricHelper.isBiometricAvailable(context)) {
                    biometricOfferPassword = pwd
                    showBiometricOfferDialog = true
                }
            },
            onPasswordVerified = { password ->
                showSecurePasswordDialog = false
                sessionPassword = password
                val itemsToSecure = pendingSecureItems ?: return@PasswordDialog
                pendingSecureItems = null

                launchSecureItems(itemsToSecure, password)
            }
        )
    }

    // Lock progress dialog
    if (isLocking) {
        ProcessingDialog(
            title = context.getString(R.string.locking_folder),
            message = context.getString(R.string.locking_progress, lockProgress.substringBefore("/").toIntOrNull() ?: 0, lockProgress.substringAfter("/").toIntOrNull() ?: 0)
        )
    }

    // Unlock progress dialog
    if (isUnlocking) {
        ProcessingDialog(
            title = context.getString(R.string.unlocking_folder),
            message = context.getString(R.string.unlocking_progress, lockProgress.substringBefore("/").toIntOrNull() ?: 0, lockProgress.substringAfter("/").toIntOrNull() ?: 0)
        )
    }

    // Move progress dialog
    if (isMoving) {
        ProcessingDialog(
            title = context.getString(R.string.moving_files),
            message = context.getString(R.string.moving_progress, moveProgress.substringBefore("/").toIntOrNull() ?: 0, moveProgress.substringAfter("/").toIntOrNull() ?: 0)
        )
    }

    if (isDeleting) {
        ProcessingDialog(
            title = context.getString(R.string.deleting_items),
            message = context.getString(R.string.deleting_progress, deleteProgress.substringBefore("/").toIntOrNull() ?: 0, deleteProgress.substringAfter("/").toIntOrNull() ?: 0)
        )
    }

    if (isShuffling) {
        ProcessingDialog(
            title = context.getString(R.string.action_shuffle_play),
            message = context.getString(R.string.indexing_videos)
        )
    }

    if (showCreateFolderDialog) {
        val isInsideLockedForCreate = FolderLockManager.isLocked(folderPath) &&
                LockedPlaybackSession.isActive &&
                LockedPlaybackSession.hasSessionForFolder(folderPath)

        CreateFolderDialog(
            currentPath = folderPath,
            isInsideLockedFolder = isInsideLockedForCreate,
            onDismiss = { showCreateFolderDialog = false },
            onFolderCreated = { folderName ->
                if (isInsideLockedForCreate) {
                    // Create locked subfolder using session password
                    val pwd = sessionPassword ?: LockedPlaybackSession.sessionPassword
                    if (pwd != null) {
                        coroutineScope.launch {
                            val subfolderPath = File(folderPath, folderName).absolutePath
                            val success = withContext(Dispatchers.IO) {
                                FolderLockManager.createEmptyLockedFolder(context, subfolderPath, pwd)
                            }
                            if (success) {
                                withContext(Dispatchers.IO) {
                                    FolderLockManager.addSubfolderToLockedFolder(context, folderPath, subfolderPath, pwd)
                                }
                                SortRowMessageCenter.showSuccess(context.getString(R.string.folder_created))
                            }
                            renameTrigger++
                            refreshAffectedPaths(listOf(folderPath))
                        }
                    }
                } else {
                    renameTrigger++
                    SortRowMessageCenter.showSuccess(context.getString(R.string.folder_created))
                    refreshAffectedPaths(listOf(folderPath))
                }
            },
        )
    }


    if (showDeleteConfirmDialog) {
        DeleteConfirmationDialog(
            itemCount = selectedItems.size,
            onDismiss = { showDeleteConfirmDialog = false },
            onConfirm = {
                showDeleteConfirmDialog = false
                coroutineScope.launch {
                    val itemsToDelete = selectedItems.toList()
                    val totalItemsToDelete = itemsToDelete.size
                    if (totalItemsToDelete == 0) return@launch

                    val isInsideLocked = FolderLockManager.isLocked(folderPath) &&
                            LockedPlaybackSession.isActive &&
                            LockedPlaybackSession.hasSessionForFolder(folderPath)

                    isDeleting = true
                    deleteProgress = "0/$totalItemsToDelete"

                    if (isInsideLocked) {
                        // Delete files/subfolders from locked folder
                        val pwd = sessionPassword ?: LockedPlaybackSession.sessionPassword
                        if (pwd != null) {
                            try {
                                LockedFolderOperations.deleteLockedItems(
                                    context = context,
                                    selectedItems = itemsToDelete,
                                    parentLockedFolder = folderPath,
                                    password = pwd,
                                    onProgress = { current, total ->
                                        deleteProgress = "$current/$total"
                                    },
                                    onError = { message ->
                                        SortRowMessageCenter.showError(message)
                                    },
                                    onSuccess = {
                                        SortRowMessageCenter.showSuccess(context.getString(R.string.items_deleted_locked))
                                    }
                                )
                            } catch (e: Exception) {
                                SortRowMessageCenter.showError(e.message ?: context.getString(R.string.delete_items_error_generic))
                            } finally {
                                isDeleting = false
                                deleteProgress = ""
                            }
                        } else {
                            isDeleting = false
                            deleteProgress = ""
                        }
                        selectedItems.clear()
                        showFabMenu = false
                        renameTrigger++
                        refreshAffectedPaths(listOf(folderPath))
                    } else if (currentRoute == "secure_folder") {
                        FilesManager.deleteSecureSelectedItems(
                            context = context,
                            selectedItems = itemsToDelete,
                            secureFolderPath = FilesManager.SecureStorage.getSecureFolderPath(context),
                            onProgress = { current, total ->
                                deleteProgress = "$current/$total"
                            },
                            onError = { message ->
                                isDeleting = false
                                deleteProgress = ""
                                SortRowMessageCenter.showError(message)
                            },
                            onSuccess = { message ->
                                isDeleting = false
                                deleteProgress = ""
                                SortRowMessageCenter.showSuccess(message)
                                selectedItems.clear()
                                showFabMenu = false
                                renameTrigger++
                                refreshAffectedPaths(listOf(folderPath))
                            }
                        )
                    } else {
                        FilesManager.deleteSelectedItems(
                            context = context,
                            selectedItems = itemsToDelete,
                            onProgress = { current, total ->
                                deleteProgress = "$current/$total"
                            },
                            onError = { message ->
                                isDeleting = false
                                deleteProgress = ""
                                SortRowMessageCenter.showError(message)
                            },
                            onSuccess = { message ->
                                isDeleting = false
                                deleteProgress = ""
                                SortRowMessageCenter.showSuccess(message)
                                selectedItems.clear()
                                showFabMenu = false
                                renameTrigger++
                                refreshAffectedPaths(listOf(folderPath))
                            }
                        )
                    }
                }
            }
        )
    }

    Scaffold(
        topBar = {
            if (currentRoute != "video_player" && !showPlayerOverlay) {
                val inlineMessage by SortRowMessageCenter.message.collectAsState()
                Column {
                    TopBar(
                        currentRoute = currentRoute,
                        selectedItems = selectedItems.toList(),
                        folderPath = folderPath,
                        navController = navController,
                        onPasswordDialog = {
                            if (showPrivateFolders) {
                                togglePrivateFolders()
                                SortRowMessageCenter.showInfo(context.getString(R.string.secure_folders_hidden))
                            } else {
                                showPasswordDialog = true
                            }
                        },
                        onSelectionClear = {
                            selectedItems.clear()
                            showFabMenu = false
                            showRenameDialog = false
                        },
                        onSelectAll = {
                            if (currentRoute == "folder") {
                                val isSecure = isSecureFolder(folderPath)
                                val isRoot = isAtRootLevel

                                val allItems = loadFolderContent(
                                    context = context,
                                    folderPath = folderPath,
                                    sortType = SortType.NAME_ASC,
                                    isSecureMode = isSecure,
                                    isRootLevel = isRoot,
                                    showPrivateFolders = showPrivateFolders
                                )
                                selectedItems.clear()
                                selectedItems.addAll(allItems.map { it.path })
                            }
                        },
                        isAtRootLevel = isAtRootLevel,
                        onNavigateToPath = { path ->
                            selectedItems.clear()
                            folderNavState.navigateToPath(path)
                        },
                        onNavigateBack = {
                            selectedItems.clear()
                            folderNavState.navigateBack()
                        }
                    )
                    InlineStatusMessage(
                        message = inlineMessage,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        },
        floatingActionButton = {
            if (currentRoute != "video_player" && currentRoute?.startsWith("settings") != true && !showPlayerOverlay) {
                Box(modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal))) {
                ActionFAB(
                    hasSelectedItems = selectedItems.isNotEmpty(),
                    isMoveMode = isMoveMode,
                    isSecureMode = isSecureFolder(folderPath),
                    isRootDirectory = isAtRootLevel,
                    selectedItems = selectedItems.toList(),
                    itemsToMoveCount = itemsToMove.size,
                    isInsideLockedFolder = FolderLockManager.isLocked(folderPath) && LockedPlaybackSession.isActive && LockedPlaybackSession.hasSessionForFolder(folderPath),
                    showShuffleLongPressHint = showShuffleLongPressHint,
                    onFabOpened = {
                        coroutineScope.launch {
                            refreshShuffleLongPressHintEligibility()
                        }
                    },
                    onShuffleLongPressHintShown = {
                        context.getSharedPreferences("nekovideo_settings", Context.MODE_PRIVATE)
                            .edit()
                            .putBoolean("shuffle_tags_hint_shown", true)
                            .apply()
                        showShuffleLongPressHint = false
                    },
                    onActionClick = { action ->
                        when (action) {
                            ActionType.SETTINGS -> {
                                navController.navigate("settings")
                            }
                            ActionType.UNLOCK -> { /* Removed - use MOVE instead */ }
                            ActionType.SECURE -> {
                                val itemsToSecure = selectedItems.toList()
                                if (itemsToSecure.isNotEmpty()) {
                                val pwd = sessionPassword
                                if (pwd == null) {
                                    pendingSecureItems = itemsToSecure
                                    showSecurePasswordDialog = true
                                } else {
                                    launchSecureItems(itemsToSecure, pwd)
                                }
                                } // end if (itemsToSecure.isNotEmpty())
                            }
                            ActionType.PRIVATIZE -> {
                                val foldersToLock = selectedItems.filter { path ->
                                    val file = File(path)
                                    file.isDirectory && !FolderLockManager.isLocked(path)
                                }
                                if (foldersToLock.isNotEmpty()) {
                                    val pwd = sessionPassword
                                    if (pwd != null) {
                                        launchFolderLockAction(foldersToLock, false, pwd)
                                    } else {
                                        pendingLockAction = foldersToLock
                                        pendingActionIsUnlock = false
                                        showLockPasswordDialog = true
                                    }
                                }
                            }
                            ActionType.UNPRIVATIZE -> {
                                val foldersToUnlock = selectedItems.filter { path ->
                                    FolderLockManager.isLocked(path)
                                }
                                if (foldersToUnlock.isNotEmpty()) {
                                    val pwd = sessionPassword
                                    if (pwd != null) {
                                        launchFolderLockAction(foldersToUnlock, true, pwd)
                                    } else {
                                        pendingLockAction = foldersToUnlock
                                        pendingActionIsUnlock = true
                                        showLockPasswordDialog = true
                                    }
                                }
                            }
                            ActionType.SHARE -> {
                                val uris = selectedItems
                                    .filter { java.io.File(it).isFile }
                                    .mapNotNull { path ->
                                        try {
                                            androidx.core.content.FileProvider.getUriForFile(
                                                context,
                                                "${context.packageName}.provider",
                                                java.io.File(path)
                                            )
                                        } catch (e: Exception) { null }
                                    }
                                if (uris.isNotEmpty()) {
                                    val intent = if (uris.size == 1) {
                                        android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                            type = "video/*"
                                            putExtra(android.content.Intent.EXTRA_STREAM, uris.first())
                                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                    } else {
                                        android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).apply {
                                            type = "video/*"
                                            putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, ArrayList(uris))
                                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                    }
                                    context.startActivity(android.content.Intent.createChooser(intent, context.getString(R.string.share_videos)).apply {
                                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    })
                                }
                                selectedItems.clear()
                            }
                            ActionType.TAGS -> {
                                coroutineScope.launch {
                                    val targetVideos = selectedItems.filter { File(it).isFile }
                                    val tagScope = currentTagScope()
                                    availableTags = getCachedTags(tagScope)
                                    commonSelectedTagIds = withContext(Dispatchers.IO) {
                                        VideoTagStore.getCommonTagIds(context, targetVideos, tagScope)
                                    }
                                    showVideoTagsDialog = true
                                }
                            }
                            ActionType.DELETE -> showDeleteConfirmDialog = true
                            ActionType.RENAME -> showRenameDialog = true
                            ActionType.MOVE -> {
                                val isFromLocked = FolderLockManager.isLocked(folderPath) &&
                                        LockedPlaybackSession.isActive &&
                                        LockedPlaybackSession.hasSessionForFolder(folderPath)
                                moveSourceLockedFolder = if (isFromLocked) folderPath else null
                                itemsToMove = selectedItems.toList()
                                selectedItems.clear()
                                isMoveMode = true
                                SortRowMessageCenter.showPersistentInfo(context.getString(R.string.move_mode_activated))
                            }
                            ActionType.CANCEL_MOVE -> {
                                isMoveMode = false
                                itemsToMove = emptyList()
                                moveSourceLockedFolder = null
                                SortRowMessageCenter.showInfo(context.getString(R.string.move_operation_cancelled))
                            }
                            ActionType.SHUFFLE_PLAY -> {
                                launchShufflePlayback()
                            }
                            ActionType.CREATE_FOLDER -> showCreateFolderDialog = true
                            ActionType.PASTE -> {
                                coroutineScope.launch {
                                    val movedItems = itemsToMove.toList()
                                    val destIsLocked = FolderLockManager.isLocked(folderPath)
                                    val sourceLockedFolder = moveSourceLockedFolder
                                    val pwd = sessionPassword ?: LockedPlaybackSession.sessionPassword

                                    val pasted = LockedFolderOperations.pasteItems(
                                        context = context,
                                        movedItems = movedItems,
                                        destinationPath = folderPath,
                                        sourceLockedFolder = sourceLockedFolder,
                                        password = pwd,
                                        destinationIsLocked = destIsLocked,
                                        onMoveStateChange = {
                                            isMoving = it
                                            if (!it) moveProgress = ""
                                        },
                                        onMoveProgress = { current, total -> moveProgress = "$current/$total" },
                                        onLockStateChange = {
                                            isLocking = it
                                            if (!it) lockProgress = ""
                                        },
                                        onLockProgress = { current, total -> lockProgress = "$current/$total" },
                                        onError = { message -> SortRowMessageCenter.showError(message) },
                                        onSuccess = { SortRowMessageCenter.showSuccess(context.getString(R.string.items_moved)) }
                                    )

                                    itemsToMove = emptyList()
                                    isMoveMode = false
                                    moveSourceLockedFolder = null
                                    renameTrigger++

                                    if (pasted) {
                                        refreshAffectedPaths(movedItems.mapNotNull { File(it).parent } + folderPath)
                                    }
                                }
                            }
                            ActionType.SET_AS_SECURE_FOLDER -> {
                                coroutineScope.launch {
                                    val folderPath = selectedItems.first()
                                    FilesManager.SecureStorage.setCustomSecureFolderPath(context, folderPath)

                                    SortRowMessageCenter.showSuccess(context.getString(R.string.secure_folder_set))

                                    selectedItems.clear()
                                    renameTrigger++
                                    refreshAffectedPaths(listOf(folderPath, File(folderPath).parent ?: folderPath))
                                }
                            }
                        }
                    },
                    onActionLongClick = { action ->
                        if (action == ActionType.SHUFFLE_PLAY) {
                            coroutineScope.launch {
                                availableTags = getCachedTags(currentTagScope())
                                showShuffleTagsDialog = true
                            }
                        }
                    }
                )
                } // Box navigationBarsPadding
            }
        },
        bottomBar = {
            if (currentRoute != "video_player" && currentRoute?.startsWith("settings") != true && !showPlayerOverlay) {
                MiniPlayerImproved(
                    onOpenPlayer = { showPlayerOverlay = true },
                    modifier = Modifier.navigationBarsPadding()
                )
            }
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            NavHost(
                navController = navController,
                startDestination = "folder",
                enterTransition = {
                    slideInHorizontally(
                        initialOffsetX = { it / 3 },
                        animationSpec = tween(250)
                    ) + fadeIn(animationSpec = tween(200))
                },
                exitTransition = {
                    slideOutHorizontally(
                        targetOffsetX = { -it / 3 },
                        animationSpec = tween(250)
                    ) + fadeOut(animationSpec = tween(150))
                },
                popEnterTransition = {
                    slideInHorizontally(
                        initialOffsetX = { -it / 3 },
                        animationSpec = tween(250)
                    ) + fadeIn(animationSpec = tween(200))
                },
                popExitTransition = {
                    slideOutHorizontally(
                        targetOffsetX = { it / 3 },
                        animationSpec = tween(250)
                    ) + fadeOut(animationSpec = tween(150))
                }
            ) {
                // Rota única para pastas - navegação gerenciada por FolderNavigationState
                composable("folder") {
                    val isSecure = isSecureFolder(folderPath)

                    FolderScreen(
                        folderPath = folderPath,
                        isSecureMode = isSecure,
                        isRootLevel = isAtRootLevel,
                        showPrivateFolders = showPrivateFolders,
                        isPlayerOverlayVisible = showPlayerOverlay,
                        isMoveMode = isMoveMode,
                        itemsToMove = itemsToMove,
                        onContinueWatchingClick = { entry ->
                            openVideoFromFolder(
                                targetFolderPath = entry.folderPath,
                                itemPath = entry.videoPath,
                                resumePositionMs = entry.positionMs
                            )
                        },
                        onFolderClick = { itemPath, currentSortType ->
                            val items = loadFolderContent(
                                context = context,
                                folderPath = folderPath,
                                sortType = currentSortType,
                                isSecureMode = isSecure,
                                isRootLevel = isAtRootLevel,
                                showPrivateFolders = showPrivateFolders
                            )
                            val item = items.find { it.path == itemPath }
                            if (item?.isFolder == true) {
                                // Check if folder is locked - use sessionPassword
                                if (FolderLockManager.isLocked(itemPath)) {
                                    val pwd = sessionPassword ?: LockedPlaybackSession.sessionPassword
                                    if (pwd != null) {
                                        coroutineScope.launch {
                                            val manifest = withContext(Dispatchers.IO) {
                                                FolderLockManager.readManifest(itemPath, pwd)
                                            }
                                            if (manifest != null) {
                                                val salt = withContext(Dispatchers.IO) {
                                                    FolderLockManager.getSalt(itemPath)
                                                }
                                                if (salt != null) {
                                                    val xorKey = withContext(Dispatchers.IO) {
                                                        FolderLockManager.deriveXorKey(pwd, salt)
                                                    }
                                                    LockedPlaybackSession.start(xorKey, manifest, itemPath, pwd)
                                                    folderNavState.navigateTo(itemPath)
                                                }
                                            } else {
                                                SortRowMessageCenter.showError(context.getString(R.string.invalid_password_or_corrupted))
                                            }
                                        }
                                    }
                                } else {
                                    folderNavState.navigateTo(itemPath)
                                }
                            } else {
                                openVideoFromFolder(folderPath, itemPath, currentSortType)
                            }
                        },
                        selectedItems = selectedItems,
                        onSelectionChange = { newSelection ->
                            selectedItems.clear()
                            selectedItems.addAll(newSelection)
                            showFabMenu = false
                            showRenameDialog = false
                        },
                        renameTrigger = renameTrigger,
                        deletedVideoPath = deletedVideoPath
                    )
                }

                composable("settings") {
                    SettingsScreen(navController)
                }
                composable("settings/playback") {
                    PlaybackSettingsScreen()
                }
                composable("settings/interface") {
                    // MODIFICADO: Passar themeManager para InterfaceSettingsScreen
                    InterfaceSettingsScreen(themeManager)
                }
                composable("settings/storage") {
                    StorageSettingsScreen()
                }
                composable("settings/tags") {
                    TagsSettingsScreen()
                }
                composable("settings/about") {
                    AboutSettingsScreen()
                }
                composable("settings/display") {
                    DisplaySettingsScreen()
                }
                composable("settings/security") {
                    SecuritySettingsScreen()
                }
            }
        }

        // ═══════════════════════════════════════════════════════════════════
        // BackHandlers DEPOIS do NavHost para ter prioridade (ordem LIFO)
        // O último BackHandler registrado é o primeiro a ser verificado
        // ═══════════════════════════════════════════════════════════════════

        // BackHandler para navegação de pastas (volta para pasta anterior)
        BackHandler(enabled = !showPlayerOverlay && !isAtRootLevel && currentRoute == "folder") {
            Log.d("BackDebug", "🔙 BACK PRESSED - Handler: FOLDER NAVIGATION")
            Log.d("BackDebug", "   Ação: Voltando para pasta anterior")
            // Limpa seleção ao voltar
            if (selectedItems.isNotEmpty()) {
                selectedItems.clear()
            }
            // When navigating back from a locked subfolder, update currentFolderPath to parent
            val parentFolder = File(folderPath).parent
            if (parentFolder != null && FolderLockManager.isLocked(folderPath) &&
                LockedPlaybackSession.hasSessionForFolder(folderPath)) {
                if (LockedPlaybackSession.hasSessionForFolder(parentFolder)) {
                    LockedPlaybackSession.setCurrentFolder(parentFolder)
                }
            }
            folderNavState.navigateBack()
        }

        // BackHandler para ignorar voltar na root (não fecha o app)
        BackHandler(enabled = !showPlayerOverlay && isAtRootLevel && currentRoute == "folder") {
            // Não fazer nada - apenas consumir o evento para não fechar o app
        }

        // BackHandler para o overlay - PRIORIDADE MÁXIMA (registrado por último)
        BackHandler(enabled = showPlayerOverlay) {
            Log.d("BackDebug", "🔙 BACK PRESSED - Handler: OVERLAY (após NavHost)")
            Log.d("BackDebug", "   Ação: Fechando overlay")
            closePlayerOverlay()
        }

        VideoPlayerOverlay(
            isVisible = showPlayerOverlay,
            canControlRotation = showPlayerOverlay,
            onDismiss = { closePlayerOverlay() },
            onManageTags = {
                showPlayerOverlay = false
                isInPiPMode = false
                navController.navigate("settings/tags")
            },
            onVideoDeleted = { deletedPath ->
                deletedVideoPath = deletedPath
                CoroutineScope(Dispatchers.Main).launch {
                    delay(100)
                    deletedVideoPath = null
                }
            },
        )

    }
}
