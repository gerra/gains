package app.gains.ui.nav

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import app.gains.ui.theme.LocalReduceMotion
import app.gains.ui.theme.screenSlide
import kotlinx.coroutines.flow.first

/**
 * The screens of [navigator]'s stack that are on show, each drawn by [content]: the one on top, any
 * still sliding out after a navigation, and the one underneath while a swipe back uncovers it.
 *
 * They are all drawn from one loop keyed by their entry, the way Decompose's stack animation does
 * it. The keys a screen's saved state (a list's scroll position) is kept under come from where the
 * screen sits in the composition, so a screen drawn anywhere else, such as a slot of its own for the
 * swipe, comes back as new. Each screen comes in and goes out on its own [MutableTransitionState]
 * and stays on show until it has gone, as with `AnimatedContent`, so a navigation in the middle of
 * another lets the screen sliding out finish rather than cutting it off.
 */
@Composable
internal fun ScreenStack(navigator: Navigator, modifier: Modifier = Modifier, content: @Composable (NavEntry) -> Unit) {
    val stage = remember { Stage() }
    val swipe = rememberSwipeBackState()
    val top = navigator.currentEntry
    // The swipe-back gesture has already slid the old screen away, so it goes at once. Otherwise the
    // new screen comes in the way the lifter went: deeper or to a tab on the right from the right,
    // back or left from the left.
    val cut = navigator.skipTransition || LocalReduceMotion.current
    val forward = navigator.direction > 0
    // The screen that was on top until now joins the ones going out, from this very frame.
    val left = stage.top?.takeIf { it !== top && !cut }
    val leaving = (stage.leaving + listOfNotNull(left)).filter { it !== top }.distinct()
    val beneath = if (swipe.dragging) navigator.previousEntry else null
    SideEffect { stage.moveOn(top, left) }
    Box(
        modifier.clipToBounds().swipeBack(
            swipe,
            // While a screen is still sliding in or out the swipe would draw it a second time.
            enabled = { navigator.canGoBack && stage.settled(navigator.currentEntry) },
            onBack = { navigator.pop(animated = false) },
        ),
    ) {
        // Drawn in this order, the last on top.
        for (entry in (listOfNotNull(beneath) + leaving + top).distinct()) key(entry.id) {
            val shown = stage.visibility(entry, initially = cut || stage.top == null || entry === beneath)
            val target = entry === top || entry === beneath
            SideEffect { shown.targetState = target }
            DisposableEffect(Unit) { onDispose { stage.forget(entry) } }
            // Off the stage once it has gone out, unless it came back on top in the meantime.
            LaunchedEffect(shown) {
                snapshotFlow { entry in stage.leaving && shown.isIdle && !shown.currentState }.first { it }
                stage.leaving.remove(entry)
            }
            val layer = when (entry) {
                beneath -> Modifier.beneathSwipe(swipe)
                top -> Modifier.draggedBySwipe(swipe)
                else -> Modifier
            }
            Box(Modifier.fillMaxSize().screenSlide(rememberTransition(shown, label = "screen"), forward).then(layer)) {
                content(entry)
            }
        }
    }
}

/** What [ScreenStack] remembers between frames. */
private class Stage {
    /** The screen on top as of the last frame; null before the first. */
    var top: NavEntry? = null
        private set

    /** Screens no longer on top that are still going out, the oldest first. */
    val leaving = mutableStateListOf<NavEntry>()

    private val visibilities = mutableMapOf<NavEntry, MutableTransitionState<Boolean>>()

    /** Whether [entry] is on show, made the first time it is drawn: shown already when [initially], otherwise to come in. */
    fun visibility(entry: NavEntry, initially: Boolean): MutableTransitionState<Boolean> =
        visibilities.getOrPut(entry) { MutableTransitionState(initially) }

    fun forget(entry: NavEntry) {
        visibilities.remove(entry)
    }

    /** [top] is fully in and nothing else is still going out. */
    fun settled(top: NavEntry): Boolean =
        leaving.isEmpty() && visibilities[top]?.let { it.isIdle && it.currentState } == true

    fun moveOn(top: NavEntry, left: NavEntry?) {
        if (left != null && left !in leaving) leaving += left
        leaving.remove(top)
        this.top = top
    }
}
