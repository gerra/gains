package app.gains.program

import app.gains.catalogue.ExerciseCatalogue
import app.gains.domain.Caution
import app.gains.domain.ExerciseSlot
import app.gains.domain.FocusArea
import app.gains.domain.Modality
import app.gains.domain.Program
import app.gains.domain.ProgramDay
import app.gains.domain.ProgressionRule
import app.gains.domain.RepTarget
import app.gains.domain.SlotReason
import app.gains.domain.SwapWhy
import app.gains.domain.WeightUnit

/**
 * Fits a built-in program to the lifter: what they want more of ([FocusArea]) and what to be careful
 * with ([Caution]). Pure and deterministic, so the same answers always give the same days, and a
 * session logged against a tailored day keeps matching it for progression.
 *
 * - A caution swaps the exercises that often bother it for a gentler version of the same movement
 *   (a box squat for a squat, a chest-supported row for a bent-over one), keeping the slot's sets,
 *   reps and progression. Where there is no gentler version that suits every caution, the exercise
 *   stays and carries a cue instead. Lists from physiotherapy guidance (JOSPT, BJSM, ACOG, NICE),
 *   kept conservative: "often easier on", never "safe".
 * - A focus area adds one exercise for it at the end of every workout, rotating through a short list
 *   of beginner-friendly ones so the days differ, and never one a caution swaps out.
 *
 * Custom programs are the lifter's own and are left exactly as written.
 */
object Tailor {

    /**
     * Per caution: exercise → gentler ones in order of preference, the exercises kept with a cue, and
     * what a swap avoids when it is not simply a gentler version for a joint.
     */
    private class Rules(val swaps: Map<String, List<String>>, val careful: Set<String>, val why: Map<String, SwapWhy> = emptyMap())

    private val squats = listOf("squat", "front_squat", "hack_squat", "smith_squat", "zercher_squat", "barbell_hack_squat", "jefferson_squat", "overhead_squat")
    private val lunges = listOf("bulgarian_split_squat", "lunge", "bodyweight_lunge", "deficit_reverse_lunge", "side_split_squat")
    private val crunches = listOf("crunch", "v_up", "flutter_kicks", "oblique_crunch", "heel_touches")
    private val bellyMoves = crunches + listOf("cable_crunch", "ab_wheel", "leg_raise", "hollow_hold", "russian_twist", "reverse_crunch")
    private val lyingPresses = listOf("bench_press", "db_bench_press", "decline_bench_press", "close_grip_bench_press", "chest_fly", "skull_crusher")

