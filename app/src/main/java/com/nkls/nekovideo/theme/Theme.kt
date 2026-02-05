package com.nkls.nekovideo.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val LocalThemeManager = staticCompositionLocalOf<ThemeManager?> { null }

@Composable
fun NekoVideoTheme(
    themeManager: ThemeManager? = null,
    content: @Composable () -> Unit
) {
    val isDarkMode = themeManager?.shouldUseDarkTheme() ?: true

    val colorScheme = if (isDarkMode) {
        darkColorScheme(
            primary = Color(0xFF1A73E8), // Azul suave para destaques
            background = Color(0xFF121212),
            surface = Color(0xFF121212), // Fundo escuro
            surfaceVariant = Color(0xFF2C2C2C), // Fundo secundário
            onSurface = Color(0xFFE0E0E0), // Texto e ícones claros
            onPrimary = Color.White // Texto sobre elementos primários
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF1A73E8), // Mesma cor azul mantida
            background = Color(0xFFF5F5F5),
            surface = Color(0xFFFFFBFE), // Fundo claro
            surfaceVariant = Color(0xFFF3F3F3), // Fundo secundário claro
            onSurface = Color(0xFF1C1B1F), // Texto escuro
            onPrimary = Color.White // Texto sobre elementos primários
        )
    }

    CompositionLocalProvider(LocalThemeManager provides themeManager) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography(
                bodyLarge = TextStyle(
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp
                ),
                bodyMedium = TextStyle(
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Normal,
                    fontSize = 14.sp
                ),
                labelSmall = TextStyle(
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Light,
                    fontSize = 12.sp
                )
            ),
            shapes = Shapes(
                small = RoundedCornerShape(8.dp),
                medium = RoundedCornerShape(12.dp),
                large = RoundedCornerShape(16.dp)
            )
        ) {
            content()
        }
    }
}