package app.gains.catalogue

import app.gains.domain.Modality
import app.gains.domain.MuscleContribution
import app.gains.domain.MuscleGroup
import app.gains.domain.SetType
import app.gains.domain.TrainingModality
import app.gains.importer.ExerciseResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test
    fun extraExportSpellingsResolveToTheirExercise() {
        // Declared through Builder.alias(), after the ex() entries.
        val aliases = ExerciseCatalogue.builtInAliases
        assertEquals("ab_wheel", aliases[NameNormalizer.normalize("Ab Roller")])
        assertEquals("back_extension", aliases[NameNormalizer.normalize("Hyperextensions (Back Extensions)")])
        assertEquals("bench_press", aliases[NameNormalizer.normalize("Bench Press - Powerlifting")])
        // Every alias points at a catalogue exercise.
        val ids = ExerciseCatalogue.builtIn.map { it.id }.toSet()
        assertEquals(emptyList(), aliases.filterValues { it !in ids }.keys.toList())
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

class CustomExerciseTest {
    @Test
    fun findsNamesAlreadyKnownWithoutMakingNewOnes() {
        val custom = ExerciseCatalogue.custom("Bouldering", Modality.CARDIO, setOf(MuscleGroup.FOREARMS))
        val r = ExerciseResolver(listOf(custom), mapOf(NameNormalizer.normalize("Iso-Lateral High Row") to "row_machine"))
        assertEquals("triceps_pushdown", r.find("Rope Tricep Pushdown")?.id)
        assertEquals("row_machine", r.find("Iso-Lateral High Row")?.id)
        assertEquals(custom.id, r.find("  bouldering ")?.id)
        assertNull(r.find("Campus Board"))
        assertTrue(r.newExercises.isEmpty())
    }

    @Test
    fun customExerciseKeepsItsModalityAndMuscleCredit() {
        val e = ExerciseCatalogue.custom(" Bouldering ", Modality.CARDIO, primary = setOf(MuscleGroup.FOREARMS, MuscleGroup.LATS), secondary = setOf(MuscleGroup.LATS, MuscleGroup.BICEPS), isDumbbell = true)
        assertEquals("custom_bouldering", e.id)
        assertEquals("Bouldering", e.name)
        assertFalse(e.isBuiltIn)
        assertFalse(e.isDumbbell, "only a weighted lift is logged per dumbbell")
        assertEquals(TrainingModality.CARDIO, e.trainingModality)
        assertEquals(
            listOf(MuscleContribution(MuscleGroup.FOREARMS, 1.0), MuscleContribution(MuscleGroup.LATS, 1.0), MuscleContribution(MuscleGroup.BICEPS, 0.5)),
            e.muscleGroups,
        )
        assertTrue(ExerciseCatalogue.custom("Landmine Press", Modality.WEIGHTED, emptySet(), isDumbbell = true).isDumbbell)
    }
}
