package app.gains.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gains.ui.theme.GainsColors
import app.gains.ui.theme.LocalReduceMotion
import app.gains.ui.theme.Motion
import app.gains.ui.theme.rememberPreviouslyShown
import app.gains.ui.theme.roll
import kotlinx.coroutines.launch

/** Rounded, softly graded surface used for every card in the app. Press feedback is a gentle scale. */
@Composable
internal fun GainsCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    brush: Brush? = null,
    contentPadding: Dp16 = Dp16.Normal,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = GainsColors.palette
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.985f else 1f, Motion.press(), label = "press")
    val shape = MaterialTheme.shapes.large
    Column(
        modifier
            .scale(scale)
            .clip(shape)
            .background(brush ?: palette.cardBrush())
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (palette.isDark) 0.6f else 1f), shape)
            .then(if (onClick != null) Modifier.clickable(interaction, indication = null, onClick = onClick) else Modifier)
            .padding(contentPadding.dp)
            .animateContentSize(),
        content = content,
    )
}

internal enum class Dp16(val dp: androidx.compose.ui.unit.Dp) { None(0.dp), Tight(12.dp), Normal(18.dp), Loose(22.dp) }

/** Large screen heading with an optional muted subtitle. */
@Composable
internal fun ScreenTitle(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    /** When set, the subtitle becomes a link (drawn in the accent colour with a chevron). */
    onSubtitleClick: (() -> Unit)? = null,
) {
    val palette = GainsColors.palette
    Row(modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineLarge)
            if (subtitle != null) {
                if (onSubtitleClick == null) {
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(
                        "$subtitle ›",
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.volt,
                        modifier = Modifier.clip(CircleShape).clickable(onClick = onSubtitleClick),
                    )
                }
            }
        }
        trailing?.invoke()
    }
}

@Composable
internal fun SectionHeader(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 20.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

/** Small rounded label: kind tags, statuses. */
@Composable
internal fun Pill(text: String, color: Color, modifier: Modifier = Modifier, filled: Boolean = false, onClick: (() -> Unit)? = null) {
    // A pill that turns on (a chosen chip, "Active") eases into it rather than blinking.
    val background by animateColorAsState(if (filled) color else color.copy(alpha = 0.16f), Motion.standard(), label = "pill")
    val content by animateColorAsState(if (filled) MaterialTheme.colorScheme.onPrimary else color, Motion.standard(), label = "pill-text")
    Box(
        modifier
            .clip(CircleShape)
            .background(background)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = content, maxLines = 1, softWrap = false)
    }
}

/** "+13%" / "−18%" style badge. */
@Composable
internal fun DeltaBadge(delta: Double, modifier: Modifier = Modifier) {
    val palette = GainsColors.palette
    val positive = delta >= 0
    val text = (if (positive) "+" else "−") + app.gains.analysis.Format.percent(kotlin.math.abs(delta))
    Pill(text, if (positive) palette.progress else palette.regression, modifier)
}

/** Big-number tile. */
@Composable
internal fun MetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
    accent: Color? = null,
    large: Boolean = false,
    onClick: (() -> Unit)? = null,
    /** False when the caller animates the value itself (a count-up), so it is not rolled on every step. */
    roll: Boolean = true,
) {
    GainsCard(modifier, onClick = onClick, contentPadding = Dp16.Tight) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        val style = if (large) MaterialTheme.typography.displayMedium else MaterialTheme.typography.displaySmall
        val color = accent ?: MaterialTheme.colorScheme.onSurface
        if (roll) RollingText(value, style, color)
        else Text(value, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (caption != null) {
            Spacer(Modifier.height(2.dp))
            Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Segmented pill selector. */
@Composable
internal fun <T> ChipRow(options: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    // The filled pill slides from the old choice to the new one instead of jumping.
    val indicator = rememberSlidingIndicator(options.indexOf(selected).takeIf { it >= 0 })
    val pill = MaterialTheme.colorScheme.primary
    Row(
        modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(4.dp)
            .slidingIndicator(indicator, pill),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { index, option ->
            val active = option == selected
            val text by animateColorAsState(
                if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                Motion.standard(), label = "chip",
            )
            Box(
                Modifier
                    .indicatorSlot(indicator, index)
                    .clip(CircleShape)
                    .clickable { onSelect(option) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(label(option), style = MaterialTheme.typography.labelLarge, color = text)
            }
        }
    }
}

@Composable
internal fun KeyValueRow(key: String, value: String, modifier: Modifier = Modifier, valueColor: Color? = null) {
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(value, style = MaterialTheme.typography.titleSmall, color = valueColor ?: MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.End)
    }
}

@Composable
internal fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    emoji: String = "✦",
    /** The state is an achievement (an import done), so its badge pops in rather than just being there. */
    celebrate: Boolean = false,
    action: (@Composable () -> Unit)? = null,
) {
    val palette = GainsColors.palette
    val badge = remember { Animatable(if (celebrate) 0.5f else 1f) }
    val pop = Motion.pop<Float>()
    LaunchedEffect(Unit) { badge.animateTo(1f, pop) }
    Column(
        modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(72.dp).scale(badge.value).clip(CircleShape).background(palette.volt.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) { Text(emoji, style = MaterialTheme.typography.headlineMedium, color = palette.volt) }
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) {
            Spacer(Modifier.height(24.dp))
            action()
        }
    }
}

@Composable
internal fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        enabled = enabled,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
    ) { Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold) }
}

