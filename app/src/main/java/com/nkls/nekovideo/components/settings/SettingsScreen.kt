package com.nkls.nekovideo.components.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.nkls.nekovideo.BuildConfig
import com.nkls.nekovideo.R
import com.nkls.nekovideo.language.LanguageManager
import com.nkls.nekovideo.theme.ThemeManager
import androidx.core.content.edit
import androidx.core.net.toUri

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
                    icon = Icons.Default.Folder,
                    title = stringResource(R.string.settings_files),
                    subtitle = stringResource(R.string.settings_files_desc),
                    onClick = { navController.navigate("settings/files") },
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

        }
    }
}

@Composable
fun PlaybackSettingsScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("nekovideo_settings", Context.MODE_PRIVATE) }

    var autoHideControls by remember { mutableStateOf(prefs.getBoolean("auto_hide_controls", true)) }
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
                        prefs.edit { putBoolean("auto_hide_controls", it) }
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
                        prefs.edit { putInt("double_tap_seek", it) }
                    },
                    isCompact = isCompact
                )
            }

//        item {
//            SettingsSectionHeader(stringResource(R.string.playback_features))
//        }
//
//        item {
//            SettingsSwitchItem(
//                icon = Icons.Default.ScreenSearchDesktop,
//                title = stringResource(R.string.playback_keep_screen_on),
//                subtitle = stringResource(R.string.playback_keep_screen_on_desc),
//                checked = keepScreenOn,
//                onCheckedChange = {
//                    keepScreenOn = it
//                    prefs.edit().putBoolean("keep_screen_on", it).apply()
//                }
//            )
//        }

//        item {
//            SettingsSwitchItem(
//                icon = Icons.Default.PictureInPictureAlt,
//                title = stringResource(R.string.playback_pip),
//                subtitle = stringResource(R.string.playback_pip_desc),
//                checked = pipEnabled,
//                onCheckedChange = {
//                    pipEnabled = it
//                    prefs.edit().putBoolean("pip_enabled", it).apply()
//                }
//            )
//        }
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
                SettingsSectionHeader(stringResource(R.string.settings_appearance), isCompact)
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

                        Toast.makeText(context, "Language will be applied after app restart", Toast.LENGTH_LONG).show()
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

    var showThumbnails by remember { mutableStateOf(prefs.getBoolean("show_thumbnails", true)) }
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
                    icon = Icons.Default.Image,
                    title = stringResource(R.string.display_show_thumbnails),
                    subtitle = stringResource(R.string.display_show_thumbnails_desc),
                    checked = showThumbnails,
                    onCheckedChange = {
                        prefs.edit { putBoolean("show_thumbnails", it) }
                    },
                    isCompact = isCompact
                )
            }

            item {
                SettingsSwitchItem(
                    icon = Icons.Default.Schedule,
                    title = stringResource(R.string.display_show_durations),
                    subtitle = stringResource(R.string.display_show_durations_desc),
                    checked = showDurations,
                    onCheckedChange = {
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
fun FilesSettingsScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("nekovideo_settings", Context.MODE_PRIVATE) }

    var appOnlyFolders by remember { mutableStateOf(prefs.getBoolean("app_only_folders", false)) }
    var confirmDelete by remember { mutableStateOf(prefs.getBoolean("confirm_delete", true)) }

    LaunchedEffect(Unit) {
        appOnlyFolders = prefs.getBoolean("app_only_folders", false)
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
                SettingsSectionHeader(stringResource(R.string.files_management), isCompact)
            }

            item {
                SettingsSwitchItem(
                    icon = Icons.Default.Folder,
                    title = stringResource(R.string.files_app_only_folders),
                    subtitle = stringResource(R.string.files_app_only_folders_desc),
                    checked = appOnlyFolders,
                    onCheckedChange = {
                        prefs.edit { putBoolean("app_only_folders", it) }
                    },
                    isCompact = isCompact
                )
            }

            item {
                SettingsSwitchItem(
                    icon = Icons.Default.Delete,
                    title = stringResource(R.string.files_confirm_delete),
                    subtitle = stringResource(R.string.files_confirm_delete_desc),
                    checked = confirmDelete,
                    onCheckedChange = {
                        prefs.edit { putBoolean("confirm_delete", it) }
                    },
                    isCompact = isCompact
                )
            }
        }
    }
}

@Composable
fun AboutSettingsScreen() {
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
                SettingsSectionHeader(stringResource(R.string.about_information), isCompact)
            }

            item {
                SettingsClickableItem(
                    icon = Icons.Default.Info,
                    title = stringResource(R.string.about_app_version),
                    subtitle = BuildConfig.VERSION_NAME,
                    onClick = { /* TODO: Show version details */ },
                    isCompact = isCompact
                )
            }

//        item {
//            SettingsClickableItem(
//                icon = Icons.Default.Code,
//                title = stringResource(R.string.about_source_code),
//                subtitle = stringResource(R.string.about_source_code_desc),
//                onClick = {
//                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/FellipitoPV/NekoVideo"))
//                    context.startActivity(intent)
//                }
//            )
//        }

            item {
                SettingsClickableItem(
                    icon = Icons.Default.BugReport,
                    title = stringResource(R.string.about_report_bug),
                    subtitle = stringResource(R.string.about_report_bug_desc),
                    onClick = {
                        val appVersion = BuildConfig.VERSION_NAME
                        val deviceInfo = "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})"
                        val emailBody = """
                Describe the issue you found:



                --- System Information ---
                App Version: $appVersion
                Device: $deviceInfo
            """.trimIndent()

                        val mailtoUri = "mailto:nklssuport@gmail.com?subject=${
                            Uri.encode(
                                "Bug Report - ${
                                    context.getString(R.string.app_name)
                                }"
                            )
                        }&body=${Uri.encode(emailBody)}".toUri()
                        val mailtoIntent = Intent(Intent.ACTION_VIEW, mailtoUri)

                        if (mailtoIntent.resolveActivity(context.packageManager) != null) {
                            context.startActivity(mailtoIntent)
                            return@SettingsClickableItem
                        }

                        val emailIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "message/rfc822"
                            putExtra(Intent.EXTRA_EMAIL, arrayOf("nklssuport@gmail.com"))
                            putExtra(Intent.EXTRA_SUBJECT, "Bug Report - ${context.getString(R.string.app_name)}")
                            putExtra(Intent.EXTRA_TEXT, emailBody)
                        }

                        if (emailIntent.resolveActivity(context.packageManager) != null) {
                            context.startActivity(emailIntent)
                            return@SettingsClickableItem
                        }

                    },
                    isCompact = isCompact
                )
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
                    text = if (title.contains("Cache")) "${value}MB" else value.toString(),
                    style = if (isCompact) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(if (isCompact) 4.dp else 8.dp))

            Slider(
                value = value.toFloat(),
                onValueChange = {
                    val newValue = (it.toInt() / step) * step
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

}
