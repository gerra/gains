package app.gains

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
import app.gains.ui.screens.ProgressionChoice
import app.gains.ui.screens.SlotDraft
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

    private val squatSets = listOf(SetDraft("60", "5", isWarmup = true), SetDraft("100", "5", done = true), SetDraft("100", "5"))
    private val state = EditorState(
        loading = false,
        exercises = listOf(ExerciseDraft(squat, squatSets, note = "belt on"), ExerciseDraft(bench, listOf(SetDraft("80", "5")))),
        targets = mapOf("squat" to "5 × 3+", "bench" to "3 × 10"),
        hints = mapOf("squat" to "Last: 100 kg × 5,5 → try 102.5 kg"),
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
        assertEquals(mapOf("front-squat" to "5 × 3+", "bench" to "3 × 10"), next.targets)
        assertEquals(mapOf("front-squat" to "Sit back"), next.notes)
        assertEquals(mapOf("front-squat" to Gzclp.Tier.T1), next.tiers)
        assertEquals(setOf("front-squat"), next.collapsedWarmups)
        assertEquals("front-squat", next.restTimer?.exerciseId)
        // What described the old exercise's history goes.
        assertEquals(emptyMap(), next.hints)
        assertEquals(emptySet(), next.seeded)
        assertEquals(emptyMap(), next.sources)
        assertEquals(mapOf("front-squat" to previous), next.previous)
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
