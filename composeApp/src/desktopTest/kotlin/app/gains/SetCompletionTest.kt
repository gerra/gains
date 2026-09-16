package app.gains

import app.gains.domain.Exercise
import app.gains.domain.Modality
import app.gains.domain.SetEntry
import app.gains.domain.SetType
import app.gains.domain.WeightUnit
import app.gains.ui.screens.ExerciseDraft
import app.gains.ui.screens.SessionEditorModel
import app.gains.ui.screens.SetDraft
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Ticking sets off in the workout editor: what counts as done, and what the save prompt counts. */
class SetCompletionTest {
    private val squat = Exercise("squat", "Squat", "squat", emptyList(), Modality.WEIGHTED)

    @Test
    fun aSavedSetLoadsTicked() {
        val draft = SetDraft.from(SetEntry(0, SetType.WEIGHTED, weightKg = 100.0, reps = 5), WeightUnit.KG)
        assertTrue(draft.done)
        assertTrue(draft.hasValues)
    }

    @Test
    fun anEmptyRowHasNothingToTick() {
        assertFalse(SetDraft().hasValues)
        assertTrue(SetDraft(reps = "8").hasValues)
        assertTrue(SetDraft(seconds = "30").hasValues)
    }

    @Test
    fun nothingTickedMeansEverySetCounts() {
        val sets = listOf(SetDraft("100", "5"), SetDraft("100", "5"), SetDraft("100", "5"))
        assertEquals(0, SessionEditorModel.untickedWorkSets(listOf(ExerciseDraft(squat, sets)), WeightUnit.KG))
    }

    @Test
    fun countsFilledWorkSetsLeftUnticked() {
        val sets = listOf(
            SetDraft("60", "5", isWarmup = true),          // warm-ups are never asked about
            SetDraft("100", "5", done = true),
            SetDraft("100", "5", done = true),
            SetDraft("100", "5"),                           // done but not ticked: asked about
            SetDraft("100", ""),                            // still filled: asked about
            SetDraft(),                                     // empty: never saved, so not asked about
        )
        assertEquals(2, SessionEditorModel.untickedWorkSets(listOf(ExerciseDraft(squat, sets)), WeightUnit.KG))
    }
}
