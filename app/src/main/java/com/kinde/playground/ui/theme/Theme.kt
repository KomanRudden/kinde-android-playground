package com.kinde.playground.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

@Immutable
data class StatusColors(
    val pass: Color, val passBg: Color,
    val fail: Color, val failBg: Color,
    val warn: Color, val warnBg: Color,
    val muted: Color, val mutedBg: Color,
    val code: Color, val codeBg: Color,
)

private val LightStatus = StatusColors(
    pass = Color(0xFF13612E), passBg = Color(0xFFE3F4E8),
    fail = Color(0xFFA4161A), failBg = Color(0xFFFBE7E7),
    warn = Color(0xFF8A5300), warnBg = Color(0xFFFDF1DC),
    muted = Color(0xFF5B5B66), mutedBg = Color(0xFFEFEFF2),
    code = Color(0xFF1E1E24), codeBg = Color(0xFFF4F4F6),
)

private val DarkStatus = StatusColors(
    pass = Color(0xFF8EDBA6), passBg = Color(0xFF16301F),
    fail = Color(0xFFFF9C9C), failBg = Color(0xFF3A1717),
    warn = Color(0xFFFFCB7A), warnBg = Color(0xFF392A10),
    muted = Color(0xFFA7A7B2), mutedBg = Color(0xFF26262C),
    code = Color(0xFFE6E6EA), codeBg = Color(0xFF1B1B20),
)

val LocalStatus = staticCompositionLocalOf { LightStatus }

private val Light = lightColorScheme(
    primary = Color(0xFF0F0F12), onPrimary = Color.White,
    secondary = Color(0xFF3D3D47), onSecondary = Color.White,
    background = Color(0xFFFAFAFB), onBackground = Color(0xFF0F0F12),
    surface = Color.White, onSurface = Color(0xFF0F0F12),
    surfaceVariant = Color(0xFFF1F1F4), onSurfaceVariant = Color(0xFF55555F),
    outline = Color(0xFFDADAE0), outlineVariant = Color(0xFFE7E7EC),
    primaryContainer = Color(0xFFE9E9EE), onPrimaryContainer = Color(0xFF0F0F12),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFF5F5F7), onPrimary = Color(0xFF0F0F12),
    secondary = Color(0xFFC9C9D1), onSecondary = Color(0xFF0F0F12),
    background = Color(0xFF0E0E11), onBackground = Color(0xFFEDEDF0),
    surface = Color(0xFF16161A), onSurface = Color(0xFFEDEDF0),
    surfaceVariant = Color(0xFF1F1F24), onSurfaceVariant = Color(0xFFA9A9B4),
    outline = Color(0xFF34343C), outlineVariant = Color(0xFF2A2A31),
    primaryContainer = Color(0xFF26262D), onPrimaryContainer = Color(0xFFEDEDF0),
)

val Mono = FontFamily.Monospace

private val AppTypography = Typography().let { t ->
    t.copy(
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = t.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = t.labelSmall.copy(letterSpacing = 0.4.sp),
    )
}

val CodeStyle = TextStyle(fontFamily = Mono, fontSize = 12.sp, lineHeight = 17.sp)

@Composable
fun PlaygroundTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    androidx.compose.runtime.CompositionLocalProvider(LocalStatus provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, typography = AppTypography, content = content)
    }
}
