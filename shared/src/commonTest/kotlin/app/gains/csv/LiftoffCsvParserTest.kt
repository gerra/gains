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
        val bench = parsed.sessions.first { it.id == "2026-02-18T20:40:47" }.exercises.first { it.name == "Bench Press" }
        assertEquals(listOf(20.0, 50.0, 60.0), bench.sets.map { it.weightKg })
        assertEquals(listOf(20, 12, 8), bench.sets.map { it.reps })
        assertTrue(bench.sets.all { it.type == SetType.WEIGHTED })

        val raise = parsed.sessions.first { it.id == "2026-02-18T20:40:47" }.exercises.first { it.name == "Dumbbell Lateral Raise" }
        assertEquals(6.0, raise.sets.first().weightKg)

        val legPress = parsed.sessions.first { it.id == "2026-01-13T22:04:26" }.exercises.single()
        assertEquals(53.0, legPress.sets.single().weightKg)
        assertEquals("+53kg", legPress.note)

        val march = parsed.sessions.first { it.id == "2026-03-21T13:29:26" }
        val hang = march.exercises.first { it.name == "Dead Hang" }.sets.single()
        assertEquals(SetType.ISOMETRIC, hang.type)
        assertEquals(60, hang.seconds)
        assertNull(hang.weightKg)
        val pullUp = march.exercises.first { it.name == "Pull Up" }.sets.single()
        assertEquals(SetType.BODYWEIGHT, pullUp.type)
        assertEquals(8, pullUp.reps)

        val run = parsed.sessions.first { it.id == "2026-04-27T17:22:29" }.exercises.single().sets.single()
        assertEquals(SetType.CARDIO, run.type)
        assertEquals(6.437376, run.distanceKm)
        assertEquals(1980, run.seconds)
    }

    @Test
    fun sortsSessionsChronologicallyRegardlessOfFileOrder() {
        val parsed = parser.parse(Fixtures.OUT_OF_ORDER)
        assertEquals(
            listOf("2023-01-05T09:00", "2024-11-20T07:15", "2025-06-01T10:00", "2026-02-10T18:30"),
            parsed.sessions.map { it.id },
        )
        assertEquals(2, parsed.sessions.first().setCount)
    }

    @Test
    fun discardsImplausibleDurations() {
        val parsed = parser.parse(Fixtures.CORRUPT_DURATIONS)
        val byId = parsed.sessions.associateBy { it.id }
        assertNull(byId.getValue("2026-01-01T10:00").durationMinutes)
        assertTrue(byId.getValue("2026-01-01T10:00").durationDiscarded)
        assertNull(byId.getValue("2026-01-02T10:00").durationMinutes)
        assertNull(byId.getValue("2026-01-03T10:00").durationMinutes)
        assertEquals(101, byId.getValue("2026-01-04T10:00").durationMinutes)
        assertNull(byId.getValue("2026-01-05T10:00").durationMinutes)
        assertEquals(false, byId.getValue("2026-01-05T10:00").durationDiscarded)
        assertEquals(240, byId.getValue("2026-01-06T10:00").durationMinutes)
        assertEquals(3, parsed.corruptDurationCount)
    }

    @Test
    fun parsesDurationVariants() {
        assertEquals(101, parser.parseDuration("01 hours 41 minutes 04 seconds"))
        assertEquals(109 * 60 + 36, parser.parseDuration("109 hours 36 minutes"))
        assertEquals(33, parser.parseDuration("00 hours 33 minutes 08 seconds"))
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
        assertEquals("I used one dumbbell, no rest, \"almost\" none", raise.note)
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
        val text = Fixtures.HEADER + "\n2026-02-18 20:40:47,,,Weighted Plank,0,44.092452437,0,0,45,,\n"
        val set = parser.parse(text).sessions.single().exercises.single().sets.single()
        assertEquals(SetType.ISOMETRIC, set.type)
        assertEquals(20.0, set.weightKg)
        assertEquals(45, set.seconds)
    }
}
