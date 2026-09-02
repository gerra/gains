package app.gains.catalogue

import app.gains.domain.SetType
import app.gains.importer.ExerciseResolver
import kotlin.test.Test
import kotlin.test.assertEquals

class NameNormalizerTest {
    @Test
    fun normalizesCaseSpacingAndAbbreviations() {
        assertEquals("dumbbell lateral raise", NameNormalizer.normalize("  DB   Lateral-Raise "))
        assertEquals("tricep pushdown", NameNormalizer.normalize("Triceps Pushdown"))
    }

    @Test
    fun resolvesKnownVariantsToOneExercise() {
        val r = ExerciseResolver(emptyList(), emptyMap())
        val w = listOf(SetType.WEIGHTED)
        assertEquals("db_shoulder_press", r.resolve("Seated Dumbbell Shoulder Press", w).id)
        assertEquals("db_shoulder_press", r.resolve("Seated Shoulder Press", w).id)
        assertEquals("lateral_raise", r.resolve("Dumbbell Lateral Raise", w).id)
        assertEquals("lateral_raise", r.resolve("Seated Dumbbell Lateral Raise", w).id)
        assertEquals("hollow_hold", r.resolve("Hollow hold", listOf(SetType.ISOMETRIC)).id)
        assertEquals("running", r.resolve("Running", listOf(SetType.CARDIO)).id)
        assertEquals("dead_hang", r.resolve("Dead Hang", listOf(SetType.ISOMETRIC)).id)
    }

    @Test
    fun catalogueIdsAndAliasesAreUnique() {
        val ids = ExerciseCatalogue.builtIn.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }
}

class ExerciseCatalogueMappingTest {
    @kotlin.test.Test
    fun catalogueStrengthEntriesAreMappedAndSideDeltsAreAuthored() {
        val strength = ExerciseCatalogue.builtIn.filter { it.trainingModality == app.gains.domain.TrainingModality.STRENGTH }
        kotlin.test.assertTrue(strength.all { it.muscleGroups.isNotEmpty() })
        for (id in listOf("lateral_raise", "upright_row")) {
            kotlin.test.assertTrue(ExerciseCatalogue.byId(id)!!.muscleGroups.any {
                it.group == app.gains.domain.MuscleGroup.SIDE_DELTS
            })
        }
    }

    @kotlin.test.Test
    fun preparationAndNonStrengthEntriesDoNotClaimLiftingVolume() {
        kotlin.test.assertEquals(app.gains.domain.TrainingModality.WARMUP, ExerciseCatalogue.byId("wrist_prep")!!.trainingModality)
        kotlin.test.assertEquals(app.gains.domain.TrainingModality.SKILL, ExerciseCatalogue.byId("face_to_wall_handstand_45")!!.trainingModality)
    }
}
