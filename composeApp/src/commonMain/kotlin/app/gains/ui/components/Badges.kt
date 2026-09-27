package app.gains.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gains.analysis.AchievementTrack
import app.gains.resources.Res
import app.gains.resources.*
import app.gains.ui.theme.GainsColors
import org.jetbrains.compose.resources.stringResource

/*
 * The trophies as pictures: a medal for a record, a metal disc with the track's emblem for an
 * achievement, and the level in a ring. All drawn on a Canvas from a handful of 24-unit vector
 * paths, so they ship inside the app, scale to any size and take the theme's colours.
 */

/** What a rung's disc is made of: bronze at the bottom of a ladder, platinum at the top. */
internal enum class Metal { LOCKED, BRONZE, SILVER, GOLD, PLATINUM }

/**
 * The metal for rung [tier] of a ladder [size] rungs long: the top rung is platinum, the rest are
 * split into bronze, silver and gold thirds. A one-rung ladder is gold.
 */
internal fun metalFor(tier: Int, size: Int, earned: Boolean): Metal = when {
    !earned -> Metal.LOCKED
    size <= 1 -> Metal.GOLD
    tier >= size -> Metal.PLATINUM
    else -> {
        val f = (tier - 1).toFloat() / (size - 1)
        when { f < 0.34f -> Metal.BRONZE; f < 0.67f -> Metal.SILVER; else -> Metal.GOLD }
    }
}

private class MetalLook(val light: Color, val dark: Color, val rim: Color, val ink: Color)

private fun Metal.look(): MetalLook = when (this) {
    Metal.BRONZE -> MetalLook(Color(0xFFE8A96A), Color(0xFF8B4A1C), Color(0xFFFFD7A8), Color(0xFF3D1C06))
    Metal.SILVER -> MetalLook(Color(0xFFF2F5FA), Color(0xFF8E98A8), Color(0xFFFFFFFF), Color(0xFF2B3240))
    Metal.GOLD -> MetalLook(Color(0xFFFFE27A), Color(0xFFB8860B), Color(0xFFFFF5C2), Color(0xFF4A3300))
    Metal.PLATINUM -> MetalLook(Color(0xFFE6FBFF), Color(0xFF5C9DC4), Color(0xFFFFFFFF), Color(0xFF0E3550))
    Metal.LOCKED -> MetalLook(Color.Transparent, Color.Transparent, Color.Transparent, Color.Transparent)
}

/** Material Design icon outlines (Apache 2.0), in a 24 × 24 box. */
private object Emblems {
    const val DUMBBELL = "M20.57 14.86L22 13.43 20.57 12 17 15.57 8.43 7 12 3.43 10.57 2 9.14 3.43 7.71 2 5.57 4.14 4.14 2.71 2.71 4.14l1.43 1.43L2 7.71l1.43 1.43L2 10.57 3.43 12 7 8.43 15.57 17 12 20.57 13.43 22l1.43-1.43L16.29 22l2.14-2.14 1.43 1.43 1.43-1.43-1.43-1.43L22 16.29z"
    const val FLAME = "M13.5.67s.74 2.65.74 4.8c0 2.06-1.35 3.73-3.41 3.73-2.07 0-3.63-1.67-3.63-3.73l.03-.36C5.21 7.51 4 10.62 4 14c0 4.42 3.58 8 8 8s8-3.58 8-8C20 8.61 17.41 3.8 13.5.67zM11.71 19c-1.78 0-3.22-1.4-3.22-3.14 0-1.62 1.05-2.76 2.81-3.12 1.77-.36 3.6-1.21 4.62-2.58.39 1.29.59 2.65.59 4.04 0 2.65-2.15 4.8-4.8 4.8z"
    const val MOUNTAIN = "M14 6l-3.75 5 2.85 3.8-1.6 1.2C9.81 13.75 7 10 7 10l-6 8h22L14 6z"
    const val STAR = "M12 17.27L18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21z"
    const val TROPHY = "M19 5h-2V3H7v2H5c-1.1 0-2 .9-2 2v1c0 2.55 1.92 4.63 4.39 4.94.63 1.5 1.98 2.63 3.61 2.96V19H7v2h10v-2h-4v-3.1c1.63-.33 2.98-1.46 3.61-2.96C19.08 12.63 21 10.55 21 8V7c0-1.1-.9-2-2-2zM5 8V7h2v3.82C5.84 10.4 5 9.3 5 8zm14 0c0 1.3-.84 2.4-2 2.82V7h2v1z"
    const val MEDAL = "M17 10.43V2H7v8.43c0 .35.18.68.49.86l4.18 2.51-.99 2.34-3.41.29 2.59 2.24L9.07 22 12 20.23 14.93 22l-.78-3.33 2.59-2.24-3.41-.29-.99-2.34 4.18-2.51c.3-.18.48-.51.48-.86zm-4 1.8l-1 .6-1-.6V3h2v9.23z"
    const val RETURN = "M12.5 8c-2.65 0-5.05.99-6.9 2.6L2 7v9h9l-3.62-3.62c1.39-1.16 3.16-1.88 5.12-1.88 3.54 0 6.55 2.31 7.6 5.5l2.37-.78C21.08 11.03 17.15 8 12.5 8z"

    private val cache = HashMap<String, Path>()
    fun path(d: String): Path = cache.getOrPut(d) { PathParser().parsePathString(d).toPath() }
}

