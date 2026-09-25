package app.gains.ui.charts

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.gains.analysis.VolumeAnalyzer
import app.gains.domain.MuscleGroup
import app.gains.resources.Res
import app.gains.resources.*
import app.gains.ui.i18n.*
import org.jetbrains.compose.resources.stringResource
import app.gains.ui.theme.GainsColors
import app.gains.ui.theme.Motion

/** The two figures of the muscle map. */
internal enum class BodySide { FRONT, BACK }

/**
 * A tappable area of the muscle map. The drawing is coarser than [MuscleGroup], so one region can stand
 * for several groups (the deltoid on the front view is both front and side delts), and body parts that
 * are not trained (head, hands) have no groups and are drawn in the resting colour.
 */
internal class BodyRegion internal constructor(
    val side: BodySide,
    /** Region name as in the upstream drawing, e.g. `upper-back`. */
    val slug: String,
    val groups: List<MuscleGroup>,
    /** The region's paths in figure coordinates, 0..[BodyMapPaths.WIDTH] × 0..[BodyMapPaths.HEIGHT]. */
    internal val paths: List<Path>,
) {
    internal val bounds: Rect = paths.map { it.getBounds() }.reduce { a, b ->
        Rect(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom))
    }

    /** Whether the point (in figure coordinates) lies inside one of the region's paths. */
    fun contains(x: Float, y: Float): Boolean {
        if (!bounds.contains(Offset(x, y))) return false
        val probe = Path().apply { addRect(Rect(x - 0.5f, y - 0.5f, x + 0.5f, y + 0.5f)) }
        return paths.any { path -> Path().apply { op(path, probe, PathOperation.Intersect) }.isEmpty.not() }
    }
}

/** The regions of the muscle map and the mapping from the drawing's region names to [MuscleGroup]s. */
internal object BodyMapModel {
    /** Figure units between the front and the back figure when both are drawn side by side. */
    const val FIGURE_GAP = 40f
    const val TOTAL_WIDTH = BodyMapPaths.WIDTH * 2 + FIGURE_GAP
    const val TOTAL_HEIGHT = BodyMapPaths.HEIGHT

    /** Which groups a region stands for. Regions the app does not track (adductors, tibialis, head…) map to none. */
    fun groupsFor(side: BodySide, slug: String): List<MuscleGroup> = when (slug) {
        "chest" -> listOf(MuscleGroup.CHEST)
        // The drawing has one deltoid per view; the side head is visible from both.
        "deltoids" -> if (side == BodySide.FRONT) listOf(MuscleGroup.FRONT_DELTS, MuscleGroup.SIDE_DELTS)
        else listOf(MuscleGroup.REAR_DELTS, MuscleGroup.SIDE_DELTS)
        "trapezius" -> listOf(MuscleGroup.TRAPS)
        "neck" -> listOf(MuscleGroup.NECK)
        "biceps" -> listOf(MuscleGroup.BICEPS)
        "triceps" -> listOf(MuscleGroup.TRICEPS)
        "forearm" -> listOf(MuscleGroup.FOREARMS)
        "abs", "obliques" -> listOf(MuscleGroup.CORE)
        "upper-back" -> listOf(MuscleGroup.UPPER_BACK)
        "lats" -> listOf(MuscleGroup.LATS)
        "lower-back" -> listOf(MuscleGroup.LOWER_BACK)
        "gluteal" -> listOf(MuscleGroup.GLUTES)
        "quadriceps" -> listOf(MuscleGroup.QUADS)
        "hamstring" -> listOf(MuscleGroup.HAMSTRINGS)
        "calves" -> listOf(MuscleGroup.CALVES)
        else -> emptyList()
    }

    /** Every region of both figures; parsed once, on first use. */
    val regions: List<BodyRegion> by lazy {
        fun build(side: BodySide, paths: List<BodyRegionPath>, offsetX: Float) =
            paths.groupBy { it.slug }.map { (slug, parts) ->
                BodyRegion(side, slug, groupsFor(side, slug), parts.map { parse(it.d, offsetX) })
            }
        build(BodySide.FRONT, BodyMapPaths.front, 0f) + build(BodySide.BACK, BodyMapPaths.back, -BodyMapPaths.BACK_OFFSET_X)
    }

    internal val frontOutline: Path by lazy { parse(BodyMapPaths.frontOutline, 0f) }
    internal val backOutline: Path by lazy { parse(BodyMapPaths.backOutline, -BodyMapPaths.BACK_OFFSET_X) }

    /** The region under a point given in figure coordinates of [side], or null over bare body or background. */
    fun regionAt(side: BodySide, x: Float, y: Float): BodyRegion? =
        regions.firstOrNull { it.side == side && it.contains(x, y) }

    /** Regions that stand for [group], in drawing order. */
    fun regionsOf(group: MuscleGroup): List<BodyRegion> = regions.filter { group in it.groups }

    private fun parse(d: String, offsetX: Float): Path =
        PathParser().parsePathString(d).toPath().apply { if (offsetX != 0f) translate(Offset(offsetX, 0f)) }
}

