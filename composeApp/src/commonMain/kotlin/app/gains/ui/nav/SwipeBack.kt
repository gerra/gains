package app.gains.ui.nav

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs

/** How far from the left edge a drag may start and still count as "back". */
private val EdgeWidth = 28.dp

/** Dragging past this fraction of the width and letting go pops; anything less springs back. */
private const val CommitFraction = 0.35f

/** A quick flick pops (or cancels) regardless of how far the finger got, in dp per second. */
private val FlingVelocity = 500.dp

/** Width of the soft shadow drawn along the leading edge of the screen being dragged away. */
private val EdgeShadow = 24.dp

/**
 * Interactive back gesture in the style every phone user expects: drag in from the left edge and the
 * current screen follows the finger, revealing the screen underneath; let go past the threshold (or
 * flick) and it slides away and [onBack] is invoked, otherwise it springs back into place.
 *
 * [content] is the current screen. [previous] draws the screen beneath it and is only composed while
 * a drag is in progress. The gesture claims pointer events in the initial pass, so once it has decided
 * the movement is horizontal, lists and other scrollables inside [content] do not fight it; a mostly
 * vertical movement is left entirely to them.
 *
 * [onBack] is called before the drag offset is reset, so the caller should switch [content] to the
 * revealed screen without its own transition; the gesture has already animated it.
 */
@Composable
fun SwipeBack(
    enabled: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    previous: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    // Never below zero: a spring-back with a fast flick must not carry the screen past its resting place.
    val offset = remember { Animatable(0f).apply { updateBounds(lowerBound = 0f) } }
    val scope = rememberCoroutineScope()
    var widthPx by remember { mutableIntStateOf(0) }
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnBack by rememberUpdatedState(onBack)
    val dragging by remember { derivedStateOf { offset.value > 0f } }

    Box(
        modifier
            .onSizeChanged { widthPx = it.width }
            .pointerInput(Unit) {
                val edgePx = EdgeWidth.toPx()
                val flingPx = FlingVelocity.toPx()
                val slop = viewConfiguration.touchSlop
                val velocity = VelocityTracker()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val width = widthPx.toFloat()
                    if (!currentEnabled || width <= 0f || down.position.x > edgePx) return@awaitEachGesture

                    velocity.resetTracking()
                    velocity.addPosition(down.uptimeMillis, down.position)
                    var isDragging = false
                    var totalX = 0f
                    var totalY = 0f
                    // Tracked here rather than read back from [offset]: the snaps are dispatched
                    // asynchronously, so several moves in one frame would otherwise lose deltas.
                    var dragX = 0f
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            if (isDragging) {
                                change.consume()
                                val flick = velocity.calculateVelocity().x
                                val commit = flick > flingPx || (dragX > width * CommitFraction && flick > -flingPx)
                                scope.launch {
                                    if (commit) {
                                        offset.animateTo(width, spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMedium), flick)
                                        // Both happen before the next frame: the revealed screen becomes
                                        // the content at the same moment the drag layer is reset.
                                        currentOnBack()
                                        offset.snapTo(0f)
                                    } else {
                                        offset.animateTo(0f, spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow), flick)
                                    }
                                }
                            }
                            break
                        }
                        if (change.isConsumed) break
                        val delta = change.positionChange()
                        velocity.addPosition(change.uptimeMillis, change.position)
                        if (!isDragging) {
                            totalX += delta.x
                            totalY += delta.y
                            // A mostly vertical movement is a scroll; hand it back to the children untouched.
                            if (abs(totalY) > slop && abs(totalY) > abs(totalX)) break
                            if (totalX > slop && totalX > abs(totalY)) {
                                isDragging = true
                                dragX = (totalX - slop).coerceIn(0f, width)
                            } else {
                                continue
                            }
                        } else {
                            dragX = (dragX + delta.x).coerceIn(0f, width)
                        }
                        val target = dragX
                        scope.launch { offset.snapTo(target) }
                        // While dragging, claim every pointer so nothing underneath reacts to the movement.
                        event.changes.forEach { it.consume() }
                    }
                }
            },
    ) {
        if (dragging) {
            Box(
                Modifier
                    .fillMaxSize()
                    // Parallax: the screen underneath sits slightly to the left and catches up as it is revealed.
                    .graphicsLayer { if (widthPx > 0) translationX = -(1f - offset.value / widthPx) * widthPx * 0.3f }
                    .drawWithContent {
                        drawContent()
                        val progress = if (widthPx > 0) (offset.value / widthPx).coerceIn(0f, 1f) else 0f
                        drawRect(Color.Black, alpha = 0.3f * (1f - progress))
                    },
            ) { previous() }
        }
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = offset.value }
                .drawBehind {
                    if (offset.value <= 0f) return@drawBehind
                    val shadow = EdgeShadow.toPx()
                    drawRect(
                        brush = Brush.horizontalGradient(
                            0f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.25f),
                            startX = -shadow,
                            endX = 0f,
                        ),
                        topLeft = Offset(-shadow, 0f),
                        size = Size(shadow, size.height),
                    )
                },
        ) { content() }
    }
}
