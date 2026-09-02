package app.gains.importer

import app.gains.csv.Fixtures
import app.gains.csv.LiftoffCsvParser
import app.gains.domain.Session
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImportAnalyzerTest {
    private val parser = LiftoffCsvParser()
    private val analyzer = ImportAnalyzer()

    private fun preview(csv: String, existing: ExistingData = ExistingData()): ImportPreview =
        analyzer.analyze(parser.parse(csv), existing)

    @Test
    fun detectsDuplicateSessionsWithinAFile() {
        val p = preview(Fixtures.DUPLICATES)
        assertEquals(1, p.duplicates.size)
        val dup = p.duplicates.single()
        assertEquals("2026-05-02T11:37:12", dup.droppedSessionId)
        assertEquals("2026-05-02T10:00", dup.keptSessionId)
        assertEquals(LocalDate(2026, 5, 2), dup.date)
        assertEquals(listOf("Bench Press", "Lat Pulldown"), dup.exerciseNames)
        assertEquals(2, p.importable.size)
        assertEquals(2, p.newCount)
        assertEquals(2, p.sessionsToCommit(emptySet()).size)
    }

    @Test
    fun detectsDuplicatesAgainstStoredSessionsAndIsIdempotent() {
        val first = preview(Fixtures.DUPLICATES)
        val stored = first.sessionsToCommit(emptySet()).map { summary(it) }

        val second = preview(Fixtures.DUPLICATES, ExistingData(sessions = stored))
        assertEquals(2, second.unchangedCount)
        assertEquals(0, second.newCount)
        assertEquals(0, second.sessionsToCommit(emptySet()).size)

        // A stored copy at a different timestamp on the same day is still a duplicate.
        val shifted = stored.map { it.copy(id = it.id + "-other") }
        val third = preview(Fixtures.DUPLICATES, ExistingData(sessions = shifted))
        assertEquals(0, third.newCount)
        assertEquals(3, third.duplicates.size)
        assertTrue(third.duplicates.all { it.keptIsAlreadyStored })
    }

    @Test
    fun flagsChangedSessionsForUpdate() {
        val original = preview(Fixtures.SAMPLE).sessionsToCommit(emptySet()).map { summary(it) }
        val edited = Fixtures.SAMPLE + "\n2026-01-13 22:04:26,01 hours 41 minutes 04 seconds,,Sled Leg Press,1,116.84499895805,12,0,0,,+53kg"
        val p = preview(edited, ExistingData(sessions = original))
        assertEquals(1, p.changedCount)
        assertEquals(3, p.unchangedCount)
        assertEquals(listOf("2026-01-13T22:04:26"), p.sessionsToCommit(emptySet()).map { it.id })
    }

    @Test
    fun flagsIsometricOutliersAndDropsThemUnlessConfirmed() {
        val p = preview(Fixtures.ISOMETRIC_OUTLIERS)
        assertEquals(3, p.outliers.size)
        assertTrue(p.outliers.all { it.seconds == 1800 && it.exerciseName == "Hollow Hold" })
        assertEquals(60, p.outliers.first().medianSeconds)

        val discarded = p.sessionsToCommit(emptySet())
        // The 2026-01-08 session only held outliers and disappears entirely.
        assertEquals(listOf("2026-01-01T10:00", "2026-01-15T10:00", "2026-01-22T10:00"), discarded.map { it.id })
        val jan22 = discarded.last()
        assertEquals(listOf("plank"), jan22.exercises.map { it.exerciseId })

        val confirmed = p.sessionsToCommit(p.outliers.map { it.key }.toSet())
        assertEquals(4, confirmed.size)
        assertEquals(2, confirmed[1].setCount)
    }

    @Test
    fun usesStoredHistoryForOutlierMedians() {
        val text = Fixtures.HEADER + "\n2026-03-01 10:00:00,,,Plank,0,0,0,0,1800,,\n"
        val none = preview(text)
        assertEquals(0, none.outliers.size)
        val withHistory = preview(text, ExistingData(isometricHistory = mapOf("plank" to listOf(60, 70, 65))))
        assertEquals(1, withHistory.outliers.size)
    }

    @Test
    fun infersWarmupsAndResolvesAliases() {
        val p = preview(Fixtures.SAMPLE)
        val feb = p.candidates.first { it.session.id == "2026-02-18T20:40:47" }.session
        val bench = feb.exercises.first { it.exerciseId == "bench_press" }
        // 20, 50 and 60 kg: only the 60 kg set is at or above 85% of the top weight (51 kg).
        assertEquals(listOf(true, true, false), bench.sets.map { it.isWarmup })
        assertEquals(listOf("bench_press", "lateral_raise"), feb.exercises.map { it.exerciseId })
        val jan = p.candidates.first { it.session.id == "2026-01-13T22:04:26" }.session
        assertEquals("leg_press", jan.exercises.single().exerciseId)
        assertEquals(0, p.newExercises.size)
    }

    @Test
    fun honoursUserAliasesAndWorkingSetOverrides() {
        val existing = ExistingData(
            userAliases = mapOf("sled leg press" to "hack_squat"),
            workingSetRatios = mapOf("bench_press" to 0.80),
        )
        val p = preview(Fixtures.SAMPLE, existing)
        val jan = p.candidates.first { it.session.id == "2026-01-13T22:04:26" }.session
        assertEquals("hack_squat", jan.exercises.single().exerciseId)
        val bench = p.candidates.first { it.session.id == "2026-02-18T20:40:47" }.session.exercises.first()
        // With an 80% threshold (48 kg) the 50 kg set counts as a working set.
        assertEquals(listOf(true, false, false), bench.sets.map { it.isWarmup })
    }

    @Test
    fun createsCustomExercisesForUnknownNamesWithGuessedMuscles() {
        val text = Fixtures.HEADER + "\n2026-03-01 10:00:00,,,Zercher Squat Hold Thing,0,220.462262185,5,0,0,,\n"
        val p = preview(text)
        assertEquals(1, p.newExercises.size)
        val ex = p.newExercises.single()
        assertEquals("custom_zercher_squat_hold_thing", ex.id)
        assertTrue(ex.muscleGroups.any { it.group == app.gains.domain.MuscleGroup.QUADS })
    }

    @Test
    fun reportsSkippedRowsAndCorruptDurations() {
        val p = preview(Fixtures.EMPTY_ROWS + "\n" + Fixtures.CORRUPT_DURATIONS.lines().drop(1).joinToString("\n"))
        assertEquals(4, p.skippedByReason.values.sum())
        assertEquals(3, p.corruptDurationCount)
        assertEquals(LocalDate(2026, 1, 1)..LocalDate(2026, 2, 18), p.dateRange)
    }

    private fun summary(s: Session) = StoredSessionSummary(
        id = s.id, date = s.date, fingerprint = ImportAnalyzer.fingerprint(s), contentHash = ImportAnalyzer.contentHash(s),
    )
}
