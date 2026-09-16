package com.nkls.nekovideo.components.settings

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringArrayResource
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
import com.nkls.nekovideo.components.helpers.ContinueWatchingSettings
import com.nkls.nekovideo.components.helpers.ContinueWatchingStore
import com.nkls.nekovideo.components.helpers.VideoProgressStore
import com.nkls.nekovideo.components.helpers.VideoTagStore
import com.nkls.nekovideo.services.FolderVideoScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private sealed interface PlaybackSettingItem {
    val visible: Boolean

    data class Section(
        val titleRes: Int,
        override val visible: Boolean = true
    ) : PlaybackSettingItem

    data class Switch(
        val icon: ImageVector,
        val titleRes: Int,
        val subtitleRes: Int,
        val checked: Boolean,
        val enabled: Boolean = true,
        override val visible: Boolean = true,
        val onCheckedChange: (Boolean) -> Unit
    ) : PlaybackSettingItem

    data class Slider(
        val icon: ImageVector,
        val titleRes: Int,
        val subtitleRes: Int,
        val value: Int,
        val range: IntRange,
        val step: Int = 1,
        val discreteValues: List<Int>? = null,
        val enabled: Boolean = true,
        override val visible: Boolean = true,
        val valueFormatter: (Int) -> String = { it.toString() },
        val onValueChange: (Int) -> Unit
    ) : PlaybackSettingItem
}

private sealed interface InterfaceSettingItem {
    val visible: Boolean

    data class Section(
        val titleRes: Int,
        override val visible: Boolean = true
    ) : InterfaceSettingItem

    data class Dropdown(
        val icon: ImageVector,
        val titleRes: Int,
        val subtitleRes: Int,
        val options: List<Pair<String, String>>,
        val selectedValue: String,
        override val visible: Boolean = true,
        val onValueChange: (String) -> Unit
    ) : InterfaceSettingItem
}

private sealed interface DisplaySettingItem {
    val visible: Boolean

    data class Section(
        val titleRes: Int,
        override val visible: Boolean = true
    ) : DisplaySettingItem

    data class Switch(
        val icon: ImageVector,
        val titleRes: Int,
        val subtitleRes: Int,
        val checked: Boolean,
        val enabled: Boolean = true,
        override val visible: Boolean = true,
        val onCheckedChange: (Boolean) -> Unit
    ) : DisplaySettingItem
}

private sealed interface SecuritySettingItem {
    val visible: Boolean

    data class Section(
        val titleRes: Int,
        override val visible: Boolean = true
    ) : SecuritySettingItem

    data class Action(
        val icon: ImageVector,
        val titleRes: Int,
        val subtitleRes: Int,
        val enabled: Boolean = true,
        override val visible: Boolean = true,
        val onClick: () -> Unit
    ) : SecuritySettingItem

    data class Switch(
        val icon: ImageVector,
        val titleRes: Int,
        val subtitleRes: Int,
        val checked: Boolean,
        val enabled: Boolean = true,
        override val visible: Boolean = true,
        val onCheckedChange: (Boolean) -> Unit
    ) : SecuritySettingItem

    data class Info(
        val icon: ImageVector,
        val messageRes: Int,
        override val visible: Boolean = true
    ) : SecuritySettingItem
}

@Composable
fun SettingsScreen(navController: NavController) {
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
                    icon = Icons.Default.Schedule,
                    title = stringResource(R.string.settings_changelog, BuildConfig.VERSION_NAME),
                    subtitle = stringResource(R.string.settings_changelog_desc),
                    onClick = { navController.navigate("settings/changelog") },
                    isCompact = isCompact
                )
            }

        }
    }
}

@Composable
fun ChangelogSettingsScreen() {
    val changelogItems = stringArrayResource(R.array.changelog_item_titles)
        .zip(stringArrayResource(R.array.changelog_item_descriptions))

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = stringResource(R.string.changelog_latest_title, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        }

        items(changelogItems) { (title, description) ->
            ChangelogCard(title = title, body = description)
        }
    }
}

