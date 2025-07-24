package com.example.nekovideo.components.settings

import android.content.Context
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.nekovideo.BuildConfig
import com.example.nekovideo.ui.theme.ThemeManager

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
                title = "Reprodução",
                subtitle = "Controles de playback e comportamento do player",
                onClick = { navController.navigate("settings/playback") }
            )
        }

        item {
            SettingsCategoryCard(
                icon = Icons.Default.Palette,
                title = "Interface",
                subtitle = "Aparência e tema do aplicativo",
                onClick = { navController.navigate("settings/interface") }
            )
        }

        item {
            SettingsCategoryCard(
                icon = Icons.Default.ViewList,
                title = "Visualização",
                subtitle = "Thumbnails, grid e exibição de informações",
                onClick = { navController.navigate("settings/display") }
            )
        }

        item {
            SettingsCategoryCard(
                icon = Icons.Default.Folder,
                title = "Arquivos",
                subtitle = "Gerenciamento de pastas e arquivos",
                onClick = { navController.navigate("settings/files") }
            )
        }

        item {
            SettingsCategoryCard(
                icon = Icons.Default.Speed,
                title = "Performance",
                subtitle = "Cache e otimizações de desempenho",
                onClick = { navController.navigate("settings/performance") }
            )
        }

        item {
            SettingsCategoryCard(
                icon = Icons.Default.Info,
                title = "Sobre",
                subtitle = "Informações do app e suporte",
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
            SettingsSectionHeader("Controles")
        }

        item {
            SettingsSwitchItem(
                icon = Icons.Default.VisibilityOff,
                title = "Ocultar Controles",
                subtitle = "Esconder automaticamente durante reprodução",
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
                title = "Pulo Duplo Toque",
                subtitle = "Segundos para avançar/voltar",
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
            SettingsSectionHeader("Funcionalidades")
        }

        item {
            SettingsSwitchItem(
                icon = Icons.Default.ScreenSearchDesktop,
                title = "Manter Tela Ligada",
                subtitle = "Não desligar tela durante reprodução",
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
                title = "Picture in Picture",
                subtitle = "Continuar reprodução em janela flutuante",
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
    val currentTheme by themeManager.themeMode.collectAsState()

    val darkModeOptions = listOf(
        "light" to "Claro",
        "dark" to "Escuro",
        "system" to "Seguir Sistema"
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        item {
            SettingsSectionHeader("Tema")
        }

        item {
            SettingsDropdownItem(
                icon = Icons.Default.Palette,
                title = "Modo Escuro",
                subtitle = "Aparência da interface",
                options = darkModeOptions,
                selectedValue = currentTheme,
                onValueChange = { newTheme ->
                    themeManager.updateTheme(newTheme)
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
        "low" to "Baixa (360p)",
        "medium" to "Média (720p)",
        "high" to "Alta (1080p)",
        "original" to "Original"
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        item {
            SettingsSectionHeader("Informações dos Vídeos")
        }

        item {
            SettingsSwitchItem(
                icon = Icons.Default.Image,
                title = "Mostrar Miniaturas",
                subtitle = "Exibir thumbnails dos vídeos",
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
                title = "Mostrar Durações",
                subtitle = "Exibir duração dos vídeos",
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
                title = "Mostrar Tamanhos",
                subtitle = "Exibir tamanho dos arquivos",
                checked = showFileSizes,
                onCheckedChange = {
                    showFileSizes = it
                    prefs.edit().putBoolean("show_file_sizes", it).apply()
                }
            )
        }

        item {
            SettingsSectionHeader("Layout")
        }

        item {
            SettingsSliderItem(
                icon = Icons.Default.GridView,
                title = "Colunas na Grid",
                subtitle = "Número de colunas na lista",
                value = gridColumns,
                range = 2..4,
                onValueChange = {
                    gridColumns = it
                    prefs.edit().putInt("grid_columns", it).apply()
                }
            )
        }

        item {
            SettingsSectionHeader("Qualidade")
        }

        item {
            SettingsDropdownItem(
                icon = Icons.Default.HighQuality,
                title = "Resolução da Thumbnail",
                subtitle = "Qualidade das miniaturas geradas",
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
            SettingsSectionHeader("Gerenciamento")
        }

        item {
            SettingsSwitchItem(
                icon = Icons.Default.Folder,
                title = "Pastas Exclusivas",
                subtitle = "Criar pastas visíveis apenas no NekoVideo",
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
                title = "Confirmar Exclusão",
                subtitle = "Pedir confirmação antes de deletar arquivos",
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
fun PerformanceSettingsScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("nekovideo_settings", Context.MODE_PRIVATE) }

    var cacheSize by remember { mutableStateOf(prefs.getInt("cache_size_mb", 100)) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        item {
            SettingsSectionHeader("Cache")
        }

        item {
            SettingsSliderItem(
                icon = Icons.Default.Memory,
                title = "Cache de Thumbnails",
                subtitle = "Espaço reservado para miniaturas",
                value = cacheSize,
                range = 50..500,
                step = 50,
                onValueChange = {
                    cacheSize = it
                    prefs.edit().putInt("cache_size_mb", it).apply()
                }
            )
        }
    }
}

@Composable
fun AboutSettingsScreen() {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        item {
            SettingsSectionHeader("Informações")
        }

        item {
            SettingsClickableItem(
                icon = Icons.Default.Info,
                title = "Versão do App",
                subtitle = BuildConfig.VERSION_NAME,
                onClick = { /* TODO: Show version details */ }
            )
        }

        item {
            SettingsClickableItem(
                icon = Icons.Default.Code,
                title = "Código Fonte",
                subtitle = "Ver no GitHub",
                onClick = { /* TODO: Open GitHub */ }
            )
        }

        item {
            SettingsClickableItem(
                icon = Icons.Default.BugReport,
                title = "Reportar Bug",
                subtitle = "Relatar problemas ou sugestões",
                onClick = { /* TODO: Open bug report */ }
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