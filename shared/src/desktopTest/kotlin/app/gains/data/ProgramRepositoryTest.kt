package app.gains.data

import app.gains.catalogue.ProgramCatalogue
import app.gains.db.GainsDatabase
import app.gains.domain.Experience
import app.gains.domain.ExerciseEntry
import app.gains.domain.ExerciseSlot
import app.gains.domain.Goal
import app.gains.domain.GoalProfile
import app.gains.domain.Program
import app.gains.domain.ProgramDay
import app.gains.domain.ProgramDayRef
import app.gains.domain.ProgressionRule
import app.gains.domain.RepTarget
import app.gains.domain.Session
import app.gains.domain.SetEntry
import app.gains.domain.SetType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProgramRepositoryTest {
    private fun newDb(): GainsDatabase = GainsDatabase(DesktopDriverFactory(file = null).createDriver())

    @Test
    fun customProgramRoundTrip() = runTest {
        val db = newDb()
        val settings = SettingsRepository(db, Dispatchers.Unconfined)
        val repo = ProgramRepository(db, settings, Dispatchers.Unconfined)

        val custom = Program(
            id = "custom_1", name = "Mine", description = "test", goals = setOf(Goal.BUILD_MUSCLE), level = Experience.INTERMEDIATE,
            daysPerWeek = 2, isBuiltIn = false,
            days = listOf(
                ProgramDay("custom_1/d0", "Day A", listOf(
                    ExerciseSlot("bench_press", 3, RepTarget.Range(8, 12), progression = ProgressionRule.DoubleProgression(8, 12, 2.5), note = "slow"),
                    ExerciseSlot("squat", 5, RepTarget.Fixed(5), lastSetAmrap = true, progression = ProgressionRule.Linear(5.0, 10.0)),
                )),
                ProgramDay("custom_1/d1", "Day B", listOf(ExerciseSlot("deadlift", 1, RepTarget.Amrap(5)))),
            ),
        )
        repo.upsert(custom)
        val programs = repo.observePrograms().first()
        assertEquals(ProgramCatalogue.builtIn.size + 1, programs.size)
        assertEquals(custom, programs.last())

        // Editing keeps the id and replaces the days.
        repo.upsert(custom.copy(name = "Mine v2", days = custom.days.take(1)))
        val edited = repo.observeCustomPrograms().first().single()
        assertEquals("Mine v2", edited.name)
        assertEquals(1, edited.days.size)

        repo.setActive("custom_1")
        repo.setProfile(GoalProfile(Goal.LOSE_FAT, Experience.BEGINNER, 4))
        val state = repo.observeState().first()
        assertEquals("custom_1", state.active?.id)
        assertEquals(GoalProfile(Goal.LOSE_FAT, Experience.BEGINNER, 4), state.profile)

        repo.delete("custom_1")
        assertTrue(repo.observeCustomPrograms().first().isEmpty())
        assertNull(repo.observeActiveProgramId().first())
    }

    @Test
    fun duplicateOfBuiltInIsEditable() = runTest {
        val db = newDb()
        val repo = ProgramRepository(db, SettingsRepository(db, Dispatchers.Unconfined), Dispatchers.Unconfined)
        val copy = repo.duplicate(ProgramCatalogue.byId("gzclp")!!, "My GZCLP")
        assertFalse(copy.isBuiltIn)
        assertTrue(copy.id.startsWith("custom_"))
        assertTrue(copy.days.all { it.id.startsWith(copy.id + "/") })
        repo.upsert(copy)
        assertEquals(copy, repo.observeCustomPrograms().first().single())
    }

    @Test
    fun builtInsAreReadOnly() = runTest {
        val db = newDb()
        val repo = ProgramRepository(db, SettingsRepository(db, Dispatchers.Unconfined), Dispatchers.Unconfined)
        val result = runCatching { repo.upsert(ProgramCatalogue.builtIn.first()) }
        assertTrue(result.isFailure)
    }

    @Test
    fun sessionKeepsItsProgramLink() = runTest {
        val db = newDb()
        val sessions = SessionRepository(db, Dispatchers.Unconfined)
        val ts = LocalDateTime(2026, 4, 1, 18, 0)
        val ref = ProgramDayRef("gzclp", "gzclp/a1")
        sessions.upsert(Session(ts.toString(), ts, 45, listOf(ExerciseEntry("squat", listOf(SetEntry(0, SetType.WEIGHTED, 60.0, 3)))), Session.MANUAL, ref))
        sessions.upsert(Session("plain", ts, null, listOf(ExerciseEntry("squat", listOf(SetEntry(0, SetType.WEIGHTED, 60.0, 3)))), Session.MANUAL))
        assertEquals(ref, sessions.observeRawSessions().first().first { it.id == ts.toString() }.program)
        val links = sessions.observeProgramLinks().first()
        assertEquals(listOf(ts.toString()), links.map { it.sessionId })
        assertEquals(ref, links.single().ref)
        assertEquals(setOf(ts.toString(), "plain"), sessions.ids())
    }

    @Test
    fun seededCatalogueCarriesEquipment() = runTest {
        val db = newDb()
        val exercises = ExerciseRepository(db, Dispatchers.Unconfined)
        exercises.seedCatalogue()
        val bench = exercises.exercises().first { it.id == "bench_press" }
        assertTrue(bench.equipment.isNotEmpty())
        assertEquals(bench.equipment, exercises.observeExercises().first().first { it.id == "bench_press" }.equipment)
    }
}