@Composable
private fun ChangelogCard(title: String, body: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun PlaybackSettingsScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("nekovideo_settings", Context.MODE_PRIVATE) }

    var backgroundPlayback by remember { mutableStateOf(prefs.getBoolean("background_playback", true)) }
    var doubleTapSeek by remember { mutableIntStateOf(prefs.getInt("double_tap_seek", 10)) }
    var dragSeekEnabled by remember { mutableStateOf(prefs.getBoolean("drag_seek_enabled", true)) }
    var volumeBrightnessGesturesEnabled by remember { mutableStateOf(prefs.getBoolean("volume_brightness_gestures_enabled", true)) }
    var continueWatchingEnabled by remember {
        mutableStateOf(ContinueWatchingSettings.isEnabled(context))
    }
    var continueWatchingMinMinutes by remember {
        mutableIntStateOf((ContinueWatchingSettings.getMinDurationMs(context) / 60_000L).toInt())
    }
    var continueWatchingIncludePrivate by remember {
        mutableStateOf(ContinueWatchingSettings.shouldIncludePrivateVideos(context))
    }
    val continueWatchingMinDurationOptions = listOf(5, 10, 15, 20, 25, 30, 40, 50, 60, 70, 80, 90)
    val playbackSettingsItems = listOf(
        PlaybackSettingItem.Section(R.string.playback_controls),
        PlaybackSettingItem.Switch(
            icon = Icons.Default.PlayArrow,
            titleRes = R.string.playback_background_playback,
            subtitleRes = R.string.playback_background_playback_desc,
            checked = backgroundPlayback,
            onCheckedChange = {
                backgroundPlayback = it
                prefs.edit { putBoolean("background_playback", it) }
            }
        ),
        PlaybackSettingItem.Slider(
            icon = Icons.Default.SkipNext,
            titleRes = R.string.playback_double_tap_seek,
            subtitleRes = R.string.playback_double_tap_seek_desc,
            value = doubleTapSeek,
            range = 5..30,
            step = 5,
            onValueChange = {
                doubleTapSeek = it
                prefs.edit { putInt("double_tap_seek", it) }
            }
        ),
        PlaybackSettingItem.Switch(
            icon = Icons.Default.SkipNext,
            titleRes = R.string.playback_drag_seek,
            subtitleRes = R.string.playback_drag_seek_desc,
            checked = dragSeekEnabled,
            onCheckedChange = {
                dragSeekEnabled = it
                prefs.edit { putBoolean("drag_seek_enabled", it) }
            }
        ),
        PlaybackSettingItem.Switch(
            icon = Icons.Default.PlayArrow,
            titleRes = R.string.playback_volume_brightness_gestures,
            subtitleRes = R.string.playback_volume_brightness_gestures_desc,
            checked = volumeBrightnessGesturesEnabled,
            onCheckedChange = {
                volumeBrightnessGesturesEnabled = it
                prefs.edit { putBoolean("volume_brightness_gestures_enabled", it) }
            }
        ),
        PlaybackSettingItem.Section(R.string.playback_continue_watching),
        PlaybackSettingItem.Switch(
            icon = Icons.Default.PlayArrow,
            titleRes = R.string.playback_continue_watching_enabled,
            subtitleRes = R.string.playback_continue_watching_enabled_desc,
            checked = continueWatchingEnabled,
            onCheckedChange = {
                continueWatchingEnabled = it
                prefs.edit { putBoolean("continue_watching_enabled", it) }
            }
        ),
        PlaybackSettingItem.Slider(
            icon = Icons.Default.Schedule,
            titleRes = R.string.playback_continue_watching_min_duration,
            subtitleRes = R.string.playback_continue_watching_min_duration_desc,
            value = continueWatchingMinMinutes,
            range = 5..90,
            discreteValues = continueWatchingMinDurationOptions,
            valueFormatter = { "$it min" },
            visible = continueWatchingEnabled,
            onValueChange = {
                continueWatchingMinMinutes = it
                prefs.edit { putInt("continue_watching_min_duration_minutes", it) }
            }
        ),
        PlaybackSettingItem.Switch(
            icon = Icons.Default.Lock,
            titleRes = R.string.playback_continue_watching_private,
            subtitleRes = R.string.playback_continue_watching_private_desc,
            checked = continueWatchingIncludePrivate,
            visible = continueWatchingEnabled,
            onCheckedChange = {
                continueWatchingIncludePrivate = it
                prefs.edit { putBoolean("continue_watching_include_private", it) }
            }
        )
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
            items(playbackSettingsItems) { settingItem ->
                AnimatedVisibility(
                    visible = settingItem.visible,
                    enter = fadeIn(animationSpec = tween(180)) + expandVertically(animationSpec = tween(220)),
                    exit = fadeOut(animationSpec = tween(120)) + shrinkVertically(animationSpec = tween(180))
                ) {
                    RenderPlaybackSettingItem(settingItem, isCompact)
                }
            }
        }
    }
}

