package app.gains.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object GainsColors {
    val Progress = Color(0xFF2E7D32)
    val Regression = Color(0xFFC62828)
    val Stall = Color(0xFFEF6C00)
    val Neglect = Color(0xFF6A1B9A)
    val Consistency = Color(0xFF1565C0)
    val Low = Color(0xFFEF6C00)
    val High = Color(0xFFC62828)
    val Ok = Color(0xFF2E7D32)
    val Muted = Color(0xFF9E9E9E)

    /** Distinct, colour-blind-friendly-ish palette for muscle groups in the stacked chart. */
    val Palette = listOf(
        Color(0xFF4E79A7), Color(0xFFF28E2B), Color(0xFFE15759), Color(0xFF76B7B2),
        Color(0xFF59A14F), Color(0xFFEDC948), Color(0xFFB07AA1), Color(0xFFFF9DA7),
        Color(0xFF9C755F), Color(0xFFBAB0AC), Color(0xFF1F77B4), Color(0xFF8C564B),
        Color(0xFF17BECF), Color(0xFFBCBD22), Color(0xFF7F7F7F), Color(0xFF2CA02C),
        Color(0xFFD62728),
    )
}

private val Light = lightColorScheme(
    primary = Color(0xFF1B5E20),
    secondary = Color(0xFF37474F),
    tertiary = Color(0xFF6D4C41),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF81C784),
    secondary = Color(0xFFB0BEC5),
    tertiary = Color(0xFFBCAAA4),
)

@Composable
fun GainsTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
