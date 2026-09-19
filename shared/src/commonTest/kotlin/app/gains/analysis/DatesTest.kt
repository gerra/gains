package app.gains.analysis

import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class DatesTest {
    private val now = LocalDateTime(2026, 9, 19, 9, 30)

    @Test
    fun agoReadsLikeASyncLine() {
        assertEquals("just now", Dates.ago(LocalDateTime(2026, 9, 19, 9, 30), now))
        assertEquals("5 min ago", Dates.ago(LocalDateTime(2026, 9, 19, 9, 25), now))
        assertEquals("2 h ago", Dates.ago(LocalDateTime(2026, 9, 19, 7, 10), now))
        assertEquals("yesterday", Dates.ago(LocalDateTime(2026, 9, 18, 23, 50), now))
        assertEquals("1 Sep", Dates.ago(LocalDateTime(2026, 9, 1, 8, 0), now))
        assertEquals("24 Dec 2025", Dates.ago(LocalDateTime(2025, 12, 24, 8, 0), now))
    }
}
