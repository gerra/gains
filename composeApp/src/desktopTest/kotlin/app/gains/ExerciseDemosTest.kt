package app.gains

import app.gains.ui.demo.videoSearchUrl
import kotlin.test.Test
import kotlin.test.assertEquals

/** The video search behind "How to do it". */
class ExerciseDemosTest {
    @Test
    fun videoSearchEscapesTheName() {
        assertEquals("https://www.youtube.com/results?search_query=Farmer%27s+Walk+exercise+form", videoSearchUrl("Farmer's Walk"))
        assertEquals("https://www.youtube.com/results?search_query=%D0%96%D0%B8%D0%BC+exercise+form", videoSearchUrl("Жим"))
    }
}
