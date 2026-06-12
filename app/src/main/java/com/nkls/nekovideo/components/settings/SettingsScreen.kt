package com.nkls.nekovideo.components.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.text.style.TextAlign
import android.content.ContextWrapper
import androidx.compose.material.icons.filled.Favorite
import androidx.fragment.app.FragmentActivity
import com.nkls.nekovideo.components.ChangePasswordDialog
import com.nkls.nekovideo.components.PasswordDialog
import com.nkls.nekovideo.components.helpers.BiometricHelper
import com.nkls.nekovideo.components.helpers.FilesManager
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.nkls.nekovideo.BuildConfig
import com.nkls.nekovideo.R
import com.nkls.nekovideo.language.LanguageManager
import com.nkls.nekovideo.theme.ThemeManager
import androidx.core.content.edit
import com.nkls.nekovideo.components.OptimizedThumbnailManager
import com.nkls.nekovideo.components.helpers.TagEntity
import com.nkls.nekovideo.components.helpers.TagScope
import com.nkls.nekovideo.components.helpers.SortRowMessageCenter
import com.nkls.nekovideo.components.helpers.VideoTagStore
import com.nkls.nekovideo.services.FolderVideoScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val donateIntent = remember {
        Intent(Intent.ACTION_VIEW, Uri.parse("https://ko-fi.com/fellipepv")).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        @Suppress("UnusedBoxWithConstraintsScope")
        val isCompact = this.maxWidth > 600.dp

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(if (isCompact) 8.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(if (isCompact) 6.dp else 12.dp)
        ) {
            item {
                SettingsCategoryCard(
                    icon = Icons.Default.PlayArrow,
                    title = stringResource(R.string.settings_playback),
                    subtitle = stringResource(R.string.settings_playback_desc),
                    onClick = { navController.navigate("settings/playback") },
                    isCompact = isCompact
                )
            }

            item {
                SettingsCategoryCard(
                    icon = Icons.Default.Palette,
                    title = stringResource(R.string.settings_interface),
                    subtitle = stringResource(R.string.settings_interface_desc),
                    onClick = { navController.navigate("settings/interface") },
                    isCompact = isCompact
                )
            }

            item {
                SettingsCategoryCard(
                    icon = Icons.AutoMirrored.Filled.ViewList,
                    title = stringResource(R.string.settings_display),
                    subtitle = stringResource(R.string.settings_display_desc),
                    onClick = { navController.navigate("settings/display") },
                    isCompact = isCompact
                )
            }

            item {
                SettingsCategoryCard(
                    icon = Icons.Default.Storage,
                    title = stringResource(R.string.settings_storage),
                    subtitle = stringResource(R.string.settings_storage_desc),
                    onClick = { navController.navigate("settings/storage") },
                    isCompact = isCompact
                )
            }

            item {
                SettingsCategoryCard(
                    icon = Icons.Default.LocalOffer,
                    title = stringResource(R.string.settings_tags),
                    subtitle = stringResource(R.string.settings_tags_desc),
                    onClick = { navController.navigate("settings/tags") },
                    isCompact = isCompact
                )
            }

            item {
                SettingsCategoryCard(
                    icon = Icons.Default.Lock,
                    title = stringResource(R.string.settings_security),
                    subtitle = stringResource(R.string.settings_security_desc),
                    onClick = { navController.navigate("settings/security") },
                    isCompact = isCompact
                )
            }

            item {
                SettingsCategoryCard(
                    icon = Icons.Default.Info,
                    title = stringResource(R.string.settings_about),
                    subtitle = stringResource(R.string.settings_about_desc),
                    onClick = { navController.navigate("settings/about") },
                    isCompact = isCompact
                )
            }

            item {
                SettingsCategoryCard(
                    icon = Icons.Default.Favorite,
                    title = stringResource(R.string.settings_support_developer),
                    subtitle = stringResource(R.string.settings_support_developer_desc),
                    onClick = { context.startActivity(donateIntent) },
                    isCompact = isCompact
                )
            }

        }
    }
}

