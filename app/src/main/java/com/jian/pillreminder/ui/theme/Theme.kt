package com.jian.pillreminder.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// 回退配色（Android 11 及以下没有动态取色时使用）。与桌面图标同一套鼠尾草绿（淡雅、低饱和），贴近医疗/健康语义。
private val SeedLight = lightColorScheme(
    primary = Color(0xFF33705A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB6EDD2),
    onPrimaryContainer = Color(0xFF00281A),
    secondary = Color(0xFF4E6357),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD0E8D9),
    onSecondaryContainer = Color(0xFF0B1F16),
    tertiary = Color(0xFF3A6572),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFBEEAFA),
    onTertiaryContainer = Color(0xFF001F28),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF8FBF7),
    onBackground = Color(0xFF191C1A),
    surface = Color(0xFFF8FBF7),
    onSurface = Color(0xFF191C1A),
    surfaceVariant = Color(0xFFDBE5DD),
    onSurfaceVariant = Color(0xFF404943),
    outline = Color(0xFF707973),
    outlineVariant = Color(0xFFBFC9C2)
)

private val SeedDark = darkColorScheme(
    primary = Color(0xFF9AD4B7),
    onPrimary = Color(0xFF00392A),
    primaryContainer = Color(0xFF1A5342),
    onPrimaryContainer = Color(0xFFB6EDD2),
    secondary = Color(0xFFB4CCBE),
    onSecondary = Color(0xFF20352B),
    secondaryContainer = Color(0xFF364B40),
    onSecondaryContainer = Color(0xFFD0E8D9),
    tertiary = Color(0xFFA2CEDD),
    onTertiary = Color(0xFF013642),
    tertiaryContainer = Color(0xFF204D59),
    onTertiaryContainer = Color(0xFFBEEAFA),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF111412),
    onBackground = Color(0xFFE1E3DF),
    surface = Color(0xFF111412),
    onSurface = Color(0xFFE1E3DF),
    surfaceVariant = Color(0xFF404943),
    onSurfaceVariant = Color(0xFFBFC9C2),
    outline = Color(0xFF8A938C),
    outlineVariant = Color(0xFF404943)
)

/** 药品卡片可选配色：(浅色容器, 浅色前景, 深色容器, 深色前景)。 */
data class MedColor(val name: String, val light: Color, val onLight: Color, val dark: Color, val onDark: Color) {
    @Composable
    fun container(): Color = if (isSystemInDarkTheme()) dark else light

    @Composable
    fun content(): Color = if (isSystemInDarkTheme()) onDark else onLight
}

val MedColors = listOf(
    MedColor("鼠尾草", Color(0xFFB6EDD2), Color(0xFF00281A), Color(0xFF1A5342), Color(0xFFB6EDD2)),
    MedColor("蓝",   Color(0xFFD3E3FD), Color(0xFF0B305F), Color(0xFF1B3A62), Color(0xFFD3E3FD)),
    MedColor("紫",   Color(0xFFE8DEF8), Color(0xFF32275A), Color(0xFF423866), Color(0xFFE8DEF8)),
    MedColor("粉",   Color(0xFFFFD8E4), Color(0xFF5C1133), Color(0xFF633B48), Color(0xFFFFD8E4)),
    MedColor("橙",   Color(0xFFFFDCC2), Color(0xFF5A2E00), Color(0xFF5D3F26), Color(0xFFFFDCC2)),
    MedColor("黄",   Color(0xFFFDF0C0), Color(0xFF4F4300), Color(0xFF4F4300), Color(0xFFFDF0C0)),
    MedColor("绿",   Color(0xFFC8E6C9), Color(0xFF19391B), Color(0xFF2D4A2F), Color(0xFFC8E6C9)),
    MedColor("灰蓝", Color(0xFFDDE3EA), Color(0xFF2A3138), Color(0xFF3A4148), Color(0xFFDDE3EA))
)

fun medColorAt(index: Int): MedColor = MedColors[index.mod(MedColors.size)]

@Composable
fun PillReminderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /** Android 12+ 跟随手机壁纸取色，这就是 Pixel 原生的观感。 */
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> SeedDark
        else -> SeedLight
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = PillTypography,
        shapes = PillShapes,
        content = content
    )
}
