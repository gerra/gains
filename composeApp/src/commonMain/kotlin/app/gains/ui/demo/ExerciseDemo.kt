package app.gains.ui.demo

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.gains.domain.Exercise
import app.gains.platform.decodeImage
import app.gains.resources.Res
import app.gains.resources.demo_frame_end
import app.gains.resources.demo_frame_start
import app.gains.resources.demo_none
import app.gains.resources.demo_of
import app.gains.resources.demo_start_end
import app.gains.resources.how_to_do_it
import app.gains.resources.watch_video
import app.gains.resources.watch_video_note
import app.gains.ui.components.Dp16
import app.gains.ui.components.GainsCard
import app.gains.ui.components.SecondaryButton
import app.gains.ui.i18n.displayName
import app.gains.ui.theme.GainsColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

/**
 * How an exercise is done: the start and end position from free-exercise-db (public domain),
 * cross-faded into a two-frame loop, with a link to a video search for the rest. Exercises the
 * database has no photos for (custom ones, and a few built-ins) get the video link alone.
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
    val frames = rememberFrames(exercise.id)
    val palette = GainsColors.palette
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val uriHandler = LocalUriHandler.current
    val name = exercise.displayName()
    if (frames != null) {
        FrameLoop(frames, name)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(Res.string.demo_start_end), style = MaterialTheme.typography.bodySmall, color = muted)
    } else if (exercise.id !in ExerciseDemos.sources) {
        Text(stringResource(Res.string.demo_none), style = MaterialTheme.typography.bodySmall, color = muted)
    } else {
        // Loading; keep the card's height close to what the photo will need so the page does not jump.
        Spacer(Modifier.fillMaxWidth().aspectRatio(3f / 2f))
    }
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SecondaryButton(stringResource(Res.string.watch_video), onClick = { uriHandler.openUri(videoSearchUrl(name)) })
        Icon(Icons.Default.PlayArrow, null, tint = palette.volt, modifier = Modifier.size(18.dp))
        Text(stringResource(Res.string.watch_video_note), style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.weight(1f))
    }
}

private class Frames(val start: ImageBitmap, val end: ImageBitmap?)

/** Both frames decoded off the main thread; null until they are, and for exercises without photos. */
@Composable
private fun rememberFrames(exerciseId: String): Frames? {
    val hasDemo = exerciseId in ExerciseDemos.sources
    return produceState<Frames?>(null, exerciseId) {
        if (!hasDemo) return@produceState
        value = withContext(Dispatchers.Default) {
            runCatching {
                val start = decodeImage(Res.readBytes("files/exercises/$exerciseId/0.webp"))
                val end = runCatching { decodeImage(Res.readBytes("files/exercises/$exerciseId/1.webp")) }.getOrNull()
                Frames(start, end)
            }.getOrNull()
        }
    }.value
}

/** Holds the start position, fades to the end position, holds, fades back. A tap freezes the loop where it is; another resumes it. */
@Composable
private fun FrameLoop(frames: Frames, name: String) {
    val transition = rememberInfiniteTransition(label = "demo")
    val animated by transition.animateFloat(
        initialValue = 0f, targetValue = 0f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = CYCLE_MS
                0f at 0
                0f at HOLD_MS using LinearEasing
                1f at HOLD_MS + FADE_MS using LinearEasing
                1f at HOLD_MS + FADE_MS + HOLD_MS using LinearEasing
                0f at CYCLE_MS
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "frame",
    )
    var frozen by remember { mutableStateOf<Float?>(null) }
    val progress = if (frames.end == null) 0f else frozen ?: animated
    val start = frames.start
    val ratio = start.width.toFloat() / start.height.coerceAtLeast(1)
    val startLabel = stringResource(Res.string.demo_frame_start)
    val endLabel = stringResource(Res.string.demo_frame_end)
    val description = stringResource(Res.string.demo_of, name)
    Box(
        Modifier.fillMaxWidth().aspectRatio(ratio).clip(MaterialTheme.shapes.medium)
            .clickable(enabled = frames.end != null) { frozen = if (frozen == null) animated else null }
            .semantics { contentDescription = description },
    ) {
        Image(start, null, Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
        if (frames.end != null) {
            Image(frames.end, null, Modifier.fillMaxWidth(), contentScale = ContentScale.Fit, alpha = progress)
            Text(
                if (progress < 0.5f) startLabel else endLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f)).padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

private const val HOLD_MS = 900
private const val FADE_MS = 450
private const val CYCLE_MS = 2 * (HOLD_MS + FADE_MS)

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
