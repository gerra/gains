package app.gains

import app.gains.analysis.UnitLabels
import app.gains.domain.Exercise
import app.gains.domain.ExerciseEntry
import app.gains.domain.Modality
import app.gains.domain.SetDraft
import app.gains.domain.SetEntry
import app.gains.domain.SetType
import app.gains.domain.WeightUnit
import app.gains.ui.screens.ExerciseDraft
import kotlin.test.Test
import kotlin.test.assertEquals

/** Warm-ups added by hand in the workout editor: where the row goes, what it is filled with, and how it is numbered. */
class WarmupSetsTest {
    private val squat = Exercise("squat", "Squat", "squat", emptyList(), Modality.WEIGHTED)
    private val labels = UnitLabels(kg = "kg", lbs = "lbs", second = "s", minute = "min", hour = "h", km = "km", reps = "reps")

    @Test
    fun theFirstWarmupStartsBlankAboveTheWorkSets() {
        val card = ExerciseDraft(squat, listOf(SetDraft("100", "5", done = true), SetDraft("100", "5"))).addingWarmup()
        assertEquals(SetDraft(isWarmup = true), card.sets[0])
        assertEquals(listOf("W1", "1", "2"), card.labels)
        assertEquals(1, card.warmups.size)
        assertEquals(2, card.workSets.size)
    }

    @Test
    fun anotherWarmupFollowsTheLastOneFilledLikeItAndUnticked() {
        val planned = listOf(SetDraft("20", "5", isWarmup = true), SetDraft("40", "5", isWarmup = true, done = true), SetDraft("100", "5"))
        val card = ExerciseDraft(squat, planned).addingWarmup()
        assertEquals(SetDraft("40", "5", isWarmup = true), card.sets[2])
        assertEquals(listOf("W1", "W2", "W3", "1"), card.labels)
        assertEquals(planned[2], card.sets[3])
    }

    @Test
    fun theNewRowLinesUpWithTheSameNumberedWarmupOfLastTime() {
        val previous = ExerciseEntry("squat", listOf(
            SetEntry(0, SetType.WEIGHTED, 20.0, 5, isWarmup = true),
            SetEntry(1, SetType.WEIGHTED, 60.0, 3, isWarmup = true),
            SetEntry(2, SetType.WEIGHTED, 100.0, 5),
        ))
        val card = ExerciseDraft(squat, listOf(SetDraft("20", "5", isWarmup = true), SetDraft("100", "5"))).addingWarmup()
        assertEquals(listOf("20×5", "60×3", "100×5"), card.previousLabels(previous, WeightUnit.KG, labels))
    }
}
