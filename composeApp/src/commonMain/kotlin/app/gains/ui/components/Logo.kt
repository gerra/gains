package app.gains.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gains.ui.charts.ChartMath

// Brand colours are fixed so the mark looks the same in both themes and matches the launcher icons.
private val LogoVolt = Color(0xFFC8FF4D)
private val LogoViolet = Color(0xFF8B7CFF)
private val LogoCyan = Color(0xFF4DD9FF)
private val LogoInk = Color(0xFF0B0D12)
private val LogoInkTop = Color(0xFF1A2238)

/** Trend-line points as fractions of the tile; the same geometry is baked into the iOS and Android app icons. */
private val LogoPoints = listOf(0.14f to 0.74f, 0.34f to 0.60f, 0.50f to 0.66f, 0.66f to 0.46f, 0.86f to 0.26f)
private val LogoBars = listOf(0.22f to 0.78f, 0.40f to 0.70f, 0.58f to 0.74f, 0.76f to 0.58f)

/** The Gains mark: a dark rounded tile with an ascending trend line that ends in a target dot. */
@Composable
internal fun GainsLogo(modifier: Modifier = Modifier, size: Dp = 56.dp, cornerFraction: Float = 0.24f) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val tile = Path().apply { addRoundRect(RoundRect(0f, 0f, s, s, CornerRadius(s * cornerFraction))) }
        clipPath(tile) {
            drawRect(Brush.linearGradient(listOf(LogoInkTop, LogoInk), Offset.Zero, Offset(s, s)))
            fun glow(color: Color, cx: Float, cy: Float, r: Float, alpha: Float) {
                val c = Offset(cx, cy)
                drawCircle(Brush.radialGradient(listOf(color.copy(alpha = alpha), color.copy(alpha = 0f)), c, r), r, c)
            }
            glow(LogoVolt, s * 0.80f, s * 0.22f, s * 0.55f, 0.30f)
            glow(LogoViolet, s * 0.18f, s * 0.85f, s * 0.60f, 0.35f)
            glow(LogoCyan, s * 0.15f, s * 0.25f, s * 0.40f, 0.15f)

            val barW = s * 0.10f
            for ((x, top) in LogoBars) {
                drawRoundRect(Color.White.copy(alpha = 0.07f), Offset(s * x - barW / 2, s * top), Size(barW, s * 0.92f - s * top), CornerRadius(barW * 0.3f))
            }

            val pts = LogoPoints.map { (x, y) -> Offset(x * s, y * s) }
            val curve = ChartMath.smoothPath(pts)
            val area = Path().apply { addPath(curve); lineTo(pts.last().x, s * 0.92f); lineTo(pts.first().x, s * 0.92f); close() }
            drawPath(area, Brush.verticalGradient(listOf(LogoVolt.copy(alpha = 0.45f), LogoVolt.copy(alpha = 0f)), startY = pts.last().y, endY = s * 0.92f))
            // Soft halo under the line, then the line itself.
            drawPath(curve, LogoVolt.copy(alpha = 0.3f), style = Stroke(width = s * 0.15f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(curve, LogoVolt, style = Stroke(width = s * 0.075f, cap = StrokeCap.Round, join = StrokeJoin.Round))

            val end = pts.last()
            drawCircle(LogoVolt.copy(alpha = 0.25f), s * 0.10f, end)
            drawCircle(LogoVolt, s * 0.055f, end)
            drawCircle(LogoInk, s * 0.025f, end)
        }
    }
}

/** Mark plus wordmark, for headers. */
@Composable
internal fun GainsWordmark(modifier: Modifier = Modifier, markSize: Dp = 26.dp) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        GainsLogo(size = markSize, cornerFraction = 0.28f)
        Spacer(Modifier.width(8.dp))
        Text(
            "GAINS",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 2.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * The Apple logo on a 24-unit square, for the Sign in with Apple button. A vector because the
 * U+F8FF glyph that draws it on Apple's own fonts is an empty box on Android and the desktop.
 */
private val AppleLogoPath: Path = PathParser().parsePathString(
    "M12.152 6.896c-.948 0-2.415-1.078-3.96-1.04-2.04.027-3.91 1.183-4.961 3.014-2.117 3.675-.546 9.103 1.519 12.09 " +
        "1.013 1.454 2.208 3.09 3.792 3.039 1.52-.065 2.09-.987 3.935-.987 1.831 0 2.35.987 3.96.948 1.637-.026 2.676-1.48 " +
        "3.676-2.948 1.156-1.688 1.636-3.325 1.662-3.415-.039-.013-3.182-1.221-3.22-4.857-.026-3.04 2.48-4.494 2.597-4.559" +
        "-1.429-2.09-3.623-2.324-4.39-2.376-2-.156-3.675 1.09-4.61 1.09zM15.53 3.83c.843-1.012 1.4-2.427 1.245-3.83" +
        "-1.207.052-2.662.805-3.532 1.818-.78.896-1.454 2.338-1.273 3.714 1.338.104 2.715-.688 3.559-1.701z",
).toPath()

/** The Apple logo in one colour, as the Sign in with Apple button draws it. */
@Composable
internal fun AppleLogo(modifier: Modifier = Modifier, color: Color) {
    Canvas(modifier) {
        scale(size.minDimension / 24f, pivot = Offset.Zero) { drawPath(AppleLogoPath, color) }
    }
}