/**
 * Front and back of a body, each muscle group shaded by how many working sets it got: nothing stays the
 * resting body colour, [maxSets] and above is the full accent. Tapping a region reports it through
 * [onRegionTap]; the regions in [selected] are outlined.
 *
 * Fills one hue from light to dark (dark to light on the dark theme) so shade reads as magnitude; the
 * status colours of the volume list are deliberately not used here, they carry meaning of their own.
 */
@Composable
internal fun BodyMap(
    sets: Map<MuscleGroup, Double>,
    modifier: Modifier = Modifier,
    maxSets: Double = VolumeAnalyzer.JUNK_SETS,
    selected: Set<MuscleGroup> = emptySet(),
    onRegionTap: ((BodyRegion) -> Unit)? = null,
) {
    val palette = GainsColors.palette
    val resting = bodyRestingColor()
    val accent = palette.volt
    val outline = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (palette.isDark) 0.45f else 0.35f)
    val highlight = MaterialTheme.colorScheme.onSurface
    val tap by rememberUpdatedState(onRegionTap)
    val regions = remember { BodyMapModel.regions }

    // The shading washes in over the resting body when it is first shown, and from one set of
    // numbers to the next when they change (another week picked), rather than cutting between them.
    var from by remember { mutableStateOf<Map<MuscleGroup, Double>>(emptyMap()) }
    var to by remember { mutableStateOf<Map<MuscleGroup, Double>>(emptyMap()) }
    val shade = remember { Animatable(1f) }
    val revealSpec = Motion.reveal<Float>()
    LaunchedEffect(sets) {
        if (sets == to) return@LaunchedEffect
        from = to
        to = sets
        shade.snapTo(0f)
        shade.animateTo(1f, revealSpec)
    }
    // A muscle picked on the map is outlined with a quick fade rather than a blink.
    val ring = remember { Animatable(1f) }
    val ringSpec = Motion.standard<Float>()
    LaunchedEffect(selected) {
        if (selected.isEmpty()) return@LaunchedEffect
        ring.snapTo(0f)
        ring.animateTo(1f, ringSpec)
    }

    fun fillFor(region: BodyRegion, sets: Map<MuscleGroup, Double>): Color {
        val value = region.groups.maxOfOrNull { sets[it] ?: 0.0 } ?: 0.0
        if (value <= 0.0) return resting
        val t = (value / maxSets).coerceIn(0.0, 1.0).toFloat()
        // A floor so that a single set is visibly different from nothing at all.
        return lerp(resting, accent, 0.25f + 0.75f * t)
    }

    Canvas(
        modifier
            .aspectRatio(BodyMapModel.TOTAL_WIDTH / BodyMapModel.TOTAL_HEIGHT)
            .semantics { contentDescription = "Muscle map" } // A fixed handle for the tests, never shown.
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val scale = size.width / BodyMapModel.TOTAL_WIDTH
                    val backStart = (BodyMapPaths.WIDTH + BodyMapModel.FIGURE_GAP) * scale
                    val (side, dx) = if (offset.x < backStart) BodySide.FRONT to 0f else BodySide.BACK to backStart
                    val region = BodyMapModel.regionAt(side, (offset.x - dx) / scale, offset.y / scale) ?: return@detectTapGestures
                    if (region.groups.isNotEmpty()) tap?.invoke(region)
                }
            },
    ) {
        val scale = size.width / BodyMapModel.TOTAL_WIDTH
        val thin = Stroke(width = 1.2.dp.toPx() / scale)
        val thick = Stroke(width = 2.dp.toPx() / scale)
        for (side in BodySide.entries) {
            val dx = if (side == BodySide.FRONT) 0f else (BodyMapPaths.WIDTH + BodyMapModel.FIGURE_GAP) * scale
            withTransform({
                translate(left = dx)
                scale(scale, scale, pivot = Offset.Zero)
            }) {
                for (region in regions) {
                    if (region.side != side) continue
                    val fill = lerp(fillFor(region, from), fillFor(region, to), shade.value)
                    for (path in region.paths) {
                        drawPath(path, fill)
                        drawPath(path, outline, style = thin)
                    }
                }
                drawPath(if (side == BodySide.FRONT) BodyMapModel.frontOutline else BodyMapModel.backOutline, outline, style = thin)
                if (selected.isNotEmpty()) {
                    for (region in regions) {
                        if (region.side != side || region.groups.none { it in selected }) continue
                        for (path in region.paths) drawPath(path, highlight.copy(alpha = highlight.alpha * ring.value), style = thick)
                    }
                }
            }
        }
    }
}

/** Colour of untrained body on the current theme: a step away from the card so the figure has a silhouette. */
@Composable
internal fun bodyRestingColor(): Color = if (GainsColors.palette.isDark) Color(0xFF2B3140) else Color(0xFFDDE2EA)

/** The scale of a [BodyMap]: resting colour to full accent over 0..[maxSets] working sets. */
@Composable
internal fun BodyMapLegend(maxSets: Double = VolumeAnalyzer.JUNK_SETS, modifier: Modifier = Modifier) {
    val resting = bodyRestingColor()
    val accent = GainsColors.palette.volt
    Row(modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("0", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(
            Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp))
                .background(Brush.horizontalGradient(listOf(resting, lerp(resting, accent, 0.25f), accent))),
        )
        Text(stringResource(Res.string.legend_max_sets, maxSets.toInt()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(2.dp))
    }
}