@Composable
private fun RenderPlaybackSettingItem(
    item: PlaybackSettingItem,
    isCompact: Boolean
) {
    when (item) {
        is PlaybackSettingItem.Section -> {
            SettingsSectionHeader(stringResource(item.titleRes), isCompact)
        }

        is PlaybackSettingItem.Switch -> {
            SettingsSwitchItem(
                icon = item.icon,
                title = stringResource(item.titleRes),
                subtitle = stringResource(item.subtitleRes),
                checked = item.checked,
                onCheckedChange = item.onCheckedChange,
                enabled = item.enabled,
                isCompact = isCompact
            )
        }

        is PlaybackSettingItem.Slider -> {
            SettingsSliderItem(
                icon = item.icon,
                title = stringResource(item.titleRes),
                subtitle = stringResource(item.subtitleRes),
                value = item.value,
                range = item.range,
                step = item.step,
                discreteValues = item.discreteValues,
                onValueChange = item.onValueChange,
                valueFormatter = item.valueFormatter,
                enabled = item.enabled,
                isCompact = isCompact
            )
        }
    }
}

@Composable
fun InterfaceSettingsScreen(themeManager: ThemeManager) {
    val currentTheme by themeManager.themeMode.collectAsState()
    val currentLanguage by LanguageManager.currentLanguage.collectAsState()

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
        "hi" to stringResource(R.string.language_hindi),
        "zh" to stringResource(R.string.language_chinese),
        "zh-TW" to stringResource(R.string.language_chinese_traditional)
    )
    val interfaceSettingsItems = listOf(
        InterfaceSettingItem.Section(R.string.settings_interface),
        InterfaceSettingItem.Dropdown(
            icon = Icons.Default.Palette,
            titleRes = R.string.settings_dark_mode,
            subtitleRes = R.string.settings_dark_mode_desc,
            options = darkModeOptions,
            selectedValue = currentTheme,
            onValueChange = { newTheme ->
                themeManager.updateTheme(newTheme)
            }
        ),
        InterfaceSettingItem.Section(R.string.settings_language),
        InterfaceSettingItem.Dropdown(
            icon = Icons.Default.Language,
            titleRes = R.string.settings_app_language,
            subtitleRes = R.string.settings_app_language_desc,
            options = languageOptions,
            selectedValue = currentLanguage,
            onValueChange = { newLanguage ->
                LanguageManager.updateLanguage(newLanguage)
            }
        )
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
            items(interfaceSettingsItems) { settingItem ->
                AnimatedVisibility(
                    visible = settingItem.visible,
                    enter = fadeIn(animationSpec = tween(180)) + expandVertically(animationSpec = tween(220)),
                    exit = fadeOut(animationSpec = tween(120)) + shrinkVertically(animationSpec = tween(180))
                ) {
                    RenderInterfaceSettingItem(settingItem, isCompact)
                }
            }
        }
    }
}

@Composable
private fun RenderInterfaceSettingItem(
    item: InterfaceSettingItem,
    isCompact: Boolean
) {
    when (item) {
        is InterfaceSettingItem.Section -> {
            SettingsSectionHeader(stringResource(item.titleRes), isCompact)
        }

        is InterfaceSettingItem.Dropdown -> {
            SettingsDropdownItem(
                icon = item.icon,
                title = stringResource(item.titleRes),
                subtitle = stringResource(item.subtitleRes),
                options = item.options,
                selectedValue = item.selectedValue,
                onValueChange = item.onValueChange,
                isCompact = isCompact
            )
        }
    }
}

