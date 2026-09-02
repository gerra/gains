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
