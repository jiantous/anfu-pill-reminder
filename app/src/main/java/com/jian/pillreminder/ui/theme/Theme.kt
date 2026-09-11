package com.jian.pillreminder.ui.theme

import android.os.Build
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// M3 Expressive 弹性动画参数：比默认 spring 更"弹"，有 subtle 的过冲和回弹，
// 符合 Material 3 Expressive 的"情感化动效"理念。
// 用于打卡、进度条、选中态等需要给用户反馈感的交互。
val ExpressiveSpring = spring<Float>(
    dampingRatio = Spring.DampingRatioMediumBouncy,
    stiffness = Spring.StiffnessMedium
)

// 回退配色（Android 11 及以下没有动态取色时使用）。与桌面图标同一套鼠尾草绿（淡雅、低饱和），贴近医疗/健康语义。
// M3 Expressive 要求完整的 surfaceContainer 层级——从 lowest 到 highest，
// 替代旧的 surface/surfaceVariant 来表现抬升。
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
    background = Color(0xFFF5F5F5),
    onBackground = Color(0xFF191C1A),
    surface = Color(0xFFF5F5F5),
    onSurface = Color(0xFF191C1A),
    surfaceVariant = Color(0xFFDBE5DD),
    onSurfaceVariant = Color(0xFF404943),
    // M3 Expressive surface container 层级：从最低到最高，替代单一 surfaceVariant
    surfaceContainerLowest = Color(0xFFEFEFEF),
    surfaceContainerLow = Color(0xFFE9E9E9),
    surfaceContainer = Color(0xFFE3E3E3),
    surfaceContainerHigh = Color(0xFFDEDEDE),
    surfaceContainerHighest = Color(0xFFD9D9D9),
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
    // M3 Expressive surface container 层级
    surfaceContainerLowest = Color(0xFF0D0F0E),
    surfaceContainerLow = Color(0xFF191C1A),
    surfaceContainer = Color(0xFF1D201E),
    surfaceContainerHigh = Color(0xFF272B28),
    surfaceContainerHighest = Color(0xFF323633),
    outline = Color(0xFF8A938C),
    outlineVariant = Color(0xFF404943)
)

/** 药品卡片可选配色：(浅色容器, 浅色前景, 深色容器, 深色前景)。 */
data class MedColor(val name: String, val light: Color, val onLight: Color, val dark: Color, val onDark: Color) {
    /**
     * 容器色：Android 12+ 动态取色时向壁纸主色调和（harmonize）——
     * 色相身份保留（蓝还是蓝、粉还是粉），但色度向主题靠拢，
     * 任何壁纸下 8 个颜色都和整体观感协调（Seal 的做法）。
     * Android 11 及以下维持原色（种子色主题下本来就是配好的）。
     */
    @Composable
    fun container(): Color {
        val raw = if (isSystemInDarkTheme()) dark else light
        return harmonizeWithPrimary(raw)
    }

    @Composable
    fun content(): Color {
        val raw = if (isSystemInDarkTheme()) onDark else onLight
        return harmonizeWithPrimary(raw)
    }
}

/**
 * 把颜色向当前主题 primary 调和（Material Harmonization 的简化实现：
 * HSL 空间把色相转到主色方向的 1/3 处，饱和度向主色靠 30%）。
 * 与 com.google.android.material 的 MaterialColors.harmonize 算法意图一致，
 * 自实现是为了不为此引入整个 material 库依赖。
 * material3 的 dynamicColorScheme 取自壁纸，锚点用 colorScheme.primary
 * 就是"向壁纸主色靠"。
 */
@Composable
private fun harmonizeWithPrimary(color: Color): Color {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return color
    val anchor = MaterialTheme.colorScheme.primary
    val hsl = color.toHsl()
    val anchorHsl = anchor.toHsl()
    // 色相：取两者圆环上的 1/3 点（更靠近原色，保留药品颜色的辨识度）
    val blendedHue = blendHuesCircular(hsl[0], anchorHsl[0], 1f / 3f)
    // 饱和度：向主色靠 30%。药品容器色本身是低饱和的，只需轻微牵引
    val blendedSat = hsl[1] + (anchorHsl[1] - hsl[1]) * 0.3f
    return hslToColor(blendedHue, blendedSat.coerceIn(0f, 1f), hsl[2])
}

/** RGB → HSL（h:0-360, s:0-1, l:0-1）。 */
private fun Color.toHsl(): FloatArray {
    val r = red; val g = green; val b = blue
    val max = maxOf(r, g, b); val min = minOf(r, g, b)
    val l = (max + min) / 2f
    val delta = max - min
    if (delta == 0f) return floatArrayOf(0f, 0f, l)
    val s = if (l > 0.5f) delta / (2f - max - min) else delta / (max + min)
    val h = when (max) {
        r -> ((g - b) / delta + (if (g < b) 6f else 0f))
        g -> (b - r) / delta + 2f
        else -> (r - g) / delta + 4f
    } * 60f
    return floatArrayOf(h, s, l)
}

/** HSL → RGB。 */
private fun hslToColor(h: Float, s: Float, l: Float): Color {
    val c = (1f - kotlin.math.abs(2f * l - 1f)) * s
    val hp = (h % 360f) / 60f
    val x = c * (1f - kotlin.math.abs(hp % 2f - 1f))
    val (r1, g1, b1) = when (hp.toInt()) {
        0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x)
        3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
    }
    val m = l - c / 2f
    return Color(r1 + m, g1 + m, b1 + m)
}

/** 色相圆环插值：从 from 出发向 to 走 t 比例，走最短弧。 */
private fun blendHuesCircular(from: Float, to: Float, t: Float): Float {
    var diff = (to - from) % 360f
    if (diff > 180f) diff -= 360f
    if (diff < -180f) diff += 360f
    return (from + diff * t + 360f) % 360f
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

// 说明：MaterialExpressiveTheme/MotionScheme 在 material3 1.4.0（BOM 2026.06.01
// 实际解析版本）仍是 internal（编译为 *$material3 后缀的 JVM 名，外部不可调），
// 1.5.0 才转正。这里维持 MaterialTheme + 手写 Expressive 形状/动效的做法，
// 升级 BOM 到 1.5+ 后可切换官方 API。
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
