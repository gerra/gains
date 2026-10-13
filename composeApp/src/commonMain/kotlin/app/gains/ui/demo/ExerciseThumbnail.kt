package app.gains.ui.demo

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.gains.platform.decodeImage
import app.gains.resources.Res
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Whether [exerciseId] has a drawing for [ExerciseThumbnail]: the built-in exercises Everkinetic draws. */
internal fun hasThumbnail(exerciseId: String): Boolean = exerciseId in ExerciseDemos.sources

/**
 * The exercise's start position, drawn small in [ink] for a list row (thumb.webp, cut by
 * tools/exercise_demos.py from the same Everkinetic drawing "How to do it" opens on). Nothing is
 * drawn until it is decoded, and nothing at all for an exercise without a drawing; check
 * [hasThumbnail] to show something else in its place.
 */
@Composable
internal fun ExerciseThumbnail(exerciseId: String, ink: Color, modifier: Modifier = Modifier) {
    val image = produceState(ThumbnailCache[exerciseId], exerciseId) {
        if (value != null || !hasThumbnail(exerciseId)) return@produceState
        val decoded = withContext(Dispatchers.Default) {
            runCatching { decodeImage(Res.readBytes("files/exercises/$exerciseId/thumb.webp")) }.getOrNull()
        } ?: return@produceState
        ThumbnailCache[exerciseId] = decoded
        value = decoded
    }.value ?: return
    Image(image, null, modifier.fillMaxSize().padding(2.dp), contentScale = ContentScale.Fit, colorFilter = ColorFilter.tint(ink))
}

/**
 * Decoded thumbnails, kept for the life of the process so a row scrolled back into view shows its
 * drawing at once. Every one of them together is under 10 MB (168x126 each). Touched only on the
 * main thread: produceState reads and writes it there, the decoding alone runs elsewhere.
 */
private object ThumbnailCache {
    private val images = HashMap<String, ImageBitmap>()
    operator fun get(id: String): ImageBitmap? = images[id]
    operator fun set(id: String, image: ImageBitmap) { images[id] = image }
}