@Composable
fun DisplaySettingsScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("nekovideo_settings", Context.MODE_PRIVATE) }

    var showDurations by remember { mutableStateOf(prefs.getBoolean("show_durations", true)) }
    var showFileSizes by remember { mutableStateOf(prefs.getBoolean("show_file_sizes", false)) }
    val displaySettingsItems = listOf(
        DisplaySettingItem.Section(R.string.display_video_info),
        DisplaySettingItem.Switch(
            icon = Icons.Default.Schedule,
            titleRes = R.string.display_show_durations,
            subtitleRes = R.string.display_show_durations_desc,
            checked = showDurations,
            onCheckedChange = {
                showDurations = it
                prefs.edit { putBoolean("show_durations", it) }
            }
        ),
        DisplaySettingItem.Switch(
            icon = Icons.Default.Storage,
            titleRes = R.string.display_show_file_sizes,
            subtitleRes = R.string.display_show_file_sizes_desc,
            checked = showFileSizes,
            onCheckedChange = {
                showFileSizes = it
                prefs.edit { putBoolean("show_file_sizes", it) }
            }
        )
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
            items(displaySettingsItems) { settingItem ->
                AnimatedVisibility(
                    visible = settingItem.visible,
                    enter = fadeIn(animationSpec = tween(180)) + expandVertically(animationSpec = tween(220)),
                    exit = fadeOut(animationSpec = tween(120)) + shrinkVertically(animationSpec = tween(180))
                ) {
                    RenderDisplaySettingItem(settingItem, isCompact)
                }
            }
        }
    }
}

@Composable
private fun RenderDisplaySettingItem(
    item: DisplaySettingItem,
    isCompact: Boolean
) {
    when (item) {
        is DisplaySettingItem.Section -> {
            SettingsSectionHeader(stringResource(item.titleRes), isCompact)
        }

        is DisplaySettingItem.Switch -> {
            SettingsSwitchItem(
                icon = item.icon,
                title = stringResource(item.titleRes),
                subtitle = stringResource(item.subtitleRes),
                checked = item.checked,
                onCheckedChange = item.onCheckedChange,
                enabled = item.enabled,
                isCompact = isCompact
            )
        }
    }
}

private fun formatStorageUsage(bytes: Long): String? {
    if (bytes <= 0L) return null

    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unitIndex = 0

    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }

    return if (unitIndex == 0) {
        "${bytes} ${units[unitIndex]}"
    } else {
        String.format(Locale.getDefault(), "%.1f %s", value, units[unitIndex])
    }
}


