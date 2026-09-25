package app.gains.ui.screens

import app.gains.ui.theme.LocalReduceMotion
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
import androidx.compose.foundation.layout.width
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
import app.gains.analysis.InsightKind
import app.gains.auth.AccountKind
import app.gains.auth.AccountRepository
import app.gains.auth.AuthConfig
import app.gains.auth.AuthNotConfiguredException
import app.gains.auth.SignInCancelledException
import app.gains.ui.ScreenModel
import app.gains.ui.charts.ChartMath
import app.gains.ui.components.AppleLogo
import app.gains.ui.components.DeltaBadge
import app.gains.ui.components.Dp16
import app.gains.ui.components.GainsCard
import app.gains.ui.components.GainsLogo
import app.gains.ui.components.Pill
import app.gains.ui.components.PrimaryButton
import app.gains.resources.Res
import app.gains.resources.*
import app.gains.ui.i18n.*
import org.jetbrains.compose.resources.stringResource
import app.gains.ui.inject
import app.gains.ui.rememberScreenModel
import app.gains.ui.theme.GainsColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

internal class SignInModel(
    private val accounts: AccountRepository = inject(),
    val config: AuthConfig = inject(),
) : ScreenModel() {
    private val attempt = SignInAttempt(scope, accounts)

    /** The provider whose sign-in is not configured, after a tap on its button; the screen words it. */
    val error: AuthNotConfiguredException? get() = attempt.error
    /** A sign-in that was configured but did not go through: the provider or the server refused, or could not be reached. */
    val failed: Boolean get() = attempt.failed

    fun continueAsGuest() = scope.launch { accounts.continueAsGuest() }

    fun signInWithGoogle() = attempt.google()

    fun signInWithApple() = attempt.apple()
}

/**
 * One sign-in through a provider's sheet, shared by the welcome screen and Settings so both treat
 * its endings alike: closing the sheet says nothing, a provider this build lacks says it is not
 * configured, and anything else says the sign-in failed. The account changes only when a sign-in
 * goes through, so a guest who gives up in Settings is still a guest with everything in place.
 */
internal class SignInAttempt(private val scope: CoroutineScope, private val accounts: AccountRepository) {
    var error by mutableStateOf<AuthNotConfiguredException?>(null)
        private set
    var failed by mutableStateOf(false)
        private set
    /** A sheet is up or its token is on the way to the server; a second tap waits for it rather than opening another. */
    var running by mutableStateOf(false)
        private set

    fun google(): Job = run { accounts.signInWithGoogle() }

    fun apple(): Job = run { accounts.signInWithApple() }

    private fun run(block: suspend () -> Unit): Job {
        if (running) return Job().apply { complete() }
        running = true
        error = null
        failed = false
        return scope.launch {
            try {
                block()
            } catch (e: AuthNotConfiguredException) {
                error = e
            } catch (e: SignInCancelledException) {
                // Closing the sheet is a choice, not a failure: the screen stays as it was.
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
            } finally {
                running = false
            }
        }
    }
}

/**
 * The sign-in buttons to offer, Apple first as its guidelines ask. Only the enabled providers are
 * shown, so an iOS build with Apple alone has no dead Google button; empty when neither is enabled,
 * and the screen then shows both disabled as a sign of what is coming.
 */
internal fun signInButtons(config: AuthConfig): List<AccountKind> = buildList {
    if (config.appleEnabled) add(AccountKind.APPLE)
    if (config.googleEnabled) add(AccountKind.GOOGLE)
}

/**
 * First-launch gate: the mark, a headline, a taste of the insights, then the ways in.
 * Everything fits on one screen; on short displays the layout tightens instead of scrolling.
 * The aurora runs edge to edge under the system bars; only the content respects them.
 */
