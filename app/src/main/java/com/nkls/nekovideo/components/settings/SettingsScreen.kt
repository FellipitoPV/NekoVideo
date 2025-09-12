package com.nkls.nekovideo.components.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.ScreenSearchDesktop
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.ViewList
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.nkls.nekovideo.components.OptimizedThumbnailManager
import com.nkls.nekovideo.language.LanguageManager
import com.nkls.nekovideo.theme.ThemeManager

@Composable
fun SettingsScreen(navController: NavController) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        item {
            SettingsCategoryCard(
                icon = Icons.Default.PlayArrow,
                title = stringResource(R.string.settings_playback),
                subtitle = stringResource(R.string.settings_playback_desc),
                onClick = { navController.navigate("settings/playback") }
            )
        }

        item {
            SettingsCategoryCard(
                icon = Icons.Default.Palette,
                title = stringResource(R.string.settings_interface),
                subtitle = stringResource(R.string.settings_interface_desc),
                onClick = { navController.navigate("settings/interface") }
            )
        }

        item {
            SettingsCategoryCard(
                icon = Icons.AutoMirrored.Filled.ViewList,
                title = stringResource(R.string.settings_display),
                subtitle = stringResource(R.string.settings_display_desc),
                onClick = { navController.navigate("settings/display") }
            )
        }

        item {
            SettingsCategoryCard(
                icon = Icons.Default.Folder,
                title = stringResource(R.string.settings_files),
                subtitle = stringResource(R.string.settings_files_desc),
                onClick = { navController.navigate("settings/files") }
            )
        }

        item {
            SettingsCategoryCard(
                icon = Icons.Default.Info,
                title = stringResource(R.string.settings_about),
                subtitle = stringResource(R.string.settings_about_desc),
                onClick = { navController.navigate("settings/about") }
            )
        }
    }
}

@Composable
fun PlaybackSettingsScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("nekovideo_settings", Context.MODE_PRIVATE) }

    var keepScreenOn by remember { mutableStateOf(prefs.getBoolean("keep_screen_on", true)) }
    var pipEnabled by remember { mutableStateOf(prefs.getBoolean("pip_enabled", false)) }
    var autoHideControls by remember { mutableStateOf(prefs.getBoolean("auto_hide_controls", true)) }
    var doubleTapSeek by remember { mutableStateOf(prefs.getInt("double_tap_seek", 10)) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        item {
            SettingsSectionHeader(stringResource(R.string.playback_controls))
        }

        item {
            SettingsSwitchItem(
                icon = Icons.Default.VisibilityOff,
                title = stringResource(R.string.playback_auto_hide_controls),
                subtitle = stringResource(R.string.playback_auto_hide_controls_desc),
                checked = autoHideControls,
                onCheckedChange = {
                    autoHideControls = it
                    prefs.edit().putBoolean("auto_hide_controls", it).apply()
                }
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
                    prefs.edit().putInt("double_tap_seek", it).apply()
                }
            )
        }

        item {
            SettingsSectionHeader(stringResource(R.string.playback_features))
        }

        item {
            SettingsSwitchItem(
                icon = Icons.Default.ScreenSearchDesktop,
                title = stringResource(R.string.playback_keep_screen_on),
                subtitle = stringResource(R.string.playback_keep_screen_on_desc),
                checked = keepScreenOn,
                onCheckedChange = {
                    keepScreenOn = it
                    prefs.edit().putBoolean("keep_screen_on", it).apply()
                }
            )
        }

        item {
            SettingsSwitchItem(
                icon = Icons.Default.PictureInPictureAlt,
                title = stringResource(R.string.playback_pip),
                subtitle = stringResource(R.string.playback_pip_desc),
                checked = pipEnabled,
                onCheckedChange = {
                    pipEnabled = it
                    prefs.edit().putBoolean("pip_enabled", it).apply()
                }
            )
        }
    }
}

