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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Brand colours are fixed so the mark looks the same in both themes and matches the launcher icons.
private val LogoTile = Color(0xFF1B1E22)
private val LogoChalk = Color(0xFFF2F0EB)
private val LogoGreen = Color(0xFF62D99A)
private val LogoBar = Color(0xFF8E949C)

// The end of a loaded barbell seen from the side, on a 100-unit tile: the sleeve, three plates
// stepping up towards the middle of the bar, and the bar running on. Plates going up is
// progressive overload, which is what "gains" means; the biggest, newest plate is the green one.
// The same paths are baked into the iOS AppIcon, the Android launcher drawables and the site's icons.
private val LogoBarPath = PathParser().parsePathString("M16.5,46.5H22.5A3.5,3.5 0 0,1 26,50V50A3.5,3.5 0 0,1 22.5,53.5H16.5A3.5,3.5 0 0,1 13,50V50A3.5,3.5 0 0,1 16.5,46.5ZM74.5,46.5H83.5A3.5,3.5 0 0,1 87,50V50A3.5,3.5 0 0,1 83.5,53.5H74.5A3.5,3.5 0 0,1 71,50V50A3.5,3.5 0 0,1 74.5,46.5Z").toPath()
private val LogoSmallPlates = PathParser().parsePathString("M28.2,37H34.8A3.2,3.2 0 0,1 38,40.2V59.8A3.2,3.2 0 0,1 34.8,63H28.2A3.2,3.2 0 0,1 25,59.8V40.2A3.2,3.2 0 0,1 28.2,37ZM45.2,29H51.8A3.2,3.2 0 0,1 55,32.2V67.8A3.2,3.2 0 0,1 51.8,71H45.2A3.2,3.2 0 0,1 42,67.8V32.2A3.2,3.2 0 0,1 45.2,29Z").toPath()
private val LogoBigPlate = PathParser().parsePathString("M62.2,19H68.8A3.2,3.2 0 0,1 72,22.2V77.8A3.2,3.2 0 0,1 68.8,81H62.2A3.2,3.2 0 0,1 59,77.8V22.2A3.2,3.2 0 0,1 62.2,19Z").toPath()

/** The Gains mark: a loaded bar's plates stepping up, on a graphite tile. Flat, three colours, legible at 16 px. */
@Composable
internal fun GainsLogo(modifier: Modifier = Modifier, size: Dp = 56.dp, cornerFraction: Float = 0.22f) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        drawRoundRect(LogoTile, size = Size(s, s), cornerRadius = CornerRadius(s * cornerFraction))
        scale(s / 100f, pivot = Offset.Zero) {
            drawPath(LogoBarPath, LogoBar)
            drawPath(LogoSmallPlates, LogoChalk)
            drawPath(LogoBigPlate, LogoGreen)
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
            style = MaterialTheme.typography.headlineLarge.copy(fontSize = 20.sp, lineHeight = 24.sp),
            letterSpacing = 1.5.sp,
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