    private val rules: Map<Caution, Rules> = mapOf(
        Caution.KNEES to Rules(
            swaps = squats.associateWith { listOf("box_squat", "goblet_squat") } +
                lunges.associateWith { listOf("step_up", "hip_thrust") } +
                mapOf(
                    "pistol_squat" to listOf("bodyweight_squat"),
                    "jump_squat" to listOf("bodyweight_squat"),
                    "sissy_squat" to listOf("wall_sit"),
                ),
            careful = setOf("box_squat", "goblet_squat", "leg_press", "bodyweight_squat", "step_up", "leg_extension", "sumo_squat"),
        ),
        Caution.LOWER_BACK to Rules(
            swaps = mapOf(
                "deadlift" to listOf("trap_bar_deadlift", "hip_thrust"),
                "deficit_deadlift" to listOf("trap_bar_deadlift", "hip_thrust"),
                "snatch_grip_deadlift" to listOf("trap_bar_deadlift", "hip_thrust"),
                "sumo_deadlift" to listOf("trap_bar_deadlift", "hip_thrust"),
                "good_morning" to listOf("hip_thrust"),
                "box_squat" to listOf("goblet_squat", "leg_press"),
                "barbell_row" to listOf("chest_supported_dumbbell_row", "seated_cable_row"),
                "t_bar_row" to listOf("chest_supported_dumbbell_row", "seated_cable_row"),
                "bent_over_dumbbell_row" to listOf("chest_supported_dumbbell_row", "seated_cable_row"),
                "reverse_grip_barbell_row" to listOf("chest_supported_dumbbell_row", "seated_cable_row"),
                "meadows_row" to listOf("chest_supported_dumbbell_row", "seated_cable_row"),
                "ab_wheel" to listOf("dead_bug", "side_plank"),
                "russian_twist" to listOf("pallof_press", "side_plank"),
            ) + squats.associateWith { listOf("goblet_squat", "leg_press") } +
                crunches.associateWith { listOf("dead_bug", "side_plank") },
            careful = setOf("romanian_deadlift", "single_leg_rdl", "trap_bar_deadlift", "goblet_squat", "leg_press", "overhead_press", "back_extension", "hip_thrust"),
        ),
        Caution.SHOULDERS to Rules(
            swaps = listOf("overhead_press", "seated_barbell_overhead_press", "behind_the_neck_press", "push_press", "smith_machine_shoulder_press", "db_shoulder_press", "arnold_press", "shoulder_press_machine")
                .associateWith { listOf("landmine_press") } +
                listOf("dip", "ring_dip", "bench_dip", "dip_machine").associateWith { listOf("push_up", "close_grip_dumbbell_press") } +
                listOf("upright_row", "dumbbell_upright_row", "band_upright_row").associateWith { listOf("lateral_raise") } +
                mapOf(
                    "behind_the_neck_pulldown" to listOf("lat_pulldown"),
                    "bench_press" to listOf("neutral_grip_dumbbell_bench_press", "dumbbell_floor_press"),
                    "pike_push_ups" to listOf("push_up"),
                ),
            careful = setOf("lateral_raise", "pull_up", "chin_up", "push_up", "landmine_press", "chest_press_machine", "db_bench_press", "db_incline_bench_press", "neutral_grip_dumbbell_bench_press"),
        ),
        Caution.WRISTS to Rules(
            swaps = mapOf(
                "barbell_curl" to listOf("hammer_curl", "db_curl"),
                "reverse_curl" to listOf("hammer_curl"),
                "drag_curl" to listOf("hammer_curl"),
                "front_squat" to listOf("goblet_squat"),
                "skull_crusher" to listOf("triceps_pushdown", "overhead_triceps_extension"),
            ),
            careful = setOf("push_up", "incline_push_up", "close_grip_push_up", "bench_press", "overhead_press", "plank", "side_plank", "dip"),
        ),
        // Crunching, lying flat and jumping are swapped while pregnant (ACOG 804, the 2019 Canadian
        // guideline); the heavy lifts stay, moderate, with the breath let out, and the card says when to stop.
        Caution.PREGNANCY to Rules(
            swaps = bellyMoves.associateWith { listOf("pallof_press", "side_plank") } +
                lyingPresses.associateWith { listOf("db_incline_bench_press", "incline_push_up") } +
                mapOf("jump_squat" to listOf("bodyweight_squat"), "dead_bug" to listOf("pallof_press", "side_plank")),
            careful = setOf("squat", "deadlift", "romanian_deadlift", "overhead_press", "plank", "hip_thrust", "leg_press", "trap_bar_deadlift", "box_squat", "goblet_squat"),
            why = bellyMoves.associateWith { SwapWhy.BELLY } + lyingPresses.associateWith { SwapWhy.LYING_FLAT } +
                mapOf("jump_squat" to SwapWhy.IMPACT, "dead_bug" to SwapWhy.LYING_FLAT),
        ),
        // After a baby lying flat is fine; crunching and jumping wait for the pelvic floor and the
        // belly (the 2025 Canadian postpartum guideline). The dead bug is the core work that suits.
        Caution.POSTPARTUM to Rules(
            swaps = bellyMoves.associateWith { listOf("dead_bug", "pallof_press") } + mapOf("jump_squat" to listOf("bodyweight_squat")),
            careful = setOf("squat", "deadlift", "romanian_deadlift", "overhead_press", "plank", "trap_bar_deadlift", "box_squat", "goblet_squat", "leg_press"),
            why = bellyMoves.associateWith { SwapWhy.BELLY } + mapOf("jump_squat" to SwapWhy.IMPACT),
        ),
        // No exercise is swapped: resistance training lowers blood pressure (AHA, Edwards 2023). The
        // hard sets get the cue instead; see [carefulFor].
        Caution.BLOOD_PRESSURE to Rules(emptyMap(), emptySet()),
    )

    /** The extra exercises for each focus area, in the order they are rotated through. */
    private class Pool(val gym: List<String>, val home: List<String>)