@Composable
fun PlaybackSettingsScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("nekovideo_settings", Context.MODE_PRIVATE) }

    var autoHideControls by remember { mutableStateOf(prefs.getBoolean("auto_hide_controls", true)) }
    var autoPip by remember { mutableStateOf(prefs.getBoolean("auto_pip", true)) }
    var doubleTapSeek by remember { mutableIntStateOf(prefs.getInt("double_tap_seek", 10)) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        @Suppress("UnusedBoxWithConstraintsScope")
        val isCompact = this.maxWidth > 600.dp

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(if (isCompact) 8.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(if (isCompact) 6.dp else 12.dp)
        ) {
            item {
                SettingsSectionHeader(stringResource(R.string.playback_controls), isCompact)
            }

            item {
                SettingsSwitchItem(
                    icon = Icons.Default.VisibilityOff,
                    title = stringResource(R.string.playback_auto_hide_controls),
                    subtitle = stringResource(R.string.playback_auto_hide_controls_desc),
                    checked = autoHideControls,
                    onCheckedChange = {
                        autoHideControls = it
                        prefs.edit { putBoolean("auto_hide_controls", it) }
                    },
                    isCompact = isCompact
                )
            }

            item {
                SettingsSwitchItem(
                    icon = Icons.Default.PlayArrow,
                    title = stringResource(R.string.playback_pip),
                    subtitle = stringResource(R.string.playback_pip_desc),
                    checked = autoPip,
                    onCheckedChange = {
                        autoPip = it
                        prefs.edit { putBoolean("auto_pip", it) }
                    },
                    isCompact = isCompact
                )
            }

            item {
                SettingsSliderItem(
                    icon = Icons.Default.SkipNext,
                    title = stringResource(R.string.playback_double_tap_seek),
                    subtitle = stringResource(R.string.playback_double_tap_seek_desc),
                    value = doubleTapSeek,
                    range = 5..30,
                    step = 5,
                    onValueChange = {
                        doubleTapSeek = it
                        prefs.edit { putInt("double_tap_seek", it) }
                    },
                    isCompact = isCompact
                )
            }

        }
    }
}

@Composable
fun InterfaceSettingsScreen(themeManager: ThemeManager) {
    val context = LocalContext.current
    val currentTheme by themeManager.themeMode.collectAsState()

    val languageStateFlow by LanguageManager.currentLanguage.collectAsState()

    var currentLanguage by remember(languageStateFlow) {
        mutableStateOf(LanguageManager.getCurrentLanguage(context))
    }

    val darkModeOptions = listOf(
        "light" to stringResource(R.string.theme_light),
        "dark" to stringResource(R.string.theme_dark),
        "system" to stringResource(R.string.theme_system)
    )

    val languageOptions = listOf(
        "system" to stringResource(R.string.language_system),
        "pt" to stringResource(R.string.language_portuguese),
        "en" to stringResource(R.string.language_english),
        "es" to stringResource(R.string.language_spanish),
        "fr" to stringResource(R.string.language_french),
        "de" to stringResource(R.string.language_german),
        "ru" to stringResource(R.string.language_russian),
        "hi" to stringResource(R.string.language_hindi)
    )

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        @Suppress("UnusedBoxWithConstraintsScope")
        val isCompact = this.maxWidth > 600.dp

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(if (isCompact) 8.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(if (isCompact) 6.dp else 12.dp)
        ) {

            item {
                SettingsSectionHeader(stringResource(R.string.settings_interface), isCompact)
            }

            item {
                SettingsDropdownItem(
                    icon = Icons.Default.Palette,
                    title = stringResource(R.string.settings_dark_mode),
                    subtitle = stringResource(R.string.settings_dark_mode_desc),
                    options = darkModeOptions,
                    selectedValue = currentTheme,
                    onValueChange = { newTheme ->
                        themeManager.updateTheme(newTheme)
                    },
                    isCompact = isCompact
                )
            }

            item {
                SettingsSectionHeader(stringResource(R.string.settings_language), isCompact)
            }

            item {
                SettingsDropdownItem(
                    icon = Icons.Default.Language,
                    title = stringResource(R.string.settings_app_language),
                    subtitle = stringResource(R.string.settings_app_language_desc),
                    options = languageOptions,
                    selectedValue = currentLanguage,
                    onValueChange = { newLanguage ->
                        LanguageManager.updateLanguage(context, newLanguage)
                    },
                    isCompact = isCompact
                )
            }

        }
    }
}

