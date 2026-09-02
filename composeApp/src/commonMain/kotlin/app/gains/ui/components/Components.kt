package app.gains.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gains.ui.theme.GainsColors

/** Rounded, softly graded surface used for every card in the app. Press feedback is a gentle scale. */
@Composable
fun GainsCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    brush: Brush? = null,
    contentPadding: Dp16 = Dp16.Normal,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = GainsColors.palette
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.985f else 1f, tween(120), label = "press")
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

enum class Dp16(val dp: androidx.compose.ui.unit.Dp) { None(0.dp), Tight(12.dp), Normal(18.dp), Loose(22.dp) }

/** Large screen heading with an optional muted subtitle. */
@Composable
fun ScreenTitle(title: String, subtitle: String? = null, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 20.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

/** Small rounded label: kind tags, statuses. */
@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier, filled: Boolean = false) {
    Box(
        modifier
            .clip(CircleShape)
            .background(if (filled) color else color.copy(alpha = 0.16f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = if (filled) MaterialTheme.colorScheme.onPrimary else color)
    }
}

/** "+13%" / "−18%" style badge. */
@Composable
fun DeltaBadge(delta: Double, modifier: Modifier = Modifier) {
    val palette = GainsColors.palette
    val positive = delta >= 0
    val text = (if (positive) "+" else "−") + app.gains.analysis.Format.percent(kotlin.math.abs(delta))
    Pill(text, if (positive) palette.progress else palette.regression, modifier)
}

/** Big-number tile. */
@Composable
fun MetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
    accent: Color? = null,
    large: Boolean = false,
) {
    GainsCard(modifier, contentPadding = Dp16.Tight) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Text(
            value,
            style = if (large) MaterialTheme.typography.displayMedium else MaterialTheme.typography.displaySmall,
            color = accent ?: MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (caption != null) {
            Spacer(Modifier.height(2.dp))
            Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Segmented pill selector. */
@Composable
fun <T> ChipRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (option in options) {
            val active = option == selected
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(if (active) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .clickable { onSelect(option) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    label(option),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun KeyValueRow(key: String, value: String, modifier: Modifier = Modifier, valueColor: Color? = null) {
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(value, style = MaterialTheme.typography.titleSmall, color = valueColor ?: MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.End)
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, emoji: String = "✦", action: (@Composable () -> Unit)? = null) {
    val palette = GainsColors.palette
    Column(
        modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(palette.volt.copy(alpha = 0.14f)),
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
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        enabled = enabled,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
    ) { Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold) }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest, contentColor = MaterialTheme.colorScheme.onSurface),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

/** Thin horizontal meter used in the volume table. */
@Composable
fun Meter(fraction: Float, color: Color, modifier: Modifier = Modifier, marker: Float? = null) {
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(600), label = "meter")
    Box(modifier.height(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        Box(Modifier.fillMaxWidth(animated).height(6.dp).clip(CircleShape).background(color))
        if (marker != null) {
            Box(Modifier.fillMaxWidth(marker.coerceIn(0f, 1f)), contentAlignment = Alignment.CenterEnd) {
                Box(Modifier.width(2.dp).height(6.dp).background(MaterialTheme.colorScheme.onSurfaceVariant))
            }
        }
    }
}

/** Small colour dot. */
@Composable
fun Dot(color: Color, size: androidx.compose.ui.unit.Dp = 10.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(color))
}

@Composable
fun RoundedIconBox(color: Color, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
        content = { content() },
    )
}