    private val pools: Map<FocusArea, Pool> = mapOf(
        FocusArea.GLUTES to Pool(listOf("hip_thrust", "hip_abduction", "cable_kickback"), listOf("bodyweight_glute_bridge", "step_up")),
        FocusArea.LEGS to Pool(listOf("leg_press", "leg_curl", "step_up"), listOf("bodyweight_lunge", "wall_sit", "step_up")),
        FocusArea.CORE to Pool(listOf("dead_bug", "plank", "side_plank", "pallof_press"), listOf("dead_bug", "plank", "side_plank")),
        FocusArea.ARMS to Pool(listOf("db_curl", "triceps_pushdown", "hammer_curl", "overhead_triceps_extension"), listOf("close_grip_push_up", "chin_up")),
        FocusArea.UPPER_BACK to Pool(listOf("seated_cable_row", "face_pull", "lat_pulldown", "rear_delt_fly"), listOf("inverted_row")),
        FocusArea.SHOULDERS to Pool(listOf("lateral_raise", "face_pull", "rear_delt_fly"), listOf("pike_push_ups", "push_up")),
        FocusArea.CHEST to Pool(listOf("push_up", "chest_fly", "db_incline_bench_press"), listOf("push_up", "incline_push_up")),
    )

    /** Seconds per set of a hold added for a focus. */
    const val HOLD_SECONDS = 30
    const val EXTRA_SETS = 3

    fun tailor(program: Program, focus: Set<FocusArea>, cautions: Set<Caution>): Program {
        if (!program.isBuiltIn || (focus.isEmpty() && cautions.isEmpty())) return program
        val home = isHome(program)
        return program.copy(days = program.days.mapIndexed { index, day -> tailorDay(day, index, focus, cautions, home) })
    }

    fun tailorAll(programs: List<Program>, focus: Set<FocusArea>, cautions: Set<Caution>): List<Program> =
        if (focus.isEmpty() && cautions.isEmpty()) programs else programs.map { tailor(it, focus, cautions) }

    /** The first of [cautions] that swaps [exerciseId] out, or null when none does. */
    fun swappedBy(exerciseId: String, cautions: Set<Caution>): Caution? =
        cautions.sortedBy { it.ordinal }.firstOrNull { exerciseId in rules.getValue(it).swaps }

    private fun tailorDay(day: ProgramDay, index: Int, focus: Set<FocusArea>, cautions: Set<Caution>, home: Boolean): ProgramDay {
        val slots = ArrayList<ExerciseSlot>()
        val inDay = day.slots.mapTo(HashSet()) { it.exerciseId }
        val ease = cautions.sortedBy { it.ordinal }.firstOrNull { it.easesHardSets }
        for (original in day.slots) {
            // Heavy and all-out sets are what pregnancy and blood pressure ask not to do: they become moderate.
            val slot = if (ease != null) moderated(original) else original
            val by = swappedBy(slot.exerciseId, cautions)
            val gentler = by?.let { gentlerFor(slot.exerciseId, cautions, inDay) }
            slots += when {
                gentler != null -> {
                    inDay += gentler
                    val why = rules.getValue(by).why[slot.exerciseId] ?: SwapWhy.GENTLER
                    slot.copy(exerciseId = gentler, reason = SlotReason.Swapped(slot.exerciseId, by, why, also = ease?.takeIf { slot != original && it != by }))
                }
                else -> carefulFor(slot, cautions)?.let { slot.copy(reason = SlotReason.Careful(it)) }
                    ?: ease?.takeIf { slot != original }?.let { slot.copy(reason = SlotReason.Careful(it)) }
                    ?: slot
            }
        }
        for (area in FocusArea.entries.filter { it in focus }) {
            val pool = pools.getValue(area).let { if (home) it.home else it.gym }
            val pick = pool.indices.map { pool[(index + it) % pool.size] }
                .firstOrNull { it !in inDay && swappedBy(it, cautions) == null } ?: continue
            inDay += pick
            val extra = extraSlot(pick, area)
            slots += extra.copy(reason = SlotReason.Focus(area, carefulFor(extra, cautions)))
        }
        return day.copy(slots = slots)
    }