@Composable
internal fun SignInScreen() {
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
                stringResource(Res.string.sign_in_headline),
                style = if (compact) MaterialTheme.typography.displaySmall else MaterialTheme.typography.displayMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(Res.string.sign_in_blurb),
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

            val buttons = signInButtons(model.config)
            if (buttons.isEmpty()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ProviderButton("Google", enabled = false, Modifier.weight(1f)) {}
                    ProviderButton("Apple", enabled = false, Modifier.weight(1f)) {}
                }
                Spacer(Modifier.height(10.dp))
            }
            for (kind in buttons) {
                when (kind) {
                    AccountKind.APPLE -> AppleSignInButton(Modifier.fillMaxWidth()) { model.signInWithApple() }
                    AccountKind.GOOGLE -> ProviderButton(stringResource(Res.string.sign_in_with_google), enabled = true, Modifier.fillMaxWidth()) { model.signInWithGoogle() }
                    AccountKind.GUEST -> Unit
                }
                Spacer(Modifier.height(10.dp))
            }
            PrimaryButton(stringResource(Res.string.continue_as_guest), onClick = { model.continueAsGuest() }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            val note = if (buttons.isNotEmpty()) stringResource(Res.string.guest_note_with_sync) else stringResource(Res.string.guest_note_coming_soon)
            val message = model.error?.let { stringResource(Res.string.sign_in_not_configured, it.provider.label()) }
                ?: if (model.failed) stringResource(Res.string.sign_in_failed) else note
            Text(message, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                color = if (model.error != null || model.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Slowly drifting colour blobs behind the hero. */
@Composable
private fun AuroraBackground(modifier: Modifier = Modifier) {
    val palette = GainsColors.palette
    val transition = rememberInfiniteTransition(label = "aurora")
    val drifting by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(14_000, easing = LinearEasing), RepeatMode.Restart), label = "t")
    // Held still where the platform asks for less motion.
    val t = if (LocalReduceMotion.current) 0f else drifting
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
    val drawing by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart), label = "p")
    // Where the platform asks for less motion, the trend is shown whole instead of drawing itself over and over.
    val progress = if (LocalReduceMotion.current) 1f else drawing
    GainsCard(Modifier.fillMaxWidth(), contentPadding = Dp16.Normal) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(InsightKind.PROGRESS.label(), palette.progress)
            Spacer(Modifier.weight(1f))
            DeltaBadge(0.06)
        }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(Res.string.hero_exercise), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(Res.string.hero_detail), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            Pill(InsightKind.REGRESSION.label(), palette.regression)
            Pill(InsightKind.STALL.label(), palette.stall)
            Pill(InsightKind.CONSISTENCY.label(), palette.consistency)
        }
    }
}

@Composable
private fun FeatureRow() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Feature(stringResource(Res.string.feature_log), stringResource(Res.string.feature_log_body), Modifier.weight(1f))
        Feature(stringResource(Res.string.feature_import), stringResource(Res.string.feature_import_body), Modifier.weight(1f))
        Feature(stringResource(Res.string.feature_analyse), stringResource(Res.string.feature_analyse_body), Modifier.weight(1f))
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

/** A provider's pill: Google's, and both while neither is configured. Settings uses a lower one. */
@Composable
internal fun ProviderButton(provider: String, enabled: Boolean, modifier: Modifier = Modifier, height: Dp = 50.dp, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(height),
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

/**
 * Sign in with Apple as Apple's Human Interface Guidelines draw it: the logo and "Sign in with
 * Apple", black on a light theme and white on a dark one. App Review checks this, so it keeps
 * the brand colours rather than the app's palette. [label] must stay one of the titles the
 * guidelines allow ("Sign in with", "Sign up with" or "Continue with Apple").
 */
@Composable
internal fun AppleSignInButton(
    modifier: Modifier = Modifier,
    label: String = stringResource(Res.string.sign_in_with_apple),
    height: Dp = 50.dp,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val dark = GainsColors.palette.isDark
    val container = if (dark) Color.White else Color.Black
    val content = if (dark) Color.Black else Color.White
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(height),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
            disabledContainerColor = container.copy(alpha = 0.5f),
            disabledContentColor = content,
        ),
    ) {
        AppleLogo(Modifier.size(height * 0.36f), color = content)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}
