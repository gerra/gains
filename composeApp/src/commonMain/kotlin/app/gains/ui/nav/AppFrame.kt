package app.gains.ui.nav

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.gains.domain.LiveSession
import app.gains.platform.CsvFilePicker
import app.gains.platform.PhotoPicker
import app.gains.root.UpNext
import app.gains.ui.components.dismissKeyboardOnTap
import app.gains.ui.screens.SyncUi
import app.gains.ui.screens.needsAttention
import app.gains.ui.theme.LocalReduceMotion
import app.gains.ui.theme.Motion

/**
 * The app past its gates: the top bar, the screen on top of [navigator]'s stack, the workout bar and
 * the tabs, with [sync]'s warning under the top bar on Home when it needs attention. The pieces are
 * drawn in `AppChrome.kt` (the bars), `Routes.kt` (the screens) and `ScreenStack.kt` (the screens'
 * moves and the swipe back); what is here is how they are laid out and animated against each other.
 * Out of `App.kt` so that the root only assembles, and this layout sits beside the pieces it lays out.
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
        ScreenStack(navigator, Modifier.weight(1f)) { entry -> ScreenContent(entry, navigator, filePicker, photoPicker, stateHolder) }
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
        AnimatedVisibility(
            visible = live == null && screen == Screen.Home && upNext != null,
            enter = if (reduceMotion) EnterTransition.None else expandVertically(tween(Motion.STANDARD)) + fadeIn(tween(Motion.STANDARD)),
            exit = if (reduceMotion) ExitTransition.None else shrinkVertically(tween(Motion.STANDARD)) + fadeOut(tween(Motion.EXIT)),
        ) {
            // Kept while it folds away after the workout starts, so the button goes with its words.
            var lastUpNext by remember { mutableStateOf(upNext) }
            SideEffect { if (upNext != null) lastUpNext = upNext }
            (upNext ?: lastUpNext)?.let { next ->
                StartBar(next, onStart = { navigator.push(Screen.EditSession(null, next.ref, live = true)) })
            }
        }
        BottomNav(navigator)
    }
}