    /** The first replacement, across every caution that swaps [exerciseId], that no caution swaps out in turn and the day does not already have. */
    private fun gentlerFor(exerciseId: String, cautions: Set<Caution>, inDay: Set<String>): String? =
        cautions.sortedBy { it.ordinal }
            .flatMap { rules.getValue(it).swaps[exerciseId].orEmpty() }
            .firstOrNull { it !in inDay && swappedBy(it, cautions) == null }

    /** The caution whose cue [slot] should carry, if any. */
    private fun carefulFor(slot: ExerciseSlot, cautions: Set<Caution>): Caution? =
        cautions.sortedBy { it.ordinal }.firstOrNull { c ->
            if (c == Caution.BLOOD_PRESSURE) isHardSet(slot) else slot.exerciseId in rules.getValue(c).careful
        }

    /**
     * The slot made moderate: a weighted lift of five reps or fewer (a GZCLP T1, a 5 × 5) becomes
     * 3 × 8–12 whose weight climbs once every set reaches 12, by the slot's own step; any other
     * "as many as possible" set becomes a fixed one at its minimum, its stage ladder's stages too.
     */
    private fun moderated(slot: ExerciseSlot): ExerciseSlot {
        if (isHeavyLift(slot)) {
            val kg = slot.progression.step(WeightUnit.KG) ?: 2.5
            val lbs = slot.progression.step(WeightUnit.LBS) ?: 5.0
            return slot.copy(sets = 3, reps = RepTarget.Range(8, 12), lastSetAmrap = false, progression = ProgressionRule.DoubleProgression(8, 12, kg, lbs))
        }
        fun fixed(r: RepTarget) = if (r is RepTarget.Amrap) RepTarget.Fixed(r.min) else r
        val rule = slot.progression
        val progression = if (rule is ProgressionRule.StageLadder) rule.copy(stages = rule.stages.map { it.copy(reps = fixed(it.reps)) }) else rule
        return slot.copy(reps = fixed(slot.reps), lastSetAmrap = false, progression = progression)
    }

    /** Five reps or fewer of a weighted lift. */
    private fun isHeavyLift(slot: ExerciseSlot): Boolean =
        ExerciseCatalogue.byId(slot.exerciseId)?.modality == Modality.WEIGHTED && slot.reps.successReps <= 5

    /**
     * Built on heavy sets: five reps or fewer of a weighted lift, or sets taken to the limit. Ranked
     * down, and flagged on its page, for someone pregnant or with high blood pressure.
     */
    fun isHeavy(program: Program): Boolean = program.days.any { day -> day.slots.any { isHardSet(it) } }

    /** Sets taken to the limit or close to it: as many reps as possible, or five or fewer of a weighted lift. */
    private fun isHardSet(slot: ExerciseSlot): Boolean = slot.lastSetAmrap || slot.reps is RepTarget.Amrap || isHeavyLift(slot)

    private fun extraSlot(exerciseId: String, area: FocusArea): ExerciseSlot {
        val reason = SlotReason.Focus(area, null)
        return when (ExerciseCatalogue.byId(exerciseId)?.modality) {
            Modality.ISOMETRIC -> ExerciseSlot(exerciseId, EXTRA_SETS, RepTarget.Fixed(HOLD_SECONDS), note = "Seconds per set.", reason = reason)
            Modality.BODYWEIGHT -> ExerciseSlot(exerciseId, EXTRA_SETS, RepTarget.Range(8, 15), reason = reason)
            else -> ExerciseSlot(exerciseId, EXTRA_SETS, RepTarget.Range(10, 15), progression = ProgressionRule.DoubleProgression(10, 15, 2.5, 5.0), reason = reason)
        }
    }

    /** Mostly bodyweight work: a focus adds bodyweight exercises rather than machines it may not have. */
    private fun isHome(program: Program): Boolean {
        val slots = program.days.flatMap { it.slots }
        val bodyweight = slots.count { ExerciseCatalogue.byId(it.exerciseId)?.modality in setOf(Modality.BODYWEIGHT, Modality.ISOMETRIC) }
        return slots.isNotEmpty() && bodyweight * 2 > slots.size
    }

    /** Every exercise id the rules and pools name, for the catalogue test. */
    internal val namedExercises: Set<String> get() =
        rules.values.flatMap { r -> r.swaps.keys + r.swaps.values.flatten() + r.careful }.toSet() +
            pools.values.flatMap { it.gym + it.home }
}
