package app.gains

import app.gains.catalogue.ExerciseCatalogue
import app.gains.platform.decodeImage
import app.gains.ui.demo.ExerciseDemos
import app.gains.ui.demo.videoSearchUrl
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The generated demo table (tools/exercise_demos.py) against the catalogue and the bundled photos. */
class ExerciseDemosTest {
    private val photos = File("src/commonMain/composeResources/files/exercises")

    @Test
    fun everyDemoIsACatalogueExerciseWithItsPhotosBundled() {
        val ids = ExerciseCatalogue.builtIn.map { it.id }.toSet()
        assertTrue(ExerciseDemos.sources.size > 250, "expected most of the catalogue to have photos, got ${ExerciseDemos.sources.size}")
        for ((id, source) in ExerciseDemos.sources) {
            assertTrue(id in ids, "$id is not a catalogue exercise")
            assertTrue(source.isNotBlank(), "$id has no source name")
            assertTrue(File(photos, "$id/0.webp").isFile, "$id has no start photo")
        }
    }

    @Test
    fun everyBundledPhotoFolderIsInTheTable() {
        val folders = photos.listFiles { f -> f.isDirectory }!!.map { it.name }.toSet()
        assertEquals(emptySet(), folders - ExerciseDemos.sources.keys, "photos without a table entry")
    }

    @Test
    fun photosDecodeAtTheScaledWidth() {
        val bench = decodeImage(File(photos, "bench_press/0.webp").readBytes())
        assertEquals(480, bench.width)
        assertTrue(bench.height in 200..720)
    }

    @Test
    fun videoSearchEscapesTheName() {
        assertEquals("https://www.youtube.com/results?search_query=Farmer%27s+Walk+exercise+form", videoSearchUrl("Farmer's Walk"))
        assertEquals("https://www.youtube.com/results?search_query=%D0%96%D0%B8%D0%BC+exercise+form", videoSearchUrl("Жим"))
    }
}
