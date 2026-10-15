package app.gains

import app.gains.domain.Caution
import app.gains.domain.RepTarget
import app.gains.domain.SetsReps
import app.gains.domain.SlotReason
import app.gains.ui.screens.PlanTarget

import app.gains.domain.Exercise
import app.gains.domain.ExerciseEntry
import app.gains.program.Gzclp
import app.gains.domain.Modality
import app.gains.domain.RestTimer
import app.gains.domain.SetDraft
import app.gains.domain.SetEntry
import app.gains.domain.SetType
import app.gains.program.Progression
import app.gains.ui.screens.DayDraft
import app.gains.ui.screens.EditorState
import app.gains.ui.screens.ExerciseDraft
import app.gains.ui.screens.PastSession
import app.gains.ui.screens.PreviousSources
import app.gains.ui.screens.ProgressionChoice
import app.gains.ui.screens.SlotDraft
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/** Swapping an exercise for another keeps the work typed against it, and cards move one place at a time. */
class ExerciseArrangementTest {
    private val squat = Exercise("squat", "Squat", "squat", emptyList(), Modality.WEIGHTED)
    private val frontSquat = Exercise("front-squat", "Front Squat", "front squat", emptyList(), Modality.WEIGHTED)
    private val bench = Exercise("bench", "Bench Press", "bench press", emptyList(), Modality.WEIGHTED)
    private val row = Exercise("row", "Barbell Row", "barbell row", emptyList(), Modality.WEIGHTED)

    private val t1 = PlanTarget(SetsReps(5, RepTarget.Amrap(3)), lastSetAmrap = false)
    private val t2 = PlanTarget(SetsReps(3, RepTarget.Fixed(10)), lastSetAmrap = false)
    private val squatSets = listOf(SetDraft("60", "5", isWarmup = true), SetDraft("100", "5", done = true), SetDraft("100", "5"))
    private val state = EditorState(
        loading = false,
        exercises = listOf(ExerciseDraft(squat, squatSets, note = "belt on"), ExerciseDraft(bench, listOf(SetDraft("80", "5")))),
        targets = mapOf("squat" to t1, "bench" to t2),
        reasons = mapOf("squat" to SlotReason.Swapped("deadlift", Caution.LOWER_BACK)),
        hints = mapOf("squat" to Progression.Hint.Try(Progression.LastTime(100.0, listOf(5, 5)), 102.5)),
        notes = mapOf("squat" to "Sit back"),
        tiers = mapOf("squat" to Gzclp.Tier.T1),
        seeded = setOf("squat"),
        sources = mapOf("squat" to Progression.Source.FREE_SESSION),
        collapsedWarmups = setOf("squat"),
        previous = mapOf("squat" to ExerciseEntry("squat", listOf(SetEntry(0, SetType.WEIGHTED, 97.5, 5)))),
        restTimer = RestTimer("squat", endsAtMs = 1_000L, totalSeconds = 180),
    )

    @Test
    fun replacingKeepsTheSetsAndNoteUnderTheNewName() {
        val previous = ExerciseEntry("front-squat", listOf(SetEntry(0, SetType.WEIGHTED, 80.0, 5)))
        val next = state.replacing(0, frontSquat, previous)
        val card = next.exercises[0]
        assertSame(frontSquat, card.exercise)
        assertEquals(squatSets, card.sets)
        assertEquals("belt on", card.note)
        assertSame(bench, next.exercises[1].exercise)
    }

    @Test
    fun replacingCarriesTheSlotOverAndDropsTheOldHistory() {
        val previous = ExerciseEntry("front-squat", listOf(SetEntry(0, SetType.WEIGHTED, 80.0, 5)))
        val next = state.replacing(0, frontSquat, previous)
        // What described the slot moves to the new id.
        assertEquals(mapOf("front-squat" to t1, "bench" to t2), next.targets)
        assertEquals(mapOf("front-squat" to "Sit back"), next.notes)
        assertEquals(mapOf("front-squat" to Gzclp.Tier.T1), next.tiers)
        assertEquals(setOf("front-squat"), next.collapsedWarmups)
        assertEquals("front-squat", next.restTimer?.exerciseId)
        // What described the old exercise's history goes.
        assertEquals(emptyMap(), next.hints)
        // The reason was the program's for the old lift; the lifter chose this one.
        assertEquals(emptyMap(), next.reasons)
        assertEquals(emptySet(), next.seeded)
        assertEquals(emptyMap(), next.sources)
        assertEquals(mapOf("front-squat" to previous), next.previous)
    }

    @Test
    fun aProgramCardReadsItsSlotsLastSessionUntilSwitched() {
        val t1 = ExerciseEntry("squat", listOf(SetEntry(0, SetType.WEIGHTED, 75.0, 3)))
        val t2 = state.previous.getValue("squat")
        val sources = PreviousSources(PastSession(t1, LocalDate(2025, 3, 3), "T1"), PastSession(t2, LocalDate(2025, 3, 7), "T2"))
        val gzclp = state.copy(previousSources = mapOf("squat" to sources))
        assertSame(t1, gzclp.previousShown("squat"))
        assertSame(t2, gzclp.copy(previousLatest = setOf("squat")).previousShown("squat"))
        // Before the slot's first session there is only the lift's last one to show.
        assertSame(t2, gzclp.copy(previousSources = mapOf("squat" to sources.copy(slot = null))).previousShown("squat"))
        assertSame(t2, state.previousShown("squat"))
        // A swapped-in lift has no history on the slot to choose from.
        val swapped = gzclp.copy(previousLatest = setOf("squat")).replacing(0, frontSquat, null)
        assertEquals(emptyMap(), swapped.previousSources)
        assertEquals(emptySet(), swapped.previousLatest)
    }

    @Test
    fun replacingWithoutAPreviousSessionLeavesThePrevColumnEmpty() {
        val next = state.replacing(0, frontSquat, previous = null)
        assertNull(next.previous["front-squat"])
        assertNull(next.previous["squat"])
    }

    @Test
    fun replacingWithAnExerciseAlreadyInTheWorkoutDoesNothing() {
        assertSame(state, state.replacing(0, bench, null))
        assertSame(state, state.replacing(0, squat, null))
        assertSame(state, state.replacing(5, frontSquat, null))
    }

    @Test
    fun movingSwapsNeighboursAndStopsAtTheEnds() {
        assertEquals(listOf(bench, squat), state.moved(0, 1).exercises.map { it.exercise })
        assertEquals(listOf(bench, squat), state.moved(1, -1).exercises.map { it.exercise })
        assertSame(state, state.moved(0, -1))
        assertSame(state, state.moved(1, 1))
        assertSame(state, state.moved(7, -1))
    }

    @Test
    fun movingKeepsEverythingElseWithTheCard() {
        val moved = state.moved(0, 1)
        assertEquals(squatSets, moved.exercises[1].sets)
        assertEquals("belt on", moved.exercises[1].note)
        assertEquals(state.targets, moved.targets)
        assertEquals(state.previous, moved.previous)
    }

    @Test
    fun replacingAProgramSlotKeepsItsPrescription() {
        val day = DayDraft("d1", "Legs", listOf(SlotDraft(squat, "5", "3+", ProgressionChoice.BIG, note = "T1"), SlotDraft(row, "3", "10")))
        val next = day.replacing(0, frontSquat)
        assertEquals(SlotDraft(frontSquat, "5", "3+", ProgressionChoice.BIG, note = "T1"), next.slots[0])
        assertEquals(day.slots[1], next.slots[1])
        assertSame(day, day.replacing(0, row))
        assertSame(day, day.replacing(3, frontSquat))
    }
}
