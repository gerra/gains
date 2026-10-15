package app.gains.domain

/**
 * A part of the body the lifter wants more of. Each one picked adds an exercise for it to every
 * workout of a built-in program (see [app.gains.program.Tailor]); none picked is "whole body".
 */
enum class FocusArea(val muscles: Set<MuscleGroup>) {
    GLUTES(setOf(MuscleGroup.GLUTES)),
    LEGS(setOf(MuscleGroup.QUADS, MuscleGroup.HAMSTRINGS, MuscleGroup.CALVES)),
    CORE(setOf(MuscleGroup.CORE)),
    ARMS(setOf(MuscleGroup.BICEPS, MuscleGroup.TRICEPS)),
    UPPER_BACK(setOf(MuscleGroup.UPPER_BACK, MuscleGroup.LATS, MuscleGroup.REAR_DELTS)),
    SHOULDERS(setOf(MuscleGroup.FRONT_DELTS, MuscleGroup.SIDE_DELTS, MuscleGroup.REAR_DELTS)),
    CHEST(setOf(MuscleGroup.CHEST));

    companion object {
        /** More than two and nothing is a priority any more: the cap the focus question enforces. */
        const val MAX = 2

        /** "GLUTES,CORE"; unknown names are dropped, so a newer device's areas never break an older one. */
        fun encode(areas: Set<FocusArea>): String = entries.filter { it in areas }.joinToString(",") { it.name }
        fun decode(raw: String?): Set<FocusArea> =
            raw.orEmpty().split(',').mapNotNull { name -> entries.firstOrNull { it.name == name } }.take(MAX).toSet()
    }
}

/**
 * Something to be adjusted for. The joints swap the exercises that often bother them for gentler
 * versions of the same movement; pregnancy swaps crunching, lying-flat and jumping moves, and after a
 * baby the crunching and jumping ones; pregnancy and blood pressure also take the all-out out of the
 * hard sets. Every one comes with guidance on screen. Health, so it stays on the device: never synced.
 */
enum class Caution {
    KNEES, LOWER_BACK, SHOULDERS, WRISTS, PREGNANCY, POSTPARTUM, BLOOD_PRESSURE;

    /**
     * Asks for moderate sets: a heavy lift of five reps or fewer becomes 3 × 8–12, an "as many as
     * possible" set a fixed one (the ACSM and AHA advice for blood pressure, ACOG's for pregnancy).
     */
    val easesHardSets: Boolean get() = this == PREGNANCY || this == BLOOD_PRESSURE

    companion object {
        fun encode(cautions: Set<Caution>): String = entries.filter { it in cautions }.joinToString(",") { it.name }
        fun decode(raw: String?): Set<Caution> =
            raw.orEmpty().split(',').mapNotNull { name -> entries.firstOrNull { it.name == name } }.toSet()
    }
}

/** Why a slot of a built-in program is not the one the program was written with. */
sealed interface SlotReason {
    /** Added for a focus area; with the cue of [careful] when one of the lifter's cautions asks for it. */
    data class Focus(val area: FocusArea, val careful: Caution? = null) : SlotReason

    /**
     * Put in place of [fromExerciseId], which often bothers [caution], for the reason [why]; [also] when
     * another caution changed its sets too (pregnancy, blood pressure), so its cue shows as well.
     */
    data class Swapped(val fromExerciseId: String, val caution: Caution, val why: SwapWhy = SwapWhy.GENTLER, val also: Caution? = null) : SlotReason

    /** Kept, with a cue on how to do it with [caution] in mind. */
    data class Careful(val caution: Caution) : SlotReason
}

/** What a swap avoids, so its line on screen says the right thing. */
enum class SwapWhy {
    /** A gentler version of the same movement for a sore joint. */
    GENTLER,
    /** Done sitting up rather than lying flat on the back (pregnancy). */
    LYING_FLAT,
    /** No crunching or strain on the belly (pregnancy, after a baby). */
    BELLY,
    /** No jumping (pregnancy, after a baby). */
    IMPACT,
}
