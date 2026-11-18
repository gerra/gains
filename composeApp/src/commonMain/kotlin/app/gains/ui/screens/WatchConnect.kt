package app.gains.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.gains.data.SettingsRepository
import app.gains.platform.WatchStatus
import app.gains.resources.Res
import app.gains.resources.*
import app.gains.ui.components.GainsCard
import app.gains.ui.components.Pill
import app.gains.ui.components.SecondaryButton
import app.gains.ui.inject
import app.gains.ui.theme.GainsColors
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/*
 * Getting Gains onto the Apple Watch (docs/watch.md, "Connecting a watch"). Nothing is said to a
 * lifter without a paired watch. With one, Settings always has a card that says where things stand
 * and, until Gains is on the watch, exactly how to put it there; a workout offers the same once,
 * at the moment the watch would help, and remembers being told "Not now". There is no pairing to
 * do in Gains itself: the watch app talks to the phone it is paired with on its own.
 */

/** Settings' Apple Watch card: connected, or the three steps to get there. Shown only with a paired watch. */
@Composable
internal fun WatchSettingsCard(status: WatchStatus, onOpenWatchApp: () -> Unit, modifier: Modifier = Modifier) {
    GainsCard(modifier.fillMaxWidth()) {
        when (status) {
            WatchStatus.INSTALLED -> {
                Pill(stringResource(Res.string.watch_connected), GainsColors.palette.progress, icon = { Icon(Icons.Default.Check, null, tint = it, modifier = Modifier.size(14.dp)) })
                Spacer(Modifier.height(12.dp))
                Text(stringResource(Res.string.watch_connected_body), style = MaterialTheme.typography.bodyMedium)
            }
            WatchStatus.NOT_INSTALLED -> {
                Text(stringResource(Res.string.watch_get_title), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(Res.string.watch_get_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                WatchSteps()
                Spacer(Modifier.height(16.dp))
                SecondaryButton(stringResource(Res.string.watch_open_watch_app), onOpenWatchApp, compact = true)
                Spacer(Modifier.height(12.dp))
                Text(stringResource(Res.string.watch_auto_install_tip), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            WatchStatus.NONE -> Unit
        }
    }
}

/**
 * The once-only offer in a workout, while a watch is paired without Gains on it: what the watch
 * would do for this very workout, a way to the Watch app, and "Not now", which is remembered.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WatchNudgeCard(onOpenWatchApp: () -> Unit, onNotNow: () -> Unit, modifier: Modifier = Modifier) {
    GainsCard(modifier.fillMaxWidth()) {
        Text(stringResource(Res.string.watch_nudge_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(Res.string.watch_nudge_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            SecondaryButton(stringResource(Res.string.watch_open_watch_app), onOpenWatchApp, compact = true)
            TextButton(onClick = onNotNow) { Text(stringResource(Res.string.not_now), color = GainsColors.palette.volt, maxLines = 1) }
        }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(Res.string.watch_nudge_later), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** "1  Open the Watch app on this iPhone." and the two after it. */
@Composable
private fun WatchSteps() {
    val steps = listOf(Res.string.watch_step_open, Res.string.watch_step_install, Res.string.watch_step_start)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        steps.forEachIndexed { i, step ->
            Row {
                Text("${i + 1}", style = MaterialTheme.typography.bodyMedium, color = GainsColors.palette.volt, modifier = Modifier.width(20.dp))
                Text(stringResource(step), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Whether the workout's watch offer is still to be shown, and the "Not now" that puts it away for good. */
internal class WatchNudge(val shown: Boolean, val dismiss: () -> Unit)

@Composable
internal fun rememberWatchNudge(): WatchNudge {
    val settings = inject<SettingsRepository>()
    val scope = rememberCoroutineScope()
    // Assumed dismissed until the setting is read, so the card never flashes up and away.
    val dismissed by remember(settings) { settings.observe(WATCH_NUDGE_DISMISSED) }.collectAsState(initial = "1")
    return WatchNudge(shown = dismissed != "1") { scope.launch { settings.set(WATCH_NUDGE_DISMISSED, "1") } }
}

/** The settings key that remembers "Not now" on the workout's watch offer. */
internal const val WATCH_NUDGE_DISMISSED = "watch_nudge_dismissed"
