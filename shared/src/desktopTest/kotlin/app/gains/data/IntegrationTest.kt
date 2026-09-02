package app.gains.data

import app.gains.analysis.Dates
import app.gains.analysis.InsightEngine
import app.gains.analysis.InsightKind
import app.gains.analysis.TrainingData
import app.gains.analysis.VolumeAnalyzer
import app.gains.csv.Fixtures
import app.gains.csv.LiftoffCsvParser
import app.gains.db.GainsDatabase
import app.gains.domain.BodyweightEntry
import app.gains.domain.MuscleGroup
import app.gains.domain.WeightUnit
import app.gains.importer.ImportAnalyzer
import app.gains.importer.ImportService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.measureTime

/** Exercises the real SQLDelight schema through the repositories with an in-memory SQLite database. */
class IntegrationTest {
    private fun newDb(): GainsDatabase = GainsDatabase(DesktopDriverFactory(file = null).createDriver())

    @Test
    fun importIsAdditiveAndIdempotent() = runTest {
        val db = newDb()
        val sessions = SessionRepository(db, Dispatchers.Unconfined)
        val exercises = ExerciseRepository(db, Dispatchers.Unconfined)
        exercises.seedCatalogue()
        val service = ImportService(sessions, exercises)

        val first = service.preview(Fixtures.SAMPLE)
        assertEquals(4, first.newCount)
        val result = service.commit(first, emptySet())
        assertEquals(4, result.sessionsWritten)

        val again = service.preview(Fixtures.SAMPLE)
        assertEquals(0, again.newCount)
        assertEquals(4, again.unchangedCount)
        assertEquals(0, service.commit(again, emptySet()).sessionsWritten)

        // Overlapping export with one extra session: only the new one is written.
        val extended = Fixtures.SAMPLE + "\n2026-05-01 10:00:00,00 hours 50 minutes 00 seconds,,Bench Press,0,137.788914,8,0,0,,"
        val third = service.preview(extended)
        assertEquals(1, third.newCount)
        service.commit(third, emptySet())

        val snapshot = TrainingData(sessions, exercises).snapshot.first()
        assertEquals(5, snapshot.sessions.size)
        val bench = snapshot.sessions.first { it.id == "2026-02-18T20:40:47" }.exercises.first { it.exerciseId == "bench_press" }
        assertEquals(listOf(true, true, false), bench.sets.map { it.isWarmup })
        assertEquals(60.0, bench.sets.last().weightKg)

        // Working-set override flows through the snapshot.
        exercises.setWorkingSetRatio("bench_press", 0.8)
        val updated = TrainingData(sessions, exercises).snapshot.first()
        val benchAfter = updated.sessions.first { it.id == "2026-02-18T20:40:47" }.exercises.first { it.exerciseId == "bench_press" }
        assertEquals(listOf(true, false, false), benchAfter.sets.map { it.isWarmup })

        // Insights run over the stored data without error.
        val insights = InsightEngine(unit = WeightUnit.KG).generate(updated.sessions, updated.exercises, LocalDate(2026, 5, 15))
        assertTrue(insights.any { it.kind == InsightKind.PROGRESS && it.exerciseId == "bench_press" })

        val volume = VolumeAnalyzer.currentWeek(updated.sessions, updated.exercisesById, LocalDate(2026, 2, 18))
        assertEquals(2.0, volume.sets[MuscleGroup.CHEST])
    }