@Composable
fun InterfaceSettingsScreen(themeManager: ThemeManager) {
    val context = LocalContext.current
    val currentTheme by themeManager.themeMode.collectAsState()

    // ✅ Observar mudanças do LanguageManager
    val languageStateFlow by LanguageManager.currentLanguage.collectAsState()

    // ✅ Variável local que recompõe quando StateFlow muda
    var currentLanguage by remember(languageStateFlow) {
        mutableStateOf(LanguageManager.getCurrentLanguage(context))
    }

    // Strings traduzidas
    val darkModeOptions = listOf(
        "light" to stringResource(R.string.theme_light),
        "dark" to stringResource(R.string.theme_dark),
        "system" to stringResource(R.string.theme_system)
    )

    // Opções de idioma
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

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {


        item {
            SettingsSectionHeader(stringResource(R.string.settings_appearance))
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
                }
            )
        }

        item {
            SettingsSectionHeader(stringResource(R.string.settings_language))
        }

        item {
            SettingsDropdownItem(
                icon = Icons.Default.Language,
                title = stringResource(R.string.settings_app_language),
                subtitle = stringResource(R.string.settings_app_language_desc),
                options = languageOptions,
                selectedValue = currentLanguage,
                onValueChange = { newLanguage ->
                    currentLanguage = newLanguage
                    LanguageManager.updateLanguage(context, newLanguage)

                    // ✅ TOAST SIMPLES EM INGLÊS
                    Toast.makeText(context, "Language will be applied after app restart", Toast.LENGTH_SHORT).show()
                }
            )
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
    var thumbnailQuality by remember { mutableStateOf(prefs.getString("thumbnail_quality", "medium") ?: "medium") }
    var gridColumns by remember { mutableStateOf(prefs.getInt("grid_columns", 3)) }

    val qualityOptions = listOf(
        "low" to stringResource(R.string.quality_low),
        "medium" to stringResource(R.string.quality_medium),
        "high" to stringResource(R.string.quality_high),
        "original" to stringResource(R.string.quality_original)
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        item {
            SettingsSectionHeader(stringResource(R.string.display_video_info))
        }

        item {
            SettingsSwitchItem(
                icon = Icons.Default.Image,
                title = stringResource(R.string.display_show_thumbnails),
                subtitle = stringResource(R.string.display_show_thumbnails_desc),
                checked = showThumbnails,
                onCheckedChange = {
                    showThumbnails = it
                    prefs.edit().putBoolean("show_thumbnails", it).apply()
                }
            )
        }

        item {
            SettingsSwitchItem(
                icon = Icons.Default.Schedule,
                title = stringResource(R.string.display_show_durations),
                subtitle = stringResource(R.string.display_show_durations_desc),
                checked = showDurations,
                onCheckedChange = {
                    showDurations = it
                    prefs.edit().putBoolean("show_durations", it).apply()
                }
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
                    prefs.edit().putBoolean("show_file_sizes", it).apply()
                }
            )
        }

        item {
            SettingsSectionHeader(stringResource(R.string.display_layout))
        }

        item {
            SettingsSliderItem(
                icon = Icons.Default.GridView,
                title = stringResource(R.string.display_grid_columns),
                subtitle = stringResource(R.string.display_grid_columns_desc),
                value = gridColumns,
                range = 2..4,
                onValueChange = {
                    gridColumns = it
                    prefs.edit().putInt("grid_columns", it).apply()
                }
            )
        }

        item {
            SettingsSectionHeader(stringResource(R.string.display_quality))
        }

        item {
            SettingsDropdownItem(
                icon = Icons.Default.HighQuality,
                title = stringResource(R.string.display_thumbnail_quality),
                subtitle = stringResource(R.string.display_thumbnail_quality_desc),
                options = qualityOptions,
                selectedValue = thumbnailQuality,
                onValueChange = {
                    thumbnailQuality = it
                    prefs.edit().putString("thumbnail_quality", it).apply()
                }
            )
        }
    }
}

@Composable
fun FilesSettingsScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("nekovideo_settings", Context.MODE_PRIVATE) }

    var appOnlyFolders by remember { mutableStateOf(prefs.getBoolean("app_only_folders", false)) }
    var confirmDelete by remember { mutableStateOf(prefs.getBoolean("confirm_delete", true)) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        item {
            SettingsSectionHeader(stringResource(R.string.files_management))
        }

        item {
            SettingsSwitchItem(
                icon = Icons.Default.Folder,
                title = stringResource(R.string.files_app_only_folders),
                subtitle = stringResource(R.string.files_app_only_folders_desc),
                checked = appOnlyFolders,
                onCheckedChange = {
                    appOnlyFolders = it
                    prefs.edit().putBoolean("app_only_folders", it).apply()
                }
            )
        }

        item {
            SettingsSwitchItem(
                icon = Icons.Default.Delete,
                title = stringResource(R.string.files_confirm_delete),
                subtitle = stringResource(R.string.files_confirm_delete_desc),
                checked = confirmDelete,
                onCheckedChange = {
                    confirmDelete = it
                    prefs.edit().putBoolean("confirm_delete", it).apply()
                }
            )
        }
    }
}

