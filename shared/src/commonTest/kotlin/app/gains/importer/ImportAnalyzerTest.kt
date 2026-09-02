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
    fun duplicateDetectionIgnoresExerciseOrderInTheFile() {
        // The second copy lists Lat Pulldown before Bench Press: still the same workout.
        val lines = Fixtures.DUPLICATES.lines()
        val shuffled = listOf(lines[0]) + lines.drop(1).filter { "11:37:12" !in it } +
            lines.drop(1).filter { "11:37:12" in it }.sortedBy { if ("Lat Pulldown" in it) 0 else 1 }
        val p = preview(shuffled.joinToString("\n"))
        assertEquals(1, p.duplicates.size)
        assertEquals("2026-05-02T11:37:12", p.duplicates.single().droppedSessionId)
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
    fun reimportAfterDiscardingOutliersIsUnchanged() {
        val first = preview(Fixtures.ISOMETRIC_OUTLIERS)
        val stored = first.sessionsToCommit(emptySet()).map { summary(it) }
        val again = preview(Fixtures.ISOMETRIC_OUTLIERS, ExistingData(sessions = stored, isometricHistory = mapOf("hollow_hold" to listOf(50, 55, 60))))
        // The 2026-01-08 session was never stored (it only held outliers), so it is new again; the rest are unchanged.
        assertEquals(1, again.newCount)
        assertEquals(0, again.changedCount)
        assertEquals(3, again.unchangedCount)
        assertEquals(0, again.commitCount(emptySet()))
        assertEquals(0, again.sessionsToCommit(emptySet()).size)
        // Keeping a previously discarded hold writes that session again.
        val jan22 = again.outliers.first { it.sessionId == "2026-01-22T10:00" }
        assertEquals(listOf("2026-01-22T10:00"), again.sessionsToCommit(setOf(jan22.key)).map { it.id })
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

class MultiFileImportTest {
    private val parser = LiftoffCsvParser()

    private fun rows(csv: String) = csv.lines().drop(1)

    @Test
    fun sessionsPresentInSeveralFilesAreKeptOnce() {
        // File B is a later export: it repeats everything in A and adds one new session.
        val fileA = Fixtures.SAMPLE
        val fileB = Fixtures.HEADER + "\n" + rows(Fixtures.SAMPLE).joinToString("\n") +
            "\n2026-05-01 10:00:00,01 hours 00 minutes 00 seconds,,Bench Press,0,132.277357311,8,0,0,,"
        val merged = MultiFileMerger.merge(listOf(parser.parse(fileA), parser.parse(fileB)))
        assertEquals(4, merged.sessionsInSeveralFiles)
        assertEquals(5, merged.csv.sessions.size)
        assertEquals(merged.csv.sessions.map { it.id }, merged.csv.sessions.map { it.id }.sorted())

        val preview = ImportAnalyzer().analyze(merged.csv, ExistingData())
        assertEquals(5, preview.newCount)
        assertEquals(0, preview.duplicates.size)
    }

    @Test
    fun fullerCopyOfASessionWins() {
        val fileA = Fixtures.SAMPLE
        val extraSet = "2026-01-13 22:04:26,01 hours 41 minutes 04 seconds,,Sled Leg Press,1,116.84499895805,12,0,0,,+53kg"
        val fileB = Fixtures.SAMPLE + "\n" + extraSet
        val merged = MultiFileMerger.merge(listOf(parser.parse(fileA), parser.parse(fileB)))
        val jan = merged.csv.sessions.first { it.id == "2026-01-13T22:04:26" }
        assertEquals(2, jan.setCount)
    }

    @Test
    fun nearDuplicatesAcrossFilesAreDetected() {
        // The same workout logged twice on one day, but the two copies live in different files.
        val all = rows(Fixtures.DUPLICATES)
        val fileA = Fixtures.HEADER + "\n" + all.filter { "11:37:12" !in it }.joinToString("\n")
        val fileB = Fixtures.HEADER + "\n" + all.filter { "11:37:12" in it }.joinToString("\n")
        val merged = MultiFileMerger.merge(listOf(parser.parse(fileA), parser.parse(fileB)))
        val preview = ImportAnalyzer().analyze(merged.csv, ExistingData())
        assertEquals(1, preview.duplicates.size)
        assertEquals(2, preview.newCount)
    }

    @Test
    fun importingTheSameFilesAgainChangesNothing() {
        val merged = MultiFileMerger.merge(listOf(parser.parse(Fixtures.SAMPLE), parser.parse(Fixtures.DUPLICATES)))
        val first = ImportAnalyzer().analyze(merged.csv, ExistingData())
        val stored = first.sessionsToCommit(emptySet()).map {
            StoredSessionSummary(it.id, it.date, ImportAnalyzer.fingerprint(it), ImportAnalyzer.contentHash(it))
        }
        val again = ImportAnalyzer().analyze(merged.csv, ExistingData(sessions = stored))
        assertEquals(0, again.newCount)
        assertEquals(0, again.changedCount)
        assertEquals(stored.size, again.unchangedCount)
        assertEquals(0, again.commitCount(emptySet()))
    }
}
