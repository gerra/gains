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
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gains.data.ThemeMode
import app.gains.resources.Res
import app.gains.resources.onest_bold
import app.gains.resources.onest_medium
import app.gains.resources.onest_regular
import app.gains.resources.onest_semibold
import app.gains.resources.oswald_medium
import app.gains.resources.oswald_semibold
import org.jetbrains.compose.resources.Font

/**
 * Chalk and iron: flat graphite surfaces like a gym floor, chalk-white type, and colour kept for what
 * it means. The accents are the competition plates' colours (green, red, yellow, blue), each with
 * one job in the insights, so a colour on screen is always a signal and never decoration.
 */
internal data class GainsPalette(
    /** The brand and progress accent: plate green. The name stays from the first palette. */
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
        Color(0xFF4FC8B4), Color(0xFFF08BA6), Color(0xFF8EC5F2), Color(0xFFE8C46A),
        Color(0xFF9AD98B), Color(0xFFE79A62), Color(0xFFAFB4F5), Color(0xFFE6A23C),
        Color(0xFF2BB39A), Color(0xFFD08AD8), Color(0xFF8FB8CC),
    )

    fun heroBrush() = Brush.linearGradient(listOf(heroStart, heroEnd))
    fun cardBrush() = Brush.verticalGradient(listOf(cardTop, cardBottom))
}

private val Graphite = Color(0xFF121416)
private val Chalk = Color(0xFFF2F0EB)
private val Ink = Color(0xFF15171A)

private val DarkPalette = GainsPalette(
    volt = Color(0xFF62D99A),
    violet = Color(0xFFA99BF7),
    coral = Color(0xFFFF7366),
    amber = Color(0xFFF2C14E),
    orchid = Color(0xFFD291F0),
    cyan = Color(0xFF6CB2FF),
    muted = Color(0xFFA7ACB3),
    cardTop = Color(0xFF1B1E22),
    cardBottom = Color(0xFF1B1E22),
    heroStart = Color(0xFF1C2622),
    heroEnd = Color(0xFF1B1E22),
    gridLine = Color(0x1AFFFFFF),
    isDark = true,
)

private val LightPalette = GainsPalette(
    volt = Color(0xFF17924F),
    violet = Color(0xFF6650D6),
    coral = Color(0xFFD4402F),
    amber = Color(0xFFB0700F),
    orchid = Color(0xFF9441C4),
    cyan = Color(0xFF1F6CC4),
    muted = Color(0xFF5A616B),
    cardTop = Color(0xFFFFFFFF),
    cardBottom = Color(0xFFFFFFFF),
    heroStart = Color(0xFFE7F5EC),
    heroEnd = Color(0xFFFFFFFF),
    gridLine = Color(0x14000000),
    isDark = false,
)

internal val LocalGainsPalette = staticCompositionLocalOf { DarkPalette }

// The main action is chalk on the dark floor and ink on the light one: the loudest thing on a
// screen is the thing to do, and the plate colours stay free to mean progress, stalls and slips.
private val DarkScheme = darkColorScheme(
    primary = Chalk,
    onPrimary = Graphite,
    primaryContainer = Color(0xFF2B3036),
    onPrimaryContainer = Chalk,
    secondary = DarkPalette.cyan,
    onSecondary = Graphite,
    tertiary = DarkPalette.violet,
    background = Graphite,
    onBackground = Chalk,
    surface = Graphite,
    onSurface = Chalk,
    surfaceVariant = Color(0xFF22262B),
    onSurfaceVariant = Color(0xFFA7ACB3),
    surfaceContainerLowest = Color(0xFF0D0F10),
    surfaceContainerLow = Color(0xFF16191C),
    surfaceContainer = Color(0xFF1B1E22),
    surfaceContainerHigh = Color(0xFF22262B),
    surfaceContainerHighest = Color(0xFF2B3036),
    outline = Color(0xFF3A4047),
    outlineVariant = Color(0xFF282C32),
    error = DarkPalette.coral,
)

private val LightScheme = lightColorScheme(
    primary = Ink,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9EBEE),
    onPrimaryContainer = Ink,
    secondary = LightPalette.cyan,
    tertiary = LightPalette.violet,
    background = Color(0xFFF1F2F4),
    onBackground = Ink,
    surface = Color(0xFFF1F2F4),
    onSurface = Ink,
    surfaceVariant = Color(0xFFE9EBEE),
    onSurfaceVariant = Color(0xFF5A616B),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF8F9FA),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFF4F5F7),
    surfaceContainerHighest = Color(0xFFE9EBEE),
    outline = Color(0xFFD0D4DA),
    outlineVariant = Color(0xFFE2E5E9),
    error = LightPalette.coral,
)

/**
 * Onest for every word (drawn for Cyrillic as much as Latin, so Russian reads as well as English) and
 * Oswald, condensed like a gym's scoreboard, for the big figures and the screen titles. A condensed
 * figure also fits "1 h 23 min" into a third of a phone without shrinking.
 */
@Composable
private fun gainsTypography(): Typography {
    val text = FontFamily(
        Font(Res.font.onest_regular, FontWeight.Normal),
        Font(Res.font.onest_medium, FontWeight.Medium),
        Font(Res.font.onest_semibold, FontWeight.SemiBold),
        Font(Res.font.onest_bold, FontWeight.Bold),
    )
    val figures = FontFamily(
        Font(Res.font.oswald_medium, FontWeight.Medium),
        Font(Res.font.oswald_semibold, FontWeight.SemiBold),
    )
    return remember(text, figures) {
        Typography(
            displayLarge = TextStyle(fontFamily = figures, fontWeight = FontWeight.SemiBold, fontSize = 52.sp, lineHeight = 58.sp),
            displayMedium = TextStyle(fontFamily = figures, fontWeight = FontWeight.SemiBold, fontSize = 38.sp, lineHeight = 44.sp),
            displaySmall = TextStyle(fontFamily = figures, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 36.sp),
            headlineLarge = TextStyle(fontFamily = figures, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 40.sp),
            headlineMedium = TextStyle(fontFamily = text, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.2).sp),
            headlineSmall = TextStyle(fontFamily = text, fontWeight = FontWeight.Bold, fontSize = 19.sp, lineHeight = 24.sp, letterSpacing = (-0.1).sp),
            titleLarge = TextStyle(fontFamily = text, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp),
            titleMedium = TextStyle(fontFamily = text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
            titleSmall = TextStyle(fontFamily = text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
            bodyLarge = TextStyle(fontFamily = text, fontSize = 16.sp, lineHeight = 24.sp),
            bodyMedium = TextStyle(fontFamily = text, fontSize = 15.sp, lineHeight = 22.sp),
            bodySmall = TextStyle(fontFamily = text, fontSize = 13.sp, lineHeight = 18.sp),
            labelLarge = TextStyle(fontFamily = text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
            labelMedium = TextStyle(fontFamily = text, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),
            labelSmall = TextStyle(fontFamily = text, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
        )
    }
}

private val GainsShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(26.dp),
)

/**
 * Whether the app is drawn dark in this mode: [ThemeMode.SYSTEM] follows the device, and changes
 * with it while the app is open.
 */
@Composable
internal fun ThemeMode.isDark(): Boolean = when (this) {
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> false
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
}

@Composable
internal fun GainsTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val palette = if (darkTheme) DarkPalette else LightPalette
    CompositionLocalProvider(LocalGainsPalette provides palette) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = gainsTypography(),
            shapes = GainsShapes,
            content = content,
        )
    }
}

internal object GainsColors {
    val palette: GainsPalette @Composable get() = LocalGainsPalette.current
}