@Composable
fun AboutSettingsScreen() {
    val context = LocalContext.current

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        item {
            SettingsSectionHeader(stringResource(R.string.about_information))
        }

        item {
            SettingsClickableItem(
                icon = Icons.Default.Info,
                title = stringResource(R.string.about_app_version),
                subtitle = BuildConfig.VERSION_NAME,
                onClick = { /* TODO: Show version details */ }
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
                Descreva o problema encontrado:
                
                
                
                --- Informações do Sistema ---
                App Version: $appVersion
                Device: $deviceInfo
            """.trimIndent()

                    // Método 1: Tenta com mailto direto
                    val mailtoUri = Uri.parse("mailto:nklssuport@gmail.com\n?subject=${Uri.encode("Bug Report - ${context.getString(R.string.app_name)}")}&body=${Uri.encode(emailBody)}")
                    val mailtoIntent = Intent(Intent.ACTION_VIEW, mailtoUri)

                    Log.d("EmailIntent", "Tentando mailto direto...")
                    if (mailtoIntent.resolveActivity(context.packageManager) != null) {
                        Log.d("EmailIntent", "Mailto funcionou")
                        context.startActivity(mailtoIntent)
                        return@SettingsClickableItem
                    }

                    // Método 2: Intent específico para email
                    val emailIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "message/rfc822"
                        putExtra(Intent.EXTRA_EMAIL, arrayOf("suporte@seuapp.com"))
                        putExtra(Intent.EXTRA_SUBJECT, "Bug Report - ${context.getString(R.string.app_name)}")
                        putExtra(Intent.EXTRA_TEXT, emailBody)
                    }

                    Log.d("EmailIntent", "Tentando com message/rfc822...")
                    if (emailIntent.resolveActivity(context.packageManager) != null) {
                        Log.d("EmailIntent", "RFC822 funcionou")
                        context.startActivity(emailIntent)
                        return@SettingsClickableItem
                    }

                    Log.e("EmailIntent", "Nenhum método funcionou")
                }
            )
        }
    }
}

@Composable
private fun SettingsCategoryCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "Abrir",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
private fun SettingsSwitchItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
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
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
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
    onValueChange: (String) -> Unit
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
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )

                Spacer(modifier = Modifier.width(16.dp))

                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = selectedOption,
                        style = MaterialTheme.typography.bodySmall,
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
    onValueChange: (Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )

                Spacer(modifier = Modifier.width(16.dp))

                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = if (title.contains("Cache")) "${value}MB" else value.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

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
    onClick: () -> Unit
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
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
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

// SettingsManager permanece igual
object SettingsManager {
    fun getShowThumbnails(context: Context): Boolean {
        return context.getSharedPreferences("nekovideo_settings", Context.MODE_PRIVATE)
            .getBoolean("show_thumbnails", true)
    }

    fun getKeepScreenOn(context: Context): Boolean {
        return context.getSharedPreferences("nekovideo_settings", Context.MODE_PRIVATE)
            .getBoolean("keep_screen_on", true)
    }

    fun getPipEnabled(context: Context): Boolean {
        return context.getSharedPreferences("nekovideo_settings", Context.MODE_PRIVATE)
            .getBoolean("pip_enabled", false)
    }

    fun getShowDurations(context: Context): Boolean {
        return context.getSharedPreferences("nekovideo_settings", Context.MODE_PRIVATE)
            .getBoolean("show_durations", true)
    }

    fun getShowFileSizes(context: Context): Boolean {
        return context.getSharedPreferences("nekovideo_settings", Context.MODE_PRIVATE)
            .getBoolean("show_file_sizes", false)
    }

    fun getThumbnailQuality(context: Context): String {
        return context.getSharedPreferences("nekovideo_settings", Context.MODE_PRIVATE)
            .getString("thumbnail_quality", "medium") ?: "medium"
    }

    fun getAppOnlyFolders(context: Context): Boolean {
        return context.getSharedPreferences("nekovideo_settings", Context.MODE_PRIVATE)
            .getBoolean("app_only_folders", false)
    }

    fun getConfirmDelete(context: Context): Boolean {
        return context.getSharedPreferences("nekovideo_settings", Context.MODE_PRIVATE)
            .getBoolean("confirm_delete", true)
    }

    fun getAutoHideControls(context: Context): Boolean {
        return context.getSharedPreferences("nekovideo_settings", Context.MODE_PRIVATE)
            .getBoolean("auto_hide_controls", true)
    }

    fun getDoubleTapSeek(context: Context): Int {
        return context.getSharedPreferences("nekovideo_settings", Context.MODE_PRIVATE)
            .getInt("double_tap_seek", 10)
    }

    fun getCacheSize(context: Context): Int {
        return context.getSharedPreferences("nekovideo_settings", Context.MODE_PRIVATE)
            .getInt("cache_size_mb", 100)
    }

    fun getDarkMode(context: Context): String {
        return context.getSharedPreferences("nekovideo_settings", Context.MODE_PRIVATE)
            .getString("dark_mode", "system") ?: "system"
    }

    fun getGridColumns(context: Context): Int {
        return context.getSharedPreferences("nekovideo_settings", Context.MODE_PRIVATE)
            .getInt("grid_columns", 3)
    }
}