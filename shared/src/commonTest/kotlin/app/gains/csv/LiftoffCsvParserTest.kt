package app.gains.csv

import app.gains.domain.SetType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiftoffCsvParserTest {
    private val parser = LiftoffCsvParser()

    @Test
    fun convertsLbsToRoundKgAndClassifiesSetTypes() {
        val parsed = parser.parse(Fixtures.SAMPLE)
        assertEquals(0, parsed.skipped.size)
        val bench = parsed.sessions.first { it.id == "2026-02-24" }.exercises.first { it.name == "Bench Press" }
        assertEquals(listOf(20.0, 57.5, 70.0), bench.sets.map { it.weightKg })
        assertEquals(listOf(15, 10, 6), bench.sets.map { it.reps })
        assertTrue(bench.sets.all { it.type == SetType.WEIGHTED })

        val raise = parsed.sessions.first { it.id == "2026-02-24" }.exercises.first { it.name == "Dumbbell Lateral Raise" }
        assertEquals(8.0, raise.sets.first().weightKg)
        assertEquals("Slow tempo, couldn’t keep the pause", raise.note)

        val legPress = parsed.sessions.first { it.id == "2025-10-02" }.exercises.single()
        assertEquals(40.0, legPress.sets.single().weightKg)
        assertEquals("+40kg", legPress.note)

        val december = parsed.sessions.first { it.id == "2025-12-14" }
        val hang = december.exercises.first { it.name == "Dead Hang" }.sets.single()
        assertEquals(SetType.ISOMETRIC, hang.type)
        assertEquals(45, hang.seconds)
        assertNull(hang.weightKg)
        val pullUp = december.exercises.first { it.name == "Pull Up" }.sets.single()
        assertEquals(SetType.BODYWEIGHT, pullUp.type)
        assertEquals(6, pullUp.reps)

        val run = parsed.sessions.first { it.id == "2026-05-09" }.exercises.single().sets.single()
        assertEquals(SetType.CARDIO, run.type)
        assertEquals(5.2, run.distanceKm)
        assertEquals(1560, run.seconds)
    }

    @Test
    fun sortsSessionsChronologicallyRegardlessOfFileOrder() {
        val parsed = parser.parse(Fixtures.OUT_OF_ORDER)
        assertEquals(
            listOf("2023-01-05", "2024-11-20", "2025-06-01", "2026-02-10"),
            parsed.sessions.map { it.id },
        )
        assertEquals(2, parsed.sessions.first().setCount)
    }

    @Test
    fun discardsImplausibleDurations() {
        val parsed = parser.parse(Fixtures.CORRUPT_DURATIONS)
        val byId = parsed.sessions.associateBy { it.id }
        assertNull(byId.getValue("2026-01-01").durationMinutes)
        assertTrue(byId.getValue("2026-01-01").durationDiscarded)
        assertNull(byId.getValue("2026-01-02").durationMinutes)
        assertNull(byId.getValue("2026-01-03").durationMinutes)
        assertEquals(97, byId.getValue("2026-01-04").durationMinutes)
        assertNull(byId.getValue("2026-01-05").durationMinutes)
        assertEquals(false, byId.getValue("2026-01-05").durationDiscarded)
        assertEquals(240, byId.getValue("2026-01-06").durationMinutes)
        assertEquals(3, parsed.corruptDurationCount)
    }

    @Test
    fun parsesDurationVariants() {
        assertEquals(97, parser.parseDuration("01 hours 37 minutes 12 seconds"))
        assertEquals(130 * 60 + 5, parser.parseDuration("130 hours 05 minutes"))
        assertEquals(27, parser.parseDuration("00 hours 27 minutes 10 seconds"))
        assertEquals(45, parser.parseDuration("45 minutes"))
        assertNull(parser.parseDuration(""))
        assertNull(parser.parseDuration("garbage"))
    }

    @Test
    fun keepsQuotedNotesWithCommasAndDeduplicatesPerExercise() {
        val parsed = parser.parse(Fixtures.QUOTED_NOTES)
        val session = parsed.sessions.single()
        val raise = session.exercises.first { it.name == "Dumbbell Lateral Raise" }
        assertEquals(3, raise.sets.size)
        assertEquals("Slow negatives, \"paused\" at the top", raise.note)
        val bench = session.exercises.first { it.name == "Bench Press" }
        assertEquals("Line one\nline two, with comma", bench.note)
        assertEquals(0, parsed.skipped.size)
    }

    @Test
    fun discardsEmptyRowsWithReasons() {
        val parsed = parser.parse(Fixtures.EMPTY_ROWS)
        assertEquals(1, parsed.sessions.size)
        assertEquals(1, parsed.sessions.single().setCount)
        assertEquals(4, parsed.skipped.size)
        assertTrue(parsed.skipped.all { it.reason == SkipReason.EMPTY_ROW })
        assertEquals(listOf(2, 3, 5, 6), parsed.skipped.map { it.lineNumber })
    }

    @Test
    fun resequencesShuffledSetOrder() {
        val parsed = parser.parse(Fixtures.SHUFFLED_SET_ORDER)
        val sets = parsed.sessions.single().exercises.single().sets
        assertEquals(listOf(0, 1, 2, 3), sets.map { it.order })
        assertEquals(listOf(20.0, 40.0, 60.0, 55.0), sets.map { it.weightKg })
    }

    @Test
    fun handlesCrlfAndParsesRpeWhenPresent() {
        val parsed = parser.parse(Fixtures.CRLF)
        val sets = parsed.sessions.single().exercises.single().sets
        assertEquals(2, sets.size)
        assertEquals(7.5, sets[0].rpe)
        assertNull(sets[1].rpe)
        assertEquals("note, with comma", parsed.sessions.single().exercises.single().note)
    }

    @Test
    fun rejectsFilesWithoutTheExpectedHeader() {
        assertFailsWith<CsvFormatException> { parser.parse("Foo,Bar\n1,2\n") }
        assertFailsWith<CsvFormatException> { parser.parse("") }
    }

    @Test
    fun weightedHoldKeepsWeightAndIsIsometric() {
        val text = Fixtures.HEADER + "\n2026-02-20 19:00:00,,,Weighted Plank,0,44.092452437,0,0,45,,\n"
        val set = parser.parse(text).sessions.single().exercises.single().sets.single()
        assertEquals(SetType.ISOMETRIC, set.type)
        assertEquals(20.0, set.weightKg)
        assertEquals(45, set.seconds)
    }
    @Test
    fun groupsDifferentTimestampsAndEmptyRowsByCalendarDate() {
        val text = Fixtures.HEADER + "\n" +
            "2026-07-06 10:00:00,,,Squat,0,100,5,0,0,,\n" +
            "2026-07-06 20:00:00,,,Bench Press,0,100,5,0,0,,\n" +
            "2026-07-06 21:00:00,,,,0,0,0,0,0,,"
        val parsed = parser.parse(text)
        assertEquals(1, parsed.sessions.size)
        assertEquals("2026-07-06", parsed.sessions.single().id)
        assertEquals(2, parsed.sessions.single().setCount)
        assertEquals(1, parsed.skipped.size)
    }

}
