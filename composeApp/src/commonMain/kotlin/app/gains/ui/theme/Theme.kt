package app.gains.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Dark-first palette in the style of current training apps: deep charcoal surfaces,
 * one electric accent, and a small set of semantic colours for insight kinds.
 */
data class GainsPalette(
    val volt: Color,
    val violet: Color,
    val coral: Color,
    val amber: Color,
    val orchid: Color,
    val cyan: Color,
    val muted: Color,
    val cardTop: Color,
    val cardBottom: Color,
    val heroStart: Color,
    val heroEnd: Color,
    val gridLine: Color,
    val isDark: Boolean,
) {
    val progress get() = volt
    val regression get() = coral
    val stall get() = amber
    val neglect get() = orchid
    val consistency get() = cyan

    /** Distinct colours for the 17 muscle groups in the stacked chart. */
    val series: List<Color> = listOf(
        volt, cyan, coral, violet, amber, orchid,
        Color(0xFF4DE1C1), Color(0xFFFF8FAB), Color(0xFF9DD6FF), Color(0xFFFFD166),
        Color(0xFF80ED99), Color(0xFFF4A261), Color(0xFFB8C0FF), Color(0xFFFF9F1C),
        Color(0xFF00C2A8), Color(0xFFE07BE0), Color(0xFF8ECAE6),
    )

    fun heroBrush() = Brush.linearGradient(listOf(heroStart, heroEnd))
    fun cardBrush() = Brush.verticalGradient(listOf(cardTop, cardBottom))
}

private val DarkPalette = GainsPalette(
    volt = Color(0xFFC8FF4D),
    violet = Color(0xFF8B7CFF),
    coral = Color(0xFFFF6B6B),
    amber = Color(0xFFFFB020),
    orchid = Color(0xFFC77DFF),
    cyan = Color(0xFF4DD9FF),
    muted = Color(0xFF8A93A6),
    cardTop = Color(0xFF181D27),
    cardBottom = Color(0xFF12161E),
    heroStart = Color(0xFF1D2A16),
    heroEnd = Color(0xFF0F1A2E),
    gridLine = Color(0x1FFFFFFF),
    isDark = true,
)

private val LightPalette = GainsPalette(
    volt = Color(0xFF3E8E00),
    violet = Color(0xFF5B4BD6),
    coral = Color(0xFFD64545),
    amber = Color(0xFFC77A00),
    orchid = Color(0xFF8E3BCF),
    cyan = Color(0xFF0E86B8),
    muted = Color(0xFF6B7280),
    cardTop = Color(0xFFFFFFFF),
    cardBottom = Color(0xFFF7F8FB),
    heroStart = Color(0xFFE8F8CF),
    heroEnd = Color(0xFFDDEBFF),
    gridLine = Color(0x14000000),
    isDark = false,
)

val LocalGainsPalette = staticCompositionLocalOf { DarkPalette }

private val DarkScheme = darkColorScheme(
    primary = DarkPalette.volt,
    onPrimary = Color(0xFF0B0D12),
    primaryContainer = Color(0xFF2A3A12),
    onPrimaryContainer = DarkPalette.volt,
    secondary = DarkPalette.cyan,
    onSecondary = Color(0xFF0B0D12),
    tertiary = DarkPalette.violet,
    background = Color(0xFF0B0D12),
    onBackground = Color(0xFFF2F4F8),
    surface = Color(0xFF0B0D12),
    onSurface = Color(0xFFF2F4F8),
    surfaceVariant = Color(0xFF1B202B),
    onSurfaceVariant = Color(0xFF9AA3B5),
    surfaceContainer = Color(0xFF151923),
    surfaceContainerHigh = Color(0xFF1B202B),
    surfaceContainerHighest = Color(0xFF232937),
    outline = Color(0xFF2C3342),
    outlineVariant = Color(0xFF222837),
    error = DarkPalette.coral,
)

private val LightScheme = lightColorScheme(
    primary = LightPalette.volt,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3F7C2),
    onPrimaryContainer = Color(0xFF1E4400),
    secondary = LightPalette.cyan,
    tertiary = LightPalette.violet,
    background = Color(0xFFF3F4F8),
    onBackground = Color(0xFF12141A),
    surface = Color(0xFFF3F4F8),
    onSurface = Color(0xFF12141A),
    surfaceVariant = Color(0xFFE6E8EF),
    onSurfaceVariant = Color(0xFF5C6373),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF7F8FB),
    surfaceContainerHighest = Color(0xFFEEF0F5),
    outline = Color(0xFFD5D9E3),
    outlineVariant = Color(0xFFE3E6EE),
    error = LightPalette.coral,
)

private val GainsTypography = Typography(
    displayLarge = TextStyle(fontWeight = FontWeight.ExtraBold, fontSize = 44.sp, lineHeight = 48.sp, letterSpacing = (-1.5).sp),
    displayMedium = TextStyle(fontWeight = FontWeight.ExtraBold, fontSize = 34.sp, lineHeight = 38.sp, letterSpacing = (-1).sp),
    displaySmall = TextStyle(fontWeight = FontWeight.ExtraBold, fontSize = 26.sp, lineHeight = 30.sp, letterSpacing = (-0.5).sp),
    headlineLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 34.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 28.sp, letterSpacing = (-0.3).sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 24.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 22.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 20.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 1.sp),
)

private val GainsShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun GainsTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val palette = if (darkTheme) DarkPalette else LightPalette
    CompositionLocalProvider(LocalGainsPalette provides palette) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = GainsTypography,
            shapes = GainsShapes,
            content = content,
        )
    }
}

object GainsColors {
    val palette: GainsPalette @Composable get() = LocalGainsPalette.current
}
