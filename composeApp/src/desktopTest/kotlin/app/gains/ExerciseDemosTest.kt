package app.gains

import app.gains.catalogue.ExerciseCatalogue
import app.gains.platform.decodeImage
import app.gains.ui.demo.ExerciseDemos
import app.gains.ui.demo.videoSearchUrl
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The generated demo table (tools/exercise_demos.py) against the catalogue and the bundled drawings, and the video search behind "How to do it". */
class ExerciseDemosTest {
    private val drawings = File("src/commonMain/composeResources/files/exercises")

    @Test
    fun everyDemoIsACatalogueExerciseWithBothDrawingsBundled() {
        val ids = ExerciseCatalogue.builtIn.map { it.id }.toSet()
        assertTrue(ExerciseDemos.sources.size > 100, "expected the staples to have drawings, got ${ExerciseDemos.sources.size}")
        for ((id, source) in ExerciseDemos.sources) {
            assertTrue(id in ids, "$id is not a catalogue exercise")
            assertTrue(source.isNotBlank(), "$id has no source")
            assertTrue(File(drawings, "$id/0.webp").isFile, "$id has no start drawing")
            assertTrue(File(drawings, "$id/1.webp").isFile, "$id has no end drawing")
        }
    }

    @Test
    fun everyBundledDrawingFolderIsInTheTable() {
        val folders = drawings.listFiles { f -> f.isDirectory }!!.map { it.name }.toSet()
        assertEquals(emptySet(), folders - ExerciseDemos.sources.keys, "drawings without a table entry")
    }

    @Test
    fun drawingsDecodeAtTheirCanvasSize() {
        for (frame in listOf("0.webp", "1.webp")) {
            val bench = decodeImage(File(drawings, "bench_press/$frame").readBytes())
            assertEquals(480, bench.width)
            assertEquals(360, bench.height)
        }
    }

    @Test
    fun videoSearchEscapesTheName() {
        assertEquals("https://www.youtube.com/results?search_query=Farmer%27s+Walk+exercise+form", videoSearchUrl("Farmer's Walk"))
        assertEquals("https://www.youtube.com/results?search_query=%D0%96%D0%B8%D0%BC+exercise+form", videoSearchUrl("Жим"))
    }
}