private fun AchievementTrack.emblem(): String? = when (this) {
    AchievementTrack.SESSIONS -> Emblems.DUMBBELL
    AchievementTrack.STREAK -> Emblems.FLAME
    AchievementTrack.TONNAGE -> Emblems.MOUNTAIN
    AchievementTrack.RECORDS -> Emblems.STAR
    AchievementTrack.PLATES -> null // Drawn as a plate: rings, not a path.
    AchievementTrack.COMEBACK -> Emblems.RETURN
}

/** Draws [path] (a 24-unit icon) filling [box] centred on [center], in [color]. */
private fun DrawScope.emblem(path: Path, center: Offset, box: Float, color: Color) {
    val k = box / 24f
    translate(center.x - box / 2, center.y - box / 2) {
        scale(k, k, pivot = Offset.Zero) { drawPath(path, color, style = Fill) }
    }
}

/** A weight plate: a disc with a hole and a rim, the emblem of the plate ladders. */
private fun DrawScope.plate(center: Offset, radius: Float, color: Color) {
    drawCircle(color, radius, center, style = Stroke(radius * 0.42f))
    drawCircle(color, radius * 0.14f, center, style = Stroke(radius * 0.12f))
}

/**
 * The disc an achievement is stamped on: a metal with a highlight and a rim, the track's emblem in
 * dark ink on it. Locked, it is an outline on the surface with the emblem faded, so what is still
 * ahead reads as a place kept rather than a prize.
 */
@Composable
internal fun AchievementBadge(track: AchievementTrack, metal: Metal, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    val locked = metal == Metal.LOCKED
    val look = metal.look()
    val outline = MaterialTheme.colorScheme.outline
    val surface = MaterialTheme.colorScheme.surfaceContainerHighest
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val emblemPath = track.emblem()?.let { d -> remember(d) { Emblems.path(d) } }
    Canvas(modifier.size(size)) {
        val r = this.size.minDimension / 2
        val c = Offset(r, r)
        if (locked) {
            drawCircle(surface, r, c)
            drawCircle(outline, r - r * 0.06f, c, style = Stroke(r * 0.09f))
        } else {
            drawCircle(Brush.linearGradient(listOf(look.light, look.dark), Offset(0f, 0f), Offset(r * 2, r * 2)), r, c)
            // A soft highlight off the top left, and the rim that makes it a coin rather than a dot.
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.55f), Color.Transparent), Offset(r * 0.65f, r * 0.6f), r * 0.9f), r, c)
            drawCircle(look.rim.copy(alpha = 0.9f), r - r * 0.09f, c, style = Stroke(r * 0.08f))
            drawCircle(look.dark.copy(alpha = 0.6f), r - r * 0.02f, c, style = Stroke(r * 0.05f))
        }
        val ink = if (locked) muted.copy(alpha = 0.7f) else look.ink
        if (emblemPath != null) emblem(emblemPath, c, r * 1.05f, ink) else plate(c, r * 0.5f, ink)
    }
}

/** The medal on a record: the accent on the surface, at any size from a set row to a heading. */
@Composable
internal fun RecordMedal(modifier: Modifier = Modifier, size: Dp = 24.dp, color: Color? = null) {
    val tint = color ?: GainsColors.palette.volt
    val path = remember { Emblems.path(Emblems.MEDAL) }
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        emblem(path, Offset(s / 2, s / 2), s, tint)
    }
}

/** The trophy on the trophies screen and the summary's records. */
@Composable
internal fun TrophyIcon(modifier: Modifier = Modifier, size: Dp = 24.dp, color: Color? = null) {
    val tint = color ?: GainsColors.palette.volt
    val path = remember { Emblems.path(Emblems.TROPHY) }
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        emblem(path, Offset(s / 2, s / 2), s, tint)
    }
}

/** A medal in a tinted box: the heading of a records card, the way the insight cards head themselves. */
@Composable
internal fun RecordMedalBox(modifier: Modifier = Modifier) {
    val palette = GainsColors.palette
    RoundedIconBox(palette.volt, modifier) { RecordMedal(size = 24.dp) }
}

/** "🏅 2" as a pill: the medal and a count, for a session row that set records. */
@Composable
internal fun RecordCountPill(count: Int, modifier: Modifier = Modifier) {
    val palette = GainsColors.palette
    Row(
        modifier.clip(CircleShape).background(palette.volt.copy(alpha = 0.16f)).padding(start = 6.dp, end = 9.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RecordMedal(size = 13.dp)
        Spacer(Modifier.width(3.dp))
        Text(count.toString(), style = MaterialTheme.typography.labelSmall, color = palette.volt)
    }
}

/**
 * The level in a ring: the number in the middle, the way to the next level drawn around it in the
 * accent. [fraction] is how far into the level the points have got.
 */
@Composable
internal fun LevelRing(level: Int, fraction: Float, modifier: Modifier = Modifier, size: Dp = 64.dp, style: TextStyle = MaterialTheme.typography.headlineMedium) {
    val palette = GainsColors.palette
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val description = stringResource(Res.string.level_label, level)
    Box(modifier.size(size).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = this.size.minDimension * 0.11f
            val inset = stroke / 2
            val arc = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
            drawArc(
                Brush.sweepGradient(listOf(palette.volt.copy(alpha = 0.55f), palette.volt), Offset(this.size.width / 2, this.size.height / 2)),
                -90f, 360f * fraction.coerceIn(0f, 1f), false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        Text(level.toString(), style = style, color = MaterialTheme.colorScheme.onSurface)
    }
}