@Composable
internal fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest, contentColor = MaterialTheme.colorScheme.onSurface),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

/** Thin horizontal meter used in the volume table. */
@Composable
internal fun Meter(fraction: Float, color: Color, modifier: Modifier = Modifier, marker: Float? = null) {
    // Fills from empty the first time it is shown, then follows the value from wherever it is.
    val target = fraction.coerceIn(0f, 1f)
    val animated = remember { Animatable(0f) }
    val spec = Motion.reveal<Float>()
    LaunchedEffect(target) { animated.animateTo(target, spec) }
    Box(modifier.height(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        Box(Modifier.fillMaxWidth(animated.value).height(6.dp).clip(CircleShape).background(color))
        if (marker != null) {
            Box(Modifier.fillMaxWidth(marker.coerceIn(0f, 1f)), contentAlignment = Alignment.CenterEnd) {
                Box(Modifier.width(2.dp).height(6.dp).background(MaterialTheme.colorScheme.onSurfaceVariant))
            }
        }
    }
}

/** Small colour dot. */
@Composable
internal fun Dot(color: Color, size: androidx.compose.ui.unit.Dp = 10.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(color))
}

@Composable
internal fun RoundedIconBox(color: Color, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
        content = { content() },
    )
}

/**
 * A figure that rolls to its new value when it changes — up when it grew, down when it shrank —
 * including a change made while the screen was covered, which is seen when the lifter comes back.
 * Shown for the first time, it simply is there.
 */
@Composable
internal fun RollingText(value: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    val reduce = LocalReduceMotion.current
    val previous = rememberPreviouslyShown(value)
    val state = remember { MutableTransitionState(previous) }
    state.targetState = value
    rememberTransition(state, label = "roll").AnimatedContent(modifier, transitionSpec = { roll(reduce) }) { shown ->
        Text(shown, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Where a row of choices has put its highlighted one, so a single pill can be drawn behind the row
 * and slide between choices. Each choice reports its place with [indicatorSlot]; the row draws the
 * pill with [slidingIndicator]. Null [selected] fades the pill out where it stands.
 */
@Stable
internal class SlidingIndicator {
    internal val slots = mutableStateMapOf<Int, Pair<Float, Float>>()
    internal val left = Animatable(0f)
    internal val width = Animatable(0f)
    internal val alpha = Animatable(0f)
    internal var placed by mutableStateOf(false)
    internal var selected: Int? by mutableStateOf(null)
}

@Composable
internal fun rememberSlidingIndicator(selected: Int?): SlidingIndicator {
    val indicator = remember { SlidingIndicator() }
    indicator.selected = selected
    val move = Motion.move<Float>()
    val fade = Motion.standard<Float>()
    val target = selected?.let { indicator.slots[it] }
    LaunchedEffect(target, selected) {
        if (selected == null) { indicator.alpha.animateTo(0f, fade); return@LaunchedEffect }
        val (left, width) = target ?: return@LaunchedEffect
        if (!indicator.placed) {
            // Where it starts, it simply is: nothing slides in from the edge on first show.
            indicator.left.snapTo(left); indicator.width.snapTo(width); indicator.alpha.snapTo(1f)
            indicator.placed = true
        } else {
            launch { indicator.left.animateTo(left, move) }
            launch { indicator.width.animateTo(width, move) }
            launch { indicator.alpha.animateTo(1f, fade) }
        }
    }
    return indicator
}

internal fun Modifier.indicatorSlot(indicator: SlidingIndicator, index: Int): Modifier =
    onPlaced { indicator.slots[index] = it.positionInParent().x to it.size.width.toFloat() }

/** Draws [indicator]'s pill behind the row's content, in [color], as tall as the row. */
internal fun Modifier.slidingIndicator(indicator: SlidingIndicator, color: Color): Modifier = drawBehind {
    // Before the first placement has been taken in, the pill is drawn straight at its slot.
    val first = indicator.selected?.let { indicator.slots[it] }
    val (left, width, alpha) = if (indicator.placed) Triple(indicator.left.value, indicator.width.value, indicator.alpha.value)
    else if (first != null) Triple(first.first, first.second, 1f) else return@drawBehind
    if (alpha <= 0f || width <= 0f) return@drawBehind
    drawRoundRect(color.copy(alpha = color.alpha * alpha), topLeft = Offset(left, 0f), size = Size(width, size.height), cornerRadius = CornerRadius(size.height / 2))
}
