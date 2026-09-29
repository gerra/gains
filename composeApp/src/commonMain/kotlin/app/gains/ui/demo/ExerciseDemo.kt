package app.gains.ui.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import app.gains.domain.Exercise
import app.gains.resources.Res
import app.gains.resources.demo_of
import app.gains.resources.watch_video
import app.gains.resources.watch_video_note
import app.gains.ui.components.Dp16
import app.gains.ui.components.GainsCard
import app.gains.ui.components.SecondaryButton
import app.gains.ui.i18n.displayName
import app.gains.ui.theme.GainsColors
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * How an exercise is done: a link to a video search. The start and end photos that used to sit
 * above it came from free-exercise-db, whose images were scraped off the internet by the project it
 * took them from, so nobody could license them; they were dropped when Gains went open source
 * (docs/launch-plan.md, item 39, and NOTICE.md). Photos can come back here once there is a set whose rights are clear.
 */
@Composable
internal fun ExerciseDemoCard(exercise: Exercise, modifier: Modifier = Modifier) {
    GainsCard(modifier.fillMaxWidth(), contentPadding = Dp16.Tight) {
        ExerciseDemo(exercise)
    }
}

/** The same demo in a bottom sheet, for the workout editor. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExerciseDemoSheet(exercise: Exercise, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp).navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.demo_of, exercise.displayName()), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable { scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() } },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.Close, null, modifier = Modifier.size(18.dp)) }
            }
            Spacer(Modifier.height(12.dp))
            ExerciseDemo(exercise)
        }
    }
}

@Composable
private fun ExerciseDemo(exercise: Exercise) {
    val uriHandler = LocalUriHandler.current
    val name = exercise.displayName()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SecondaryButton(stringResource(Res.string.watch_video), onClick = { uriHandler.openUri(videoSearchUrl(name)) })
        Icon(Icons.Default.PlayArrow, null, tint = GainsColors.palette.volt, modifier = Modifier.size(18.dp))
        Text(stringResource(Res.string.watch_video_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
    }
}

/** A YouTube search for the exercise; the browser or the YouTube app takes it from there. */
internal fun videoSearchUrl(exerciseName: String): String =
    "https://www.youtube.com/results?search_query=" + percentEncode("$exerciseName exercise form")

private fun percentEncode(text: String): String = buildString {
    for (byte in text.encodeToByteArray()) {
        val c = byte.toInt() and 0xFF
        val ch = c.toChar()
        when {
            ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '_' || ch == '.' || ch == '~' -> append(ch)
            ch == ' ' -> append('+')
            else -> { append('%'); append(HEX[c shr 4]); append(HEX[c and 0xF]) }
        }
    }
}

private const val HEX = "0123456789ABCDEF"
