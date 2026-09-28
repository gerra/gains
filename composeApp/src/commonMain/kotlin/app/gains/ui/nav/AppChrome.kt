package app.gains.ui.nav

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gains.analysis.Format
import app.gains.domain.LiveSession
import app.gains.resources.Res
import app.gains.resources.*
import app.gains.root.UpNext
import app.gains.ui.components.GainsWordmark
import app.gains.ui.components.indicatorSlot
import app.gains.ui.components.rememberSlidingIndicator
import app.gains.ui.components.slidingIndicator
import app.gains.ui.nowMs
import app.gains.ui.theme.GainsColors
import app.gains.ui.theme.LocalReduceMotion
import app.gains.ui.theme.Motion
import app.gains.ui.theme.fadeThrough
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

// The app's chrome around the screen on top of the stack: the top bar, the workout bar and the tabs.
// Kept out of App.kt so that file is left with the assembly and none of the drawing.

/**
 * The bar above every screen: the wordmark or the back arrow, the "+" menu (with [upNext] offered
 * first when the active program has one) and the way to Settings, each hidden on the screen it
 * would only lead back to.
 */
@Composable
internal fun TopBar(navigator: Navigator, screen: Screen, upNext: UpNext?) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val reduce = LocalReduceMotion.current
        // The wordmark and the back arrow hand over to each other while the screen below slides.
        AnimatedContent(navigator.canGoBack, transitionSpec = { fadeThrough(reduce) }, contentAlignment = Alignment.CenterStart) { canGoBack ->
            if (canGoBack) {
                Row {
                    IconCircle(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.back)) { navigator.pop() }
                    Spacer(Modifier.size(8.dp))
                }
            } else {
                GainsWordmark(Modifier.padding(start = 4.dp))
            }
        }
        Spacer(Modifier.weight(1f))
        val iconEnter = if (reduce) EnterTransition.None else fadeIn(tween(Motion.STANDARD)) + scaleIn(tween(Motion.STANDARD), initialScale = 0.8f)
        val iconExit = if (reduce) ExitTransition.None else fadeOut(tween(Motion.EXIT)) + scaleOut(tween(Motion.EXIT), targetScale = 0.8f)
        // "+" offers both ways of getting a session in; hidden on the screens that already are one of them.
        AnimatedVisibility(screen != Screen.Import && screen !is Screen.EditSession && screen !is Screen.SessionSummary, enter = iconEnter, exit = iconExit) {
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                IconCircle(Icons.Default.Add, stringResource(Res.string.add_description)) { menuOpen = true }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, shape = MaterialTheme.shapes.medium) {
                    if (upNext != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.menu_start_day, upNext.dayName)) },
                            leadingIcon = { Icon(Icons.Default.Star, null, modifier = Modifier.size(18.dp)) },
                            onClick = { menuOpen = false; navigator.push(Screen.EditSession(null, upNext.ref, live = true)) },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.menu_start_workout)) },
                        leadingIcon = { Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp)) },
                        onClick = { menuOpen = false; navigator.push(Screen.EditSession(null, live = true)) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.menu_log_past_workout)) },
                        leadingIcon = { Icon(Icons.Default.Edit, null, modifier = Modifier.size(18.dp)) },
                        onClick = { menuOpen = false; navigator.push(Screen.EditSession(null)) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.menu_import_csv)) },
                        leadingIcon = { Icon(Icons.Default.Share, null, modifier = Modifier.size(18.dp)) },
                        onClick = { menuOpen = false; navigator.push(Screen.Import) },
                    )
                    if (screen != Screen.Programs) {
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.menu_programs)) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.List, null, modifier = Modifier.size(18.dp)) },
                            onClick = { menuOpen = false; navigator.push(Screen.Programs) },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.size(8.dp))
        AnimatedVisibility(screen != Screen.Settings, enter = iconEnter, exit = iconExit) {
            IconCircle(Icons.Default.Settings, stringResource(Res.string.settings_description)) { navigator.push(Screen.Settings) }
        }
    }
}

@Composable
private fun IconCircle(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
    }
}

/** The tabs along the bottom, the current one lit only while it is the screen on show. */
@Composable
internal fun BottomNav(navigator: Navigator) {
    val palette = GainsColors.palette
    // One pill that slides along the bar to the chosen tab, and fades while a screen is pushed on top.
    val indicator = rememberSlidingIndicator(navigator.currentTab?.ordinal?.takeIf { !navigator.canGoBack })
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(6.dp)
                .slidingIndicator(indicator, palette.volt),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            for (tab in Tab.entries) {
                val selected = navigator.currentTab == tab && !navigator.canGoBack
                val interaction = remember { MutableInteractionSource() }
                val content by animateColorAsState(
                    if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    Motion.standard(), label = "tab",
                )
                Column(
                    Modifier
                        .weight(1f)
                        .indicatorSlot(indicator, tab.ordinal)
                        .clip(CircleShape)
                        .clickable(interaction, indication = null) { navigator.switchTab(tab) }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val label = tab.label()
                    Icon(tab.icon(), label, tint = content, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.height(2.dp))
                    Text(label, style = MaterialTheme.typography.labelSmall, color = content)
                }
            }
        }
    }
}

/**
 * The workout in progress, above the tabs: its name, the total time, the rest left, and a tap to get
 * back to it. Both clocks run against wall time, so the bar is right straight after a relaunch.
 */
@Composable
internal fun LiveSessionBar(live: LiveSession, onResume: () -> Unit) {
    val palette = GainsColors.palette
    var now by remember { mutableStateOf(nowMs()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = nowMs() } }
    val remaining = live.rest?.remainingSeconds(now)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(CircleShape)
            .background(palette.volt)
            .clickable(onClick = onResume)
            .padding(horizontal = 18.dp, vertical = 10.dp)
            .animateContentSize(Motion.standard()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val onAccent = MaterialTheme.colorScheme.onPrimary
        Text(live.title, style = MaterialTheme.typography.titleSmall, color = onAccent, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(Format.clock(live.elapsedMs(now) / 1000), style = MaterialTheme.typography.titleSmall, color = onAccent)
        if (remaining != null && remaining > 0) {
            Spacer(Modifier.size(10.dp))
            Text(stringResource(Res.string.rest_countdown, Format.clock(remaining.toLong())), style = MaterialTheme.typography.bodySmall, color = onAccent)
        }
        Spacer(Modifier.size(10.dp))
        Text(stringResource(Res.string.resume_chevron), style = MaterialTheme.typography.labelSmall, color = onAccent)
    }
}

private fun Tab.icon(): ImageVector = when (this) {
    Tab.HOME -> Icons.Default.Home
    Tab.HISTORY -> Icons.Default.DateRange
    Tab.EXERCISES -> Icons.AutoMirrored.Filled.List
    Tab.VOLUME -> Icons.Default.Star
    Tab.BODY -> Icons.Default.Favorite
}