@Composable
fun DisplaySettingsScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("nekovideo_settings", Context.MODE_PRIVATE) }

    var showDurations by remember { mutableStateOf(prefs.getBoolean("show_durations", true)) }
    var showFileSizes by remember { mutableStateOf(prefs.getBoolean("show_file_sizes", false)) }
    listOf(
        "low" to stringResource(R.string.quality_low),
        "medium" to stringResource(R.string.quality_medium),
        "high" to stringResource(R.string.quality_high),
        "original" to stringResource(R.string.quality_original)
    )

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        @Suppress("UnusedBoxWithConstraintsScope")
        val isCompact = this.maxWidth > 600.dp

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(if (isCompact) 8.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(if (isCompact) 6.dp else 12.dp)
        ) {
            item {
                SettingsSectionHeader(stringResource(R.string.display_video_info), isCompact)
            }

            item {
                SettingsSwitchItem(
                    icon = Icons.Default.Schedule,
                    title = stringResource(R.string.display_show_durations),
                    subtitle = stringResource(R.string.display_show_durations_desc),
                    checked = showDurations,
                    onCheckedChange = {
                        showDurations = it
                        prefs.edit { putBoolean("show_durations", it) }
                    },
                    isCompact = isCompact
                )
            }

            item {
                SettingsSwitchItem(
                    icon = Icons.Default.Storage,
                    title = stringResource(R.string.display_show_file_sizes),
                    subtitle = stringResource(R.string.display_show_file_sizes_desc),
                    checked = showFileSizes,
                    onCheckedChange = {
                        showFileSizes = it
                        prefs.edit { putBoolean("show_file_sizes", it) }
                    },
                    isCompact = isCompact
                )
            }

//        item {
//            SettingsSectionHeader(stringResource(R.string.display_quality))
//        }

//        item {
//            SettingsDropdownItem(
//                icon = Icons.Default.HighQuality,
//                title = stringResource(R.string.display_thumbnail_quality),
//                subtitle = stringResource(R.string.display_thumbnail_quality_desc),
//                options = qualityOptions,
//                selectedValue = thumbnailQuality,
//                onValueChange = {
//                    thumbnailQuality = it
//                    prefs.edit().putString("thumbnail_quality", it).apply()
//                }
//            )
//        }
        }
    }
}


@Composable
fun StorageSettingsScreen() {
    val context = LocalContext.current

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        @Suppress("UnusedBoxWithConstraintsScope")
        val isCompact = this.maxWidth > 600.dp

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(if (isCompact) 8.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(if (isCompact) 6.dp else 12.dp)
        ) {
            item {
                SettingsSectionHeader(stringResource(R.string.storage_thumbnails_section), isCompact)
            }

            item {
                SettingsClickableItem(
                    icon = Icons.Default.Image,
                    title = stringResource(R.string.storage_clear_thumbnails),
                    subtitle = stringResource(R.string.storage_clear_thumbnails_desc),
                    onClick = {
                        CoroutineScope(Dispatchers.IO).launch {
                            val folderPaths = FolderVideoScanner.cache.value.keys
                            OptimizedThumbnailManager.clearCache()
                            OptimizedThumbnailManager.clearAllDiskThumbnails(context, folderPaths)
                        }
                        SortRowMessageCenter.showSuccess(context.getString(R.string.storage_clear_thumbnails_success))
                    },
                    isCompact = isCompact
                )
            }
        }
    }
}

