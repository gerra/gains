package app.gains.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gains.auth.AccountRepository
import app.gains.auth.AuthConfig
import app.gains.auth.AuthNotConfiguredException
import app.gains.ui.ScreenModel
import app.gains.ui.charts.ChartMath
import app.gains.ui.components.DeltaBadge
import app.gains.ui.components.Dp16
import app.gains.ui.components.GainsCard
import app.gains.ui.components.GainsLogo
import app.gains.ui.components.Pill
import app.gains.ui.components.PrimaryButton
import app.gains.ui.inject
import app.gains.ui.rememberScreenModel
import app.gains.ui.theme.GainsColors
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

class SignInModel(
    private val accounts: AccountRepository = inject(),
    val config: AuthConfig = inject(),
) : ScreenModel() {
    var error by mutableStateOf<String?>(null)
        private set

    fun continueAsGuest() = scope.launch { accounts.continueAsGuest() }

    fun signInWithGoogle() = scope.launch {
        try { accounts.signInWithGoogle() } catch (e: AuthNotConfiguredException) { error = e.message }
    }

    fun signInWithApple() = scope.launch {
        try { accounts.signInWithApple() } catch (e: AuthNotConfiguredException) { error = e.message }
    }
}

/**
 * First-launch gate: the mark, a headline, a taste of the insights, then the ways in.
 * Everything fits on one screen; on short displays the layout tightens instead of scrolling.
 * The aurora runs edge to edge under the system bars; only the content respects them.
 */
@Composable
fun SignInScreen() {
    val model = rememberScreenModel { SignInModel() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        AuroraBackground(Modifier.fillMaxSize())
        val compact = maxHeight < 760.dp
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            GainsLogo(size = if (compact) 52.dp else 64.dp)
            Spacer(Modifier.height(if (compact) 10.dp else 16.dp))
            Text(
                "Know what's\nactually moving.",
                style = if (compact) MaterialTheme.typography.displaySmall else MaterialTheme.typography.displayMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Log workouts or import from Liftoff, Strong, Hevy or any CSV. Gains shows which lifts climb, stall or slip, with the numbers to prove it.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(if (compact) 12.dp else 18.dp))
            HeroPreview(chartHeight = if (compact) 52.dp else 68.dp)
            if (!compact) {
                Spacer(Modifier.height(12.dp))
                FeatureRow()
            }
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.height(12.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ProviderButton("Google", enabled = model.config.googleEnabled, Modifier.weight(1f)) { model.signInWithGoogle() }
                ProviderButton("Apple", enabled = model.config.appleEnabled, Modifier.weight(1f)) { model.signInWithApple() }
            }
            Spacer(Modifier.height(10.dp))
            PrimaryButton("Continue as guest", onClick = { model.continueAsGuest() }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            val note = if (model.config.googleEnabled && model.config.appleEnabled) {
                "As a guest everything stays on this device. Sign in later to back it up and sync."
            } else {
                "Sign-in and cloud sync are coming soon. As a guest everything stays on this device."
            }
            Text(model.error ?: note, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                color = if (model.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Slowly drifting colour blobs behind the hero. */
@Composable
private fun AuroraBackground(modifier: Modifier = Modifier) {
    val palette = GainsColors.palette
    val transition = rememberInfiniteTransition(label = "aurora")
    val t by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(14_000, easing = LinearEasing), RepeatMode.Restart), label = "t")
    val alpha = if (palette.isDark) 0.55f else 0.35f
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val a = t * 2f * kotlin.math.PI.toFloat()
        fun blob(color: Color, cx: Float, cy: Float, r: Float) {
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = alpha), color.copy(alpha = 0f)), center = Offset(cx, cy), radius = r), radius = r, center = Offset(cx, cy))
        }
        blob(palette.volt, w * 0.25f + cos(a) * w * 0.12f, h * 0.12f + sin(a) * h * 0.05f, w * 0.55f)
        blob(palette.violet, w * 0.85f + cos(a + 2f) * w * 0.1f, h * 0.35f + sin(a + 2f) * h * 0.06f, w * 0.5f)
        blob(palette.cyan, w * 0.15f + cos(a + 4f) * w * 0.08f, h * 0.7f + sin(a + 4f) * h * 0.05f, w * 0.45f)
    }
}

/** A mock insight card with a live-drawn trend, so the value is visible before any data exists. */
@Composable
private fun HeroPreview(chartHeight: Dp) {
    val palette = GainsColors.palette
    val transition = rememberInfiniteTransition(label = "hero")
    val progress by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart), label = "p")
    GainsCard(Modifier.fillMaxWidth(), contentPadding = Dp16.Normal) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill("Progress", palette.progress)
            Spacer(Modifier.weight(1f))
            DeltaBadge(0.06)
        }
        Spacer(Modifier.height(8.dp))
        Text("Bench Press", style = MaterialTheme.typography.titleMedium)
        Text("62.5 kg × 8 on 20 Aug, up 6% on 60 kg × 8 from June.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        val values = listOf(72.0, 74.0, 73.5, 76.0, 76.0, 78.5, 80.0, 79.5, 82.0, 84.0)
        Canvas(Modifier.fillMaxWidth().height(chartHeight)) {
            val lo = values.min(); val hi = values.max()
            val pts = values.mapIndexed { i, v ->
                Offset(i * size.width / (values.size - 1), size.height - ((v - lo) / (hi - lo) * (size.height - 12f)).toFloat() - 6f)
            }
            val path = ChartMath.smoothPath(pts)
            val reveal = (progress * 1.25f).coerceAtMost(1f)
            clipRect(right = size.width * reveal) {
                val area = Path().apply { addPath(path); lineTo(pts.last().x, size.height); lineTo(pts.first().x, size.height); close() }
                drawPath(area, Brush.verticalGradient(listOf(palette.volt.copy(alpha = 0.35f), Color.Transparent)))
                drawPath(path, palette.volt, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
            }
            if (reveal >= 1f) {
                drawCircle(palette.volt.copy(alpha = 0.3f), radius = 9.dp.toPx(), center = pts.last())
                drawCircle(palette.volt, radius = 4.dp.toPx(), center = pts.last())
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill("Regression", palette.regression)
            Pill("Stall", palette.stall)
            Pill("Consistency", palette.consistency)
        }
    }
}

@Composable
private fun FeatureRow() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Feature("Log", "Sets, reps, holds, cardio", Modifier.weight(1f))
        Feature("Import", "Liftoff · Strong · Hevy · CSV", Modifier.weight(1f))
        Feature("Analyse", "e1RM, volume, streaks", Modifier.weight(1f))
    }
}

@Composable
private fun Feature(title: String, body: String, modifier: Modifier = Modifier) {
    val palette = GainsColors.palette
    Column(modifier.clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.8f)).padding(12.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(palette.volt))
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ProviderButton(provider: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(50.dp),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        ),
    ) {
        Text(provider, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}
