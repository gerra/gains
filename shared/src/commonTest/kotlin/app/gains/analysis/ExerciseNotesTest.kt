package app.gains.analysis

import app.gains.domain.ExerciseEntry
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/** The notes on an exercise: what was written in each workout, newest first, and the standing note. */
class ExerciseNotesTest {
    private val bench = TestData.bench
    private val squat = TestData.squat

    private fun note(text: String?) = ExerciseEntry(bench.id, listOf(TestData.weighted(60.0, 5, 0)), text)

    @Test
    fun historyIsNewestFirstAndSkipsEmptyNotes() {
        val first = TestData.session(LocalDate(2026, 10, 1), note("Seat on 4"))
        val blank = TestData.session(LocalDate(2026, 10, 8), note("  "))
        val none = TestData.session(LocalDate(2026, 10, 10), note(null))
        val other = TestData.session(LocalDate(2026, 10, 12), ExerciseEntry(squat.id, emptyList(), "Belt on"))
        val last = TestData.session(LocalDate(2026, 10, 15), note(" Wrists hurt "))
        val history = ExerciseNotes.history(listOf(first, last, blank, none, other), bench.id)
        assertEquals(listOf("Wrists hurt", "Seat on 4"), history.map { it.note })
        assertEquals(LocalDate(2026, 10, 15), history.first().date)
        assertEquals(listOf("Seat on 4"), ExerciseNotes.history(listOf(first, last), bench.id, excludeSessionId = last.id).map { it.note })
    }

    @Test
    fun standingNotesRoundTripAndABlankOneIsRemoved() {
        val notes = ExerciseNotes.withNote(emptyMap(), bench.id, " Seat on 4 ")
        assertEquals(mapOf(bench.id to "Seat on 4"), ExerciseNotes.decode(ExerciseNotes.encode(notes)))
        assertEquals(emptyMap(), ExerciseNotes.withNote(notes, bench.id, " "))
        assertEquals(emptyMap(), ExerciseNotes.decode(null))
        assertEquals(emptyMap(), ExerciseNotes.decode("not json"))
    }
}