@Composable
fun TagsSettingsScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val hasPassword = remember { FilesManager.SecureStorage.hasPassword(context) }
    var showPrivatePasswordDialog by remember { mutableStateOf(false) }
    var privateUnlocked by remember { mutableStateOf(false) }
    var normalTags by remember { mutableStateOf<List<TagEntity>>(emptyList()) }
    var privateTags by remember { mutableStateOf<List<TagEntity>>(emptyList()) }
    var hasAutomaticBackup by remember { mutableStateOf(false) }
    var shouldOfferImport by remember { mutableStateOf(false) }
    var lastAutomaticBackupAt by remember { mutableStateOf(0L) }
    var refreshToken by remember { mutableIntStateOf(0) }

    suspend fun refreshTags() {
        val loadedNormalTags = withContext(Dispatchers.IO) {
            VideoTagStore.getAllTags(context, TagScope.NORMAL)
        }
        val loadedPrivateTags = if (privateUnlocked) {
            withContext(Dispatchers.IO) {
                VideoTagStore.getAllTags(context, TagScope.PRIVATE)
            }
        } else {
            emptyList()
        }
        val automaticBackupExists = withContext(Dispatchers.IO) {
            VideoTagStore.hasAutomaticBackup(context)
        }
        val shouldShowImport = withContext(Dispatchers.IO) {
            VideoTagStore.shouldOfferAutomaticImport(context)
        }
        val automaticBackupTimestamp = withContext(Dispatchers.IO) {
            VideoTagStore.getLastAutomaticBackupAt(context)
        }

        normalTags = loadedNormalTags
        privateTags = loadedPrivateTags
        hasAutomaticBackup = automaticBackupExists
        shouldOfferImport = shouldShowImport
        lastAutomaticBackupAt = automaticBackupTimestamp
    }

    fun showImportSuccessToast(result: VideoTagStore.TagBackupImportResult) {
        SortRowMessageCenter.showSuccess(
            context.getString(
                R.string.tags_backup_import_success,
                result.createdTags,
                result.restoredRefs
            ),
            durationMs = 4500L
        )
    }

    LaunchedEffect(refreshToken, privateUnlocked) {
        refreshTags()
    }

    if (showPrivatePasswordDialog) {
        PasswordDialog(
            onDismiss = { showPrivatePasswordDialog = false },
            onPasswordVerified = {
                showPrivatePasswordDialog = false
                privateUnlocked = true
            }
        )
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        @Suppress("UnusedBoxWithConstraintsScope")
        val isCompact = this.maxWidth > 600.dp

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(if (isCompact) 8.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(if (isCompact) 6.dp else 12.dp)
        ) {
            item { SettingsSectionHeader(stringResource(R.string.tags_backup_section), isCompact) }
            item {
                Text(
                    text = if (lastAutomaticBackupAt > 0L && hasAutomaticBackup) {
                        stringResource(
                            R.string.tags_backup_auto_status,
                            formatBackupTimestamp(lastAutomaticBackupAt)
                        )
                    } else {
                        stringResource(R.string.tags_backup_auto_status_empty)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = if (isCompact) 4.dp else 8.dp)
                )
            }
            if (shouldOfferImport) {
                item {
                SettingsClickableItem(
                    icon = Icons.Default.Folder,
                    title = stringResource(R.string.tags_backup_import),
                    subtitle = stringResource(R.string.tags_backup_import_desc),
                    onClick = {
                        coroutineScope.launch {
                            val result = withContext(Dispatchers.IO) {
                                VideoTagStore.importLatestAutomaticBackup(context)
                            }

                            if (result.isSuccess) {
                                showImportSuccessToast(result.getOrThrow())
                                refreshToken++
                            } else {
                                SortRowMessageCenter.showError(context.getString(R.string.tags_backup_action_failed))
                            }
                        }
                    },
                    isCompact = isCompact
                )
            }
            }

            item { SettingsSectionHeader(stringResource(R.string.tags_normal_section), isCompact) }
            item {
                TagScopeManagerCard(
                    scope = TagScope.NORMAL,
                    tags = normalTags,
                    isCompact = isCompact,
                    onCreateTag = { name ->
                        val result = VideoTagStore.createTag(context, name, TagScope.NORMAL)
                        if (result.isSuccess) refreshToken++
                        result
                    },
                    onRenameTag = { tagId, name ->
                        val result = VideoTagStore.renameTag(context, tagId, name, TagScope.NORMAL)
                        if (result.isSuccess) refreshToken++
                        result
                    },
                    onDeleteTag = { tagId ->
                        VideoTagStore.deleteTag(context, tagId)
                        refreshToken++
                    }
                )
            }

            item { SettingsSectionHeader(stringResource(R.string.tags_private_section), isCompact) }
            if (!privateUnlocked) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(if (isCompact) 12.dp else 16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.tags_private_locked_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = if (hasPassword) stringResource(R.string.tags_private_locked_desc) else stringResource(R.string.tags_private_password_required),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(
                                onClick = { showPrivatePasswordDialog = true },
                                enabled = hasPassword,
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                                    contentColor = MaterialTheme.colorScheme.onSurface
                                )
                            ) {
                                Text(stringResource(R.string.tags_private_unlock))
                            }
                        }
                    }
                }
            } else {
                item {
                    TagScopeManagerCard(
                        scope = TagScope.PRIVATE,
                        tags = privateTags,
                        isCompact = isCompact,
                        onCreateTag = { name ->
                            val result = VideoTagStore.createTag(context, name, TagScope.PRIVATE)
                            if (result.isSuccess) refreshToken++
                            result
                        },
                        onRenameTag = { tagId, name ->
                            val result = VideoTagStore.renameTag(context, tagId, name, TagScope.PRIVATE)
                            if (result.isSuccess) refreshToken++
                            result
                        },
                        onDeleteTag = { tagId ->
                            VideoTagStore.deleteTag(context, tagId)
                            refreshToken++
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun SecuritySettingsScreen() {
    val context = LocalContext.current
    val view = LocalView.current
    val activity = remember(view) {
        var ctx: android.content.Context = view.context
        while (ctx is ContextWrapper && ctx !is FragmentActivity) ctx = ctx.baseContext
        ctx as FragmentActivity
    }
    val hasPassword = remember { FilesManager.SecureStorage.hasPassword(context) }
    val biometricAvailable = remember { BiometricHelper.isBiometricAvailable(context) }
    var biometricEnabled by remember { mutableStateOf(BiometricHelper.isBiometricEnabled(context)) }
    var showPasswordConfirmDialog by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf("") } // "enable" or "disable"
    var showChangePasswordDialog by remember { mutableStateOf(false) }

    if (showChangePasswordDialog) {
        ChangePasswordDialog(
            onDismiss = { showChangePasswordDialog = false },
            onSuccess = {
                showChangePasswordDialog = false
                SortRowMessageCenter.showSuccess(context.getString(R.string.change_password_success))
            }
        )
    }

    if (showPasswordConfirmDialog) {
        PasswordDialog(
            onDismiss = { showPasswordConfirmDialog = false; pendingAction = "" },
            onPasswordVerified = { password ->
                showPasswordConfirmDialog = false
                when (pendingAction) {
                    "enable" -> {
                        BiometricHelper.enable(
                            activity = activity,
                            password = password,
                            title = context.getString(R.string.biometric_enable_prompt_title),
                            subtitle = context.getString(R.string.biometric_enable_prompt_subtitle),
                            negativeText = context.getString(R.string.biometric_enable_cancel),
                            onSuccess = {
                                biometricEnabled = true
                                SortRowMessageCenter.showSuccess(context.getString(R.string.biometric_enabled_success))
                            },
                            onError = { }
                        )
                    }
                    "disable" -> {
                        BiometricHelper.disable(context)
                        biometricEnabled = false
                        SortRowMessageCenter.showInfo(context.getString(R.string.biometric_disabled))
                    }
                }
                pendingAction = ""
            }
        )
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        @Suppress("UnusedBoxWithConstraintsScope")
        val isCompact = this.maxWidth > 600.dp

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(if (isCompact) 8.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(if (isCompact) 6.dp else 12.dp)
        ) {
            item { SettingsSectionHeader(stringResource(R.string.change_password_section), isCompact) }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    shape = RoundedCornerShape(8.dp),
                    onClick = { if (hasPassword) showChangePasswordDialog = true }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(if (isCompact) 10.dp else 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = if (hasPassword) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(if (isCompact) 20.dp else 24.dp)
                        )
                        Spacer(modifier = Modifier.width(if (isCompact) 10.dp else 16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.change_password),
                                style = if (isCompact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = if (hasPassword) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = if (hasPassword) stringResource(R.string.change_password_desc) else stringResource(R.string.biometric_no_password),
                                style = if (isCompact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = if (hasPassword) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                    }
                }
            }

            item { SettingsSectionHeader(stringResource(R.string.biometric_section), isCompact) }

            item {
                when {
                    !hasPassword -> {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(24.dp)
                                )
                                Text(
                                    text = stringResource(R.string.biometric_no_password),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    !biometricAvailable -> {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Fingerprint,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(24.dp)
                                )
                                Text(
                                    text = stringResource(R.string.biometric_not_available),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    else -> {
                        SettingsSwitchItem(
                            icon = Icons.Default.Fingerprint,
                            title = stringResource(R.string.biometric_unlock),
                            subtitle = stringResource(R.string.biometric_unlock_desc),
                            checked = biometricEnabled,
                            onCheckedChange = { enabled ->
                                pendingAction = if (enabled) "enable" else "disable"
                                showPasswordConfirmDialog = true
                            },
                            isCompact = isCompact
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AboutSettingsScreen() {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    fun openBugReport() {
        runCatching {
            uriHandler.openUri("https://github.com/FellipitoPV/NekoVideo/issues/new/choose")
        }.onFailure {
            SortRowMessageCenter.showError("Nao foi possivel abrir o link")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        // Hero card — ícone + nome + versão
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(36.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val appIconBitmap = remember {
                    val drawable = context.packageManager.getApplicationIcon(context.packageName)
                    val size = 192
                    val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(bmp)
                    drawable.setBounds(0, 0, size, size)
                    drawable.draw(canvas)
                    bmp
                }
                Image(
                    bitmap = appIconBitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(88.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(50)
                        )
                        .padding(horizontal = 14.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "v${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Card de reportar bug — destaque com cor de erro
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { openBugReport() },
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.BugReport,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.about_report_bug),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.about_report_bug_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun TagScopeManagerCard(
    scope: TagScope,
    tags: List<TagEntity>,
    isCompact: Boolean,
    onCreateTag: suspend (String) -> Result<TagEntity>,
    onRenameTag: suspend (Long, String) -> Result<Unit>,
    onDeleteTag: suspend (Long) -> Unit
) {
    var isSelectionMode by remember(scope) { mutableStateOf(false) }
    var selectedTagIds by remember(scope) { mutableStateOf(setOf<Long>()) }
    var showCreateSheet by remember(scope) { mutableStateOf(false) }
    var renameTarget by remember(scope) { mutableStateOf<TagEntity?>(null) }
    val coroutineScope = rememberCoroutineScope()

    fun exitSelectionMode() {
        isSelectionMode = false
        selectedTagIds = emptySet()
    }

    if (showCreateSheet) {
        TagNameBottomSheet(
            title = stringResource(R.string.video_tags_create),
            initialValue = "",
            actionLabel = stringResource(R.string.video_tags_create),
            onDismiss = { showCreateSheet = false },
            onSubmit = { name -> onCreateTag(name) }
        ) {
            showCreateSheet = false
        }
    }

    renameTarget?.let { target ->
        TagNameBottomSheet(
            title = stringResource(R.string.action_rename),
            initialValue = target.name,
            actionLabel = stringResource(R.string.action_rename),
            onDismiss = { renameTarget = null },
            onSubmit = { name ->
                onRenameTag(target.id, name).also {
                    if (it.isSuccess) {
                        renameTarget = null
                    }
                }
            }
        ) {
            renameTarget = null
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(if (isCompact) 12.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { showCreateSheet = true },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Text(stringResource(R.string.video_tags_create))
                }

                Button(
                    onClick = {
                        if (isSelectionMode) {
                            exitSelectionMode()
                        } else {
                            isSelectionMode = true
                        }
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSelectionMode) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                        },
                        contentColor = if (isSelectionMode) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.edit_tag))
                }
            }

            if (tags.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isSelectionMode) {
                        Text(
                            text = "${selectedTagIds.size}/${tags.size}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (isSelectionMode) {
                            IconButton(
                                onClick = {
                                    val selectedId = selectedTagIds.singleOrNull() ?: return@IconButton
                                    val selectedTag = tags.firstOrNull { it.id == selectedId } ?: return@IconButton
                                    renameTarget = selectedTag
                                    exitSelectionMode()
                                },
                                enabled = selectedTagIds.size == 1
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = stringResource(R.string.action_rename)
                                )
                            }

                            IconButton(
                                onClick = {
                                    val idsToDelete = selectedTagIds.toList()
                                    if (idsToDelete.isEmpty()) return@IconButton

                                    coroutineScope.launch {
                                        idsToDelete.forEach { tagId ->
                                            onDeleteTag(tagId)
                                        }
                                        exitSelectionMode()
                                    }
                                },
                                enabled = selectedTagIds.isNotEmpty()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = stringResource(R.string.tags_delete),
                                    tint = if (selectedTagIds.isNotEmpty()) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }

                            IconButton(onClick = { exitSelectionMode() }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.action_cancel)
                                )
                            }
                        }
                    }
                }
            }

            if (tags.isEmpty()) {
                Text(
                    text = stringResource(R.string.tags_empty_scope),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    tags.forEach { tag ->
                        val isSelected = tag.id in selectedTagIds
                        AssistChip(
                            onClick = {
                                if (isSelectionMode) {
                                    selectedTagIds = if (isSelected) {
                                        selectedTagIds - tag.id
                                    } else {
                                        selectedTagIds + tag.id
                                    }
                                }
                            },
                            label = { Text(tag.name) },
                            leadingIcon = if (isSelectionMode) {
                                {
                                    Icon(
                                        imageVector = if (isSelected) Icons.Default.Check else Icons.Default.LocalOffer,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            } else {
                                null
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = when {
                                    isSelectionMode && isSelected -> MaterialTheme.colorScheme.primaryContainer
                                    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)
                                },
                                labelColor = when {
                                    isSelectionMode && isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
                                    else -> MaterialTheme.colorScheme.onSurface
                                }
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TagNameBottomSheet(
    title: String,
    initialValue: String,
    actionLabel: String,
    onDismiss: () -> Unit,
    onSubmit: suspend (String) -> Result<*>,
    onSuccess: () -> Unit
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val bottomSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val emptyNameMessage = stringResource(R.string.video_tags_name_empty)
    val duplicateNameMessage = stringResource(R.string.video_tags_name_exists)

    fun submit() {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            errorMessage = emptyNameMessage
            return
        }

        isSubmitting = true
        errorMessage = null
        coroutineScope.launch {
            val result = onSubmit(trimmed)
            result.onSuccess {
                isSubmitting = false
                onSuccess()
            }.onFailure { error ->
                errorMessage = when (error.message) {
                    "empty" -> emptyNameMessage
                    "exists" -> duplicateNameMessage
                    else -> error.localizedMessage
                }
                isSubmitting = false
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        sheetState = bottomSheetState,
        dragHandle = {
            Surface(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                shape = RoundedCornerShape(2.dp)
            ) {
                Box(modifier = Modifier.size(width = 32.dp, height = 4.dp))
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )

            HorizontalDivider()

            OutlinedTextField(
                value = value,
                onValueChange = {
                    value = it
                    errorMessage = null
                },
                label = { Text(stringResource(R.string.new_name)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !isSubmitting,
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                )
            )

            errorMessage?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                TextButton(onClick = onDismiss, enabled = !isSubmitting) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    onClick = { submit() },
                    enabled = !isSubmitting,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
private fun SettingsCategoryCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    isCompact: Boolean = false
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(if (isCompact) 8.dp else 12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (isCompact) 12.dp else 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(if (isCompact) 24.dp else 32.dp)
            )

            Spacer(modifier = Modifier.width(if (isCompact) 10.dp else 16.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    style = if (isCompact) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = subtitle,
                    style = if (isCompact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "Abrir",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(if (isCompact) 20.dp else 24.dp)
            )
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String, isCompact: Boolean = false) {
    Text(
        text = title,
        style = if (isCompact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(vertical = if (isCompact) 2.dp else 8.dp)
    )
}

@Composable
private fun SettingsSwitchItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    isCompact: Boolean = false
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onCheckedChange(!checked) }
                .padding(if (isCompact) 10.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(if (isCompact) 20.dp else 24.dp)
            )

            Spacer(modifier = Modifier.width(if (isCompact) 10.dp else 16.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    style = if (isCompact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = subtitle,
                    style = if (isCompact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                )
            )
        }
    }
}

@Composable
private fun SettingsDropdownItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    options: List<Pair<String, String>>,
    selectedValue: String,
    onValueChange: (String) -> Unit,
    isCompact: Boolean = false
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedOption = options.find { it.first == selectedValue }?.second ?: "Desconhecido"

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = true }
                    .padding(if (isCompact) 10.dp else 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(if (isCompact) 20.dp else 24.dp)
                )

                Spacer(modifier = Modifier.width(if (isCompact) 10.dp else 16.dp))

                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = title,
                        style = if (isCompact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = subtitle,
                        style = if (isCompact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = selectedOption,
                        style = if (isCompact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = "Expandir",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.fillMaxWidth()
            ) {
                options.forEach { (value, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            onValueChange(value)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSliderItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    value: Int,
    range: IntRange,
    step: Int = 1,
    onValueChange: (Int) -> Unit,
    isCompact: Boolean = false
) {
    var sliderValue by remember(value) { mutableIntStateOf(value) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(if (isCompact) 10.dp else 16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(if (isCompact) 20.dp else 24.dp)
                )

                Spacer(modifier = Modifier.width(if (isCompact) 10.dp else 16.dp))

                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = title,
                        style = if (isCompact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = subtitle,
                        style = if (isCompact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = if (title.contains("Cache")) "${sliderValue}MB" else sliderValue.toString(),
                    style = if (isCompact) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(if (isCompact) 4.dp else 8.dp))

            Slider(
                value = sliderValue.toFloat(),
                onValueChange = {
                    val newValue = (it.toInt() / step) * step
                    sliderValue = newValue
                    onValueChange(newValue)
                },
                valueRange = range.first.toFloat()..range.last.toFloat(),
                steps = (range.last - range.first) / step - 1,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
}

@Composable
private fun SettingsClickableItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    isCompact: Boolean = false
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(if (isCompact) 10.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(if (isCompact) 20.dp else 24.dp)
            )

            Spacer(modifier = Modifier.width(if (isCompact) 10.dp else 16.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    style = if (isCompact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = subtitle,
                    style = if (isCompact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "Abrir",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

object SettingsManager {

    fun getDoubleTapSeek(context: Context): Int {
        return context.getSharedPreferences("nekovideo_settings", Context.MODE_PRIVATE)
            .getInt("double_tap_seek", 10)
    }

    fun getPlaybackSpeed(context: Context): Float {
        return context.getSharedPreferences("nekovideo_settings", Context.MODE_PRIVATE)
            .getFloat("playback_speed", 1.0f)
    }

    fun setPlaybackSpeed(context: Context, speed: Float) {
        context.getSharedPreferences("nekovideo_settings", Context.MODE_PRIVATE)
            .edit { putFloat("playback_speed", speed) }
    }

}

private fun formatBackupTimestamp(timestamp: Long): String {
    return SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(timestamp))
}
