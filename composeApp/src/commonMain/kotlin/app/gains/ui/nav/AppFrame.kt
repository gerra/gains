package app.gains.ui.nav

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import app.gains.domain.LiveSession
import app.gains.platform.CsvFilePicker
import app.gains.platform.PhotoPicker
import app.gains.root.UpNext
import app.gains.ui.components.dismissKeyboardOnTap
import app.gains.ui.screens.SyncUi
import app.gains.ui.screens.needsAttention
import app.gains.ui.theme.LocalReduceMotion
import app.gains.ui.theme.Motion
import app.gains.ui.theme.screenSlide

/**
 * The app past its gates: the top bar, the screen on top of [navigator]'s stack, the workout bar and
 * the tabs, with [sync]'s warning under the top bar on Home when it needs attention. The pieces are
 * drawn in `AppChrome.kt` (the bars) and `Routes.kt` (the screens); what is here is how they are
 * laid out and animated against each other. Out of `App.kt` so that the root only assembles, and
 * this layout sits beside the pieces it lays out.
 */
@Composable
internal fun AppFrame(
    navigator: Navigator,
    stateHolder: SaveableStateHolder,
    filePicker: CsvFilePicker,
    photoPicker: PhotoPicker,
    upNext: UpNext?,
    live: LiveSession?,
    sync: SyncUi,
    onRetrySync: () -> Unit,
) {
    val screen = navigator.current
    // Tapping outside a text field anywhere in the app puts the keyboard away.
    Column(Modifier.fillMaxSize().statusBarsPadding().dismissKeyboardOnTap()) {
        TopBar(navigator, screen, upNext, syncAttention = sync.needsAttention)
        val reduceMotion = LocalReduceMotion.current
        // Kept while it folds away, like the workout bar below, so the last words go with it.
        var lastSync by remember { mutableStateOf(sync) }
        SideEffect { if (sync.needsAttention) lastSync = sync }
        AnimatedVisibility(
            visible = sync.needsAttention && screen == Screen.Home,
            enter = if (reduceMotion) EnterTransition.None else expandVertically(tween(Motion.STANDARD)) + fadeIn(tween(Motion.STANDARD)),
            exit = if (reduceMotion) ExitTransition.None else shrinkVertically(tween(Motion.STANDARD)) + fadeOut(tween(Motion.EXIT)),
        ) {
            SyncBanner(
                if (sync.needsAttention) sync else lastSync,
                onOpenSettings = { navigator.push(Screen.Settings) },
                onRetry = onRetrySync,
            )
        }
        val top = navigator.currentEntry
        val transition = updateTransition(top, label = "screen")
        val swipe = rememberSwipeBackState()
        Box(
            Modifier.weight(1f).clipToBounds().swipeBack(
                swipe,
                // While a screen is still sliding out it is on screen already; the swipe would draw it a second time.
                enabled = navigator.canGoBack && !transition.isRunning && transition.currentState === transition.targetState,
                onBack = { navigator.pop(animated = false) },
            ),
        ) {
            // The swipe-back gesture has already slid the old screen away, so it goes at once. Otherwise
            // the new screen comes in the way the lifter went: deeper or to a tab on the right from the
            // right, back or left from the left.
            val cut = navigator.skipTransition || reduceMotion
            val beneath = if (swipe.dragging) navigator.previousEntry else null
            val leaving = transition.currentState.takeIf { it !== top && !cut }
            // Every screen is drawn from this one loop, keyed by its entry, whether it is on top, sliding
            // in or out, or uncovered by a swipe. The keys its saved state (a list's scroll position) is
            // kept under come from where it sits in the composition, so drawn anywhere else it would come
            // back as new. The last one drawn is on top.
            for (entry in listOfNotNull(beneath, leaving, top).distinct()) key(entry.id) {
                val layer = when (entry) {
                    beneath -> Modifier.beneathSwipe(swipe)
                    top -> Modifier.draggedBySwipe(swipe)
                    else -> Modifier
                }
                Box(Modifier.fillMaxSize().screenSlide(transition, entry, forward = navigator.direction > 0, cut = cut).then(layer)) {
                    ScreenContent(entry, navigator, filePicker, photoPicker, stateHolder)
                }
            }
        }
        // The bar rises in when a workout starts and folds away when it ends, rather than shoving the tabs.
        // The workout it last showed, so the bar can still be drawn while it folds away after the workout ends.
        var lastLive by remember { mutableStateOf(live) }
        SideEffect { if (live != null) lastLive = live }
        val shownLive = live ?: lastLive
        AnimatedVisibility(
            visible = live != null && !(screen is Screen.EditSession && screen.live),
            enter = if (reduceMotion) EnterTransition.None else expandVertically(tween(Motion.STANDARD)) + fadeIn(tween(Motion.STANDARD)),
            exit = if (reduceMotion) ExitTransition.None else shrinkVertically(tween(Motion.STANDARD)) + fadeOut(tween(Motion.EXIT)),
        ) {
            shownLive?.let { running ->
                LiveSessionBar(running, onResume = { navigator.push(Screen.EditSession(null, running.program, live = true)) })
            }
        }
        BottomNav(navigator)
    }
}