@Composable
fun StorageSettingsScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var thumbnailCacheBytes by remember { mutableStateOf(0L) }
    var watchHistoryBytes by remember { mutableStateOf(0L) }

    fun updateStorageUsage() {
        coroutineScope.launch {
            val folderPaths = FolderVideoScanner.cache.value.keys
            val thumbnailBytes = withContext(Dispatchers.IO) {
                OptimizedThumbnailManager.getDiskCacheSize(context, folderPaths)
            }
            val historyBytes = withContext(Dispatchers.IO) {
                ContinueWatchingStore.storageBytes(context) + VideoProgressStore.storageBytes(context)
            }
            thumbnailCacheBytes = thumbnailBytes
            watchHistoryBytes = historyBytes
        }
    }

    LaunchedEffect(Unit) {
        val folderPaths = FolderVideoScanner.cache.value.keys
        thumbnailCacheBytes = withContext(Dispatchers.IO) {
            OptimizedThumbnailManager.getDiskCacheSize(context, folderPaths)
        }
        watchHistoryBytes = withContext(Dispatchers.IO) {
            ContinueWatchingStore.storageBytes(context) + VideoProgressStore.storageBytes(context)
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
                SettingsSectionHeader(stringResource(R.string.storage_thumbnails_section), isCompact)
            }

            item {
                SettingsClickableItem(
                    icon = Icons.Default.Image,
                    title = stringResource(R.string.storage_clear_thumbnails),
                    subtitle = formatStorageUsage(thumbnailCacheBytes),
                    onClick = {
                        coroutineScope.launch {
                            val folderPaths = FolderVideoScanner.cache.value.keys
                            withContext(Dispatchers.IO) {
                                OptimizedThumbnailManager.clearCache()
                                OptimizedThumbnailManager.clearAllDiskThumbnails(context, folderPaths)
                            }
                            thumbnailCacheBytes = 0L
                            updateStorageUsage()
                        }
                        SortRowMessageCenter.showSuccess(context.getString(R.string.storage_clear_thumbnails_success))
                    },
                    isCompact = isCompact
                )
            }

            item {
                SettingsClickableItem(
                    icon = Icons.Default.Schedule,
                    title = stringResource(R.string.storage_clear_continue_watching),
                    subtitle = formatStorageUsage(watchHistoryBytes),
                    onClick = {
                        ContinueWatchingStore.clear(context)
                        VideoProgressStore.clearAll(context)
                        watchHistoryBytes = 0L
                        updateStorageUsage()
                        SortRowMessageCenter.showSuccess(
                            context.getString(R.string.storage_clear_continue_watching_success)
                        )
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
                    SettingsClickableItem(
                        icon = Icons.Default.Lock,
                        title = stringResource(R.string.tags_private_locked_title),
                        subtitle = if (hasPassword) {
                            stringResource(R.string.tags_private_locked_desc)
                        } else {
                            stringResource(R.string.tags_private_password_required)
                        },
                        onClick = {
                            if (hasPassword) {
                                showPrivatePasswordDialog = true
                            } else {
                                SortRowMessageCenter.showInfo(context.getString(R.string.tags_private_password_required))
                            }
                        },
                        isCompact = isCompact
                    )
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

            if (shouldOfferImport) {
                item { SettingsSectionHeader(stringResource(R.string.tags_backup_section), isCompact) }
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

    val securitySettingsItems = listOf(
        SecuritySettingItem.Section(R.string.change_password_section),
        SecuritySettingItem.Action(
            icon = Icons.Default.Lock,
            titleRes = R.string.change_password,
            subtitleRes = if (hasPassword) R.string.change_password_desc else R.string.biometric_no_password,
            enabled = hasPassword,
            onClick = { showChangePasswordDialog = true }
        ),
        SecuritySettingItem.Section(R.string.biometric_section),
        SecuritySettingItem.Info(
            icon = Icons.Default.Lock,
            messageRes = R.string.biometric_no_password,
            visible = !hasPassword
        ),
        SecuritySettingItem.Info(
            icon = Icons.Default.Fingerprint,
            messageRes = R.string.biometric_not_available,
            visible = hasPassword && !biometricAvailable
        ),
        SecuritySettingItem.Switch(
            icon = Icons.Default.Fingerprint,
            titleRes = R.string.biometric_unlock,
            subtitleRes = R.string.biometric_unlock_desc,
            checked = biometricEnabled,
            visible = hasPassword && biometricAvailable,
            onCheckedChange = { enabled ->
                pendingAction = if (enabled) "enable" else "disable"
                showPasswordConfirmDialog = true
            }
        )
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
            items(securitySettingsItems) { settingItem ->
                AnimatedVisibility(
                    visible = settingItem.visible,
                    enter = fadeIn(animationSpec = tween(180)) + expandVertically(animationSpec = tween(220)),
                    exit = fadeOut(animationSpec = tween(120)) + shrinkVertically(animationSpec = tween(180))
                ) {
                    RenderSecuritySettingItem(settingItem, isCompact)
                }
            }
        }
    }
}

@Composable
private fun RenderSecuritySettingItem(
    item: SecuritySettingItem,
    isCompact: Boolean
) {
    when (item) {
        is SecuritySettingItem.Section -> {
            SettingsSectionHeader(stringResource(item.titleRes), isCompact)
        }

        is SecuritySettingItem.Action -> {
            SettingsClickableItem(
                icon = item.icon,
                title = stringResource(item.titleRes),
                subtitle = stringResource(item.subtitleRes),
                enabled = item.enabled,
                onClick = item.onClick,
                isCompact = isCompact
            )
        }

        is SecuritySettingItem.Switch -> {
            SettingsSwitchItem(
                icon = item.icon,
                title = stringResource(item.titleRes),
                subtitle = stringResource(item.subtitleRes),
                checked = item.checked,
                onCheckedChange = item.onCheckedChange,
                enabled = item.enabled,
                isCompact = isCompact
            )
        }

        is SecuritySettingItem.Info -> {
            SettingsInfoItem(
                icon = item.icon,
                message = stringResource(item.messageRes),
                isCompact = isCompact
            )
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

        SettingsClickableItem(
            icon = Icons.Default.BugReport,
            title = stringResource(R.string.about_report_bug),
            subtitle = stringResource(R.string.about_report_bug_desc),
            onClick = { openBugReport() }
        )
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
    var isExpanded by remember(scope) { mutableStateOf(false) }
    var showCreateSheet by remember(scope) { mutableStateOf(false) }
    var renameTarget by remember(scope) { mutableStateOf<TagEntity?>(null) }
    var deleteTarget by remember(scope) { mutableStateOf<TagEntity?>(null) }
    var expandedMenuTagId by remember(scope) { mutableStateOf<Long?>(null) }
    val coroutineScope = rememberCoroutineScope()

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

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.tags_delete_confirm_title)) },
            text = { Text(stringResource(R.string.tags_delete_confirm_message, target.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        coroutineScope.launch {
                            onDeleteTag(target.id)
                            deleteTarget = null
                        }
                    }
                ) {
                    Text(
                        text = stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
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
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.LocalOffer,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(if (isCompact) 20.dp else 24.dp)
                )
                Spacer(modifier = Modifier.width(if (isCompact) 10.dp else 12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.tags_list_title),
                        style = if (isCompact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = stringResource(R.string.tags_count, tags.size),
                        style = if (isCompact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = fadeIn(animationSpec = tween(180)) + expandVertically(animationSpec = tween(220)),
                exit = fadeOut(animationSpec = tween(120)) + shrinkVertically(animationSpec = tween(180))
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { showCreateSheet = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.LocalOffer,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.video_tags_create))
                    }

                    if (tags.isEmpty()) {
                        Text(
                            text = stringResource(R.string.tags_empty_scope),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = if (isCompact) 280.dp else 360.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(tags, key = { it.id }) { tag ->
                                TagManagementRow(
                                    tag = tag,
                                    isMenuExpanded = expandedMenuTagId == tag.id,
                                    onMenuClick = { expandedMenuTagId = tag.id },
                                    onDismissMenu = { expandedMenuTagId = null },
                                    onRenameClick = {
                                        expandedMenuTagId = null
                                        renameTarget = tag
                                    },
                                    onDeleteClick = {
                                        expandedMenuTagId = null
                                        deleteTarget = tag
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TagManagementRow(
    tag: TagEntity,
    isMenuExpanded: Boolean,
    onMenuClick: () -> Unit,
    onDismissMenu: () -> Unit,
    onRenameClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f),
                RoundedCornerShape(8.dp)
            )
            .padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.LocalOffer,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = tag.name,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium
        )
        Box {
            IconButton(onClick = onMenuClick) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.player_more_actions)
                )
            }
            DropdownMenu(
                expanded = isMenuExpanded,
                onDismissRequest = onDismissMenu
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_rename)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = null
                        )
                    },
                    onClick = onRenameClick
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(R.string.action_delete),
                            color = MaterialTheme.colorScheme.error
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    },
                    onClick = onDeleteClick
                )
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
    enabled: Boolean = true,
    isCompact: Boolean = false
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.6f),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { onCheckedChange(!checked) }
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
                AnimatedVisibility(
                    visible = checked,
                    enter = fadeIn(animationSpec = tween(180)) + expandVertically(animationSpec = tween(220)),
                    exit = fadeOut(animationSpec = tween(120)) + shrinkVertically(animationSpec = tween(180))
                ) {
                    Text(
                        text = subtitle,
                        style = if (isCompact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Switch(
                checked = checked,
                onCheckedChange = if (enabled) onCheckedChange else null,
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
    discreteValues: List<Int>? = null,
    onValueChange: (Int) -> Unit,
    valueFormatter: (Int) -> String = { currentValue ->
        if (title.contains("Cache")) "${currentValue}MB" else currentValue.toString()
    },
    enabled: Boolean = true,
    isCompact: Boolean = false
) {
    fun nearestDiscreteIndex(currentValue: Int): Int {
        val values = discreteValues ?: return currentValue
        return values.indices.minByOrNull { index -> abs(values[index] - currentValue) } ?: 0
    }

    var sliderValue by remember(value, discreteValues) {
        mutableIntStateOf(discreteValues?.get(nearestDiscreteIndex(value)) ?: value)
    }
    var sliderPosition by remember(value, discreteValues) {
        mutableIntStateOf(discreteValues?.let { nearestDiscreteIndex(value) } ?: value)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.6f),
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
                    text = valueFormatter(sliderValue),
                    style = if (isCompact) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(if (isCompact) 4.dp else 8.dp))

            Slider(
                value = sliderPosition.toFloat(),
                enabled = enabled,
                onValueChange = {
                    val newValue = discreteValues?.get(it.toInt().coerceIn(0, discreteValues.lastIndex))
                        ?: ((it.toInt() / step) * step)
                    sliderPosition = discreteValues?.indexOf(newValue) ?: newValue
                    sliderValue = newValue
                    onValueChange(newValue)
                },
                valueRange = if (discreteValues != null) {
                    0f..discreteValues.lastIndex.toFloat()
                } else {
                    range.first.toFloat()..range.last.toFloat()
                },
                steps = discreteValues?.let { (it.size - 2).coerceAtLeast(0) }
                    ?: ((range.last - range.first) / step - 1),
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
    subtitle: String?,
    enabled: Boolean = true,
    onClick: () -> Unit,
    isCompact: Boolean = false
) {
    val coroutineScope = rememberCoroutineScope()
    var showClickFeedback by remember { mutableStateOf(false) }
    val feedbackIconScale by animateFloatAsState(
        targetValue = if (showClickFeedback) 1.18f else 1f,
        animationSpec = tween(160),
        label = "settingsActionFeedbackScale"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.6f),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) {
                    showClickFeedback = true
                    coroutineScope.launch {
                        delay(650)
                        showClickFeedback = false
                    }
                    onClick()
                }
                .padding(if (isCompact) 10.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(if (isCompact) 20.dp else 24.dp)
            )

            Spacer(modifier = Modifier.width(if (isCompact) 10.dp else 16.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                text = title,
                style = if (isCompact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                )
                AnimatedVisibility(
                    visible = !subtitle.isNullOrBlank(),
                    enter = fadeIn(animationSpec = tween(180)) + expandVertically(animationSpec = tween(220)),
                    exit = fadeOut(animationSpec = tween(120)) + shrinkVertically(animationSpec = tween(180))
                ) {
                    Text(
                        text = subtitle.orEmpty(),
                        style = if (isCompact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Icon(
                imageVector = if (showClickFeedback) Icons.Default.Check else Icons.Default.ChevronRight,
                contentDescription = null,
                tint = if (showClickFeedback) {
                    Color(0xFF2E7D32)
                } else {
                    if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                },
                modifier = Modifier
                    .size(20.dp)
                    .scale(feedbackIconScale)
            )
        }
    }
}

@Composable
private fun SettingsInfoItem(
    icon: ImageVector,
    message: String,
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
                .padding(if (isCompact) 10.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (isCompact) 10.dp else 12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(if (isCompact) 20.dp else 24.dp)
            )
            Text(
                text = message,
                style = if (isCompact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

object SettingsManager {

    fun getSubtitleSizeLevel(context: Context): Int {
        return context.getSharedPreferences("nekovideo_settings", Context.MODE_PRIVATE)
            .getInt("subtitle_size_level", 1)
            .coerceIn(0, 2)
    }

    fun setSubtitleSizeLevel(context: Context, level: Int) {
        context.getSharedPreferences("nekovideo_settings", Context.MODE_PRIVATE)
            .edit { putInt("subtitle_size_level", level.coerceIn(0, 2)) }
    }

    fun getDoubleTapSeek(context: Context): Int {
        return context.getSharedPreferences("nekovideo_settings", Context.MODE_PRIVATE)
            .getInt("double_tap_seek", 10)
    }

    fun isDragSeekEnabled(context: Context): Boolean {
        return context.getSharedPreferences("nekovideo_settings", Context.MODE_PRIVATE)
            .getBoolean("drag_seek_enabled", true)
    }

    fun areVolumeBrightnessGesturesEnabled(context: Context): Boolean {
        return context.getSharedPreferences("nekovideo_settings", Context.MODE_PRIVATE)
            .getBoolean("volume_brightness_gestures_enabled", true)
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
