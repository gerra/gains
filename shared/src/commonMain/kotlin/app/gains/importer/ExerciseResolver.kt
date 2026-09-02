package app.gains.importer

import app.gains.catalogue.ExerciseCatalogue
import app.gains.catalogue.NameNormalizer
import app.gains.domain.Exercise
import app.gains.domain.Modality
import app.gains.domain.SetType

/**
 * Maps raw export names onto canonical exercises. User aliases win over built-in
 * aliases, which win over a loose textual match; unknown names become custom exercises.
 */
class ExerciseResolver(
    knownExercises: List<Exercise>,
    /** normalized raw name -> exercise id, user-defined. */
    private val userAliases: Map<String, String>,
) {
    private val exercises = LinkedHashMap<String, Exercise>().apply {
        ExerciseCatalogue.builtIn.forEach { put(it.id, it) }
        knownExercises.forEach { put(it.id, it) }
    }
    private val looseIndex: Map<String, String> by lazy {
        val map = HashMap<String, MutableSet<String>>()
        (ExerciseCatalogue.builtInAliases.keys).forEach { alias ->
            val id = ExerciseCatalogue.builtInAliases.getValue(alias)
            map.getOrPut(NameNormalizer.loose(alias)) { LinkedHashSet() }.add(id)
        }
        exercises.values.forEach { map.getOrPut(NameNormalizer.loose(it.name)) { LinkedHashSet() }.add(it.id) }
        // Only unambiguous loose matches are usable.
        map.filterValues { it.size == 1 }.mapValues { it.value.first() }
    }
    private val created = LinkedHashMap<String, Exercise>()

    /** Exercises created for names the catalogue did not know. */
    val newExercises: List<Exercise> get() = created.values.toList()

    fun exercise(id: String): Exercise? = exercises[id] ?: created[id]

    fun resolve(rawName: String, setTypes: Collection<SetType>): Exercise {
        val normalized = NameNormalizer.normalize(rawName)
        userAliases[normalized]?.let { id -> exercise(id)?.let { return it } }
        ExerciseCatalogue.builtInAliases[normalized]?.let { id -> exercises[id]?.let { return it } }
        exercises.values.firstOrNull { NameNormalizer.normalize(it.name) == normalized }?.let { return it }
        created.values.firstOrNull { NameNormalizer.normalize(it.name) == normalized }?.let { return it }
        looseIndex[NameNormalizer.loose(rawName)]?.let { id -> exercise(id)?.let { return it } }

        val guessed = ExerciseCatalogue.guess(rawName, modalityHint(setTypes))
        val existing = exercises[guessed.id] ?: created[guessed.id]
        if (existing != null) return existing
        created[guessed.id] = guessed
        return guessed
    }

    private fun modalityHint(setTypes: Collection<SetType>): Modality? {
        if (setTypes.isEmpty()) return null
        val counts = setTypes.groupingBy { it }.eachCount()
        return when (counts.maxByOrNull { it.value }!!.key) {
            SetType.WEIGHTED -> Modality.WEIGHTED
            SetType.BODYWEIGHT -> Modality.BODYWEIGHT
            SetType.ISOMETRIC -> Modality.ISOMETRIC
            SetType.CARDIO -> Modality.CARDIO
        }
    }
}