    @Test
    fun outlierHistoryMergeAndDeleteAll() = runTest {
        val db = newDb()
        val sessions = SessionRepository(db, Dispatchers.Unconfined)
        val exercises = ExerciseRepository(db, Dispatchers.Unconfined)
        exercises.seedCatalogue()
        val service = ImportService(sessions, exercises)

        val preview = service.preview(Fixtures.ISOMETRIC_OUTLIERS)
        assertEquals(3, preview.outliers.size)
        service.commit(preview, emptySet())
        assertEquals(listOf(50, 55, 60), sessions.isometricHistory().getValue("hollow_hold").sorted())

        // A custom exercise merged into a catalogue one re-points history and records an alias.
        val custom = service.preview(Fixtures.HEADER + "\n2026-03-01 10:00:00,,,My Weird Row Variant,0,110.231131093,10,0,0,,\n")
        assertEquals(1, custom.newExercises.size)
        service.commit(custom, emptySet())
        val created = custom.newExercises.single()
        exercises.merge(created.id, "seated_cable_row", created.name)
        val snapshot = TrainingData(sessions, exercises).snapshot.first()
        assertTrue(snapshot.sessions.any { s -> s.exercises.any { it.exerciseId == "seated_cable_row" } })
        assertTrue(snapshot.exercises.none { it.id == created.id })
        assertEquals("seated_cable_row", exercises.aliases()["my weird row variant"])

        val bodyweight = BodyweightRepository(db, Dispatchers.Unconfined)
        bodyweight.upsert(BodyweightEntry(LocalDate(2026, 3, 1), 80.0))
        bodyweight.upsert(BodyweightEntry(LocalDate(2026, 3, 1), 81.0))
        assertEquals(listOf(81.0), bodyweight.observe().first().map { it.weightKg })

        sessions.deleteAll()
        assertEquals(0, TrainingData(sessions, exercises).snapshot.first().sessions.size)
        assertEquals(1, bodyweight.observe().first().size)
    }

    @Test
    fun tenThousandRowImportIsFast() = runTest {
        val csv = buildString {
            appendLine(Fixtures.HEADER)
            val exercisesInFile = listOf("Bench Press", "Squat", "Deadlift", "Lat Pulldown", "Seated Dumbbell Shoulder Press", "Dumbbell Lateral Raise", "Leg Curl", "Plank", "Pull Up", "Running")
            var rows = 0
            var day = LocalDate(2023, 1, 2)
            var session = 0
            while (rows < 10_000) {
                val ts = "$day 18:${(session % 60).toString().padStart(2, '0')}:00"
                for ((i, name) in exercisesInFile.shuffled(kotlin.random.Random(session)).take(6).withIndex()) {
                    repeat(4) { set ->
                        val row = when (name) {
                            "Plank" -> "$ts,01 hours 00 minutes 00 seconds,,$name,$set,0,0,0,${60 + session % 30},,"
                            "Pull Up" -> "$ts,01 hours 00 minutes 00 seconds,,$name,$set,0,${8 + session % 5},0,0,,"
                            "Running" -> "$ts,01 hours 00 minutes 00 seconds,,$name,$set,0,0,${5 + session % 3}.01,1800,,"
                            else -> "$ts,01 hours 00 minutes 00 seconds,,$name,$set,${(88.184904874 + (session % 40) * 5.5 + i)},${6 + set},0,0,,\"note, with comma\""
                        }
                        appendLine(row); rows++
                    }
                }
                session++
                day = Dates.run { day.plusDays(2) }
            }
        }
        val db = newDb()
        val sessions = SessionRepository(db, Dispatchers.Unconfined)
        val exercises = ExerciseRepository(db, Dispatchers.Unconfined)
        exercises.seedCatalogue()
        val service = ImportService(sessions, exercises)

        var preview: app.gains.importer.ImportPreview
        val parseTime = measureTime { preview = service.preview(csv) }
        assertTrue(preview.rowCount >= 10_000)
        val commitTime = measureTime { service.commit(preview, emptySet()) }
        var snapshot: app.gains.analysis.TrainingSnapshot
        val loadTime = measureTime { snapshot = TrainingData(sessions, exercises).snapshot.first() }
        val insightTime = measureTime { InsightEngine().generate(snapshot.sessions, snapshot.exercises, LocalDate(2026, 9, 2)) }
        println("10k rows: parse+analyze ${parseTime}, commit ${commitTime}, load ${loadTime}, insights ${insightTime}")
        assertEquals(preview.newCount, snapshot.sessions.size)
        assertTrue(parseTime.inWholeSeconds < 10, "parsing took $parseTime")
        assertTrue(commitTime.inWholeSeconds < 30, "commit took $commitTime")
        assertTrue(insightTime.inWholeSeconds < 10, "insights took $insightTime")
    }
}
