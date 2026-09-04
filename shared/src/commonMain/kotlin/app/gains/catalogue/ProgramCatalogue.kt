package app.gains.catalogue

import app.gains.domain.Experience
import app.gains.domain.ExerciseSlot
import app.gains.domain.Goal
import app.gains.domain.Program
import app.gains.domain.ProgramDay
import app.gains.domain.ProgressionRule
import app.gains.domain.RepTarget
import app.gains.domain.SetsReps

/**
 * Built-in programs: the routines the r/Fitness and r/bodyweightfitness wikis recommend,
 * expressed as named days of exercise slots. Exercise ids must exist in [ExerciseCatalogue].
 */
object ProgramCatalogue {

    private class SlotsBuilder {
        val slots = ArrayList<ExerciseSlot>()
        fun slot(
            exerciseId: String,
            sets: Int,
            reps: RepTarget,
            progression: ProgressionRule = ProgressionRule.None,
            lastSetAmrap: Boolean = false,
            note: String? = null,
        ) {
            slots.add(ExerciseSlot(exerciseId, sets, reps, lastSetAmrap, progression, note))
        }
    }

    private class DaysBuilder(private val programId: String) {
        val days = ArrayList<ProgramDay>()
        fun day(id: String, name: String, block: SlotsBuilder.() -> Unit): ProgramDay {
            val day = ProgramDay("$programId/$id", name, SlotsBuilder().apply(block).slots)
            days.add(day)
            return day
        }
        /** Share day objects (and ids) with another program, e.g. the 3-day PPL variant. */
        fun reuse(vararg existing: ProgramDay) { days.addAll(existing) }
    }

    private class Builder {
        val programs = ArrayList<Program>()
        fun program(
            id: String,
            name: String,
            description: String,
            goals: Set<Goal>,
            level: Experience,
            daysPerWeek: Int,
            block: DaysBuilder.() -> Unit,
        ): Program {
            val program = Program(id, name, description, goals, level, daysPerWeek, DaysBuilder(id).apply(block).days, isBuiltIn = true)
            programs.add(program)
            return program
        }
    }

    private fun fixed(n: Int) = RepTarget.Fixed(n)
    private fun range(a: Int, b: Int) = RepTarget.Range(a, b)
    private fun amrap(n: Int) = RepTarget.Amrap(n)
    private fun linear(kg: Double, lbs: Double = kg * 2) = ProgressionRule.Linear(kg, lbs)
    private fun double(min: Int, max: Int, kg: Double, lbs: Double = kg * 2) = ProgressionRule.DoubleProgression(min, max, kg, lbs)
    private fun ladder(kg: Double, lbs: Double = kg * 2, vararg stages: SetsReps) = ProgressionRule.StageLadder(stages.toList(), kg, lbs)
    private infix fun Int.x(reps: RepTarget) = SetsReps(this, reps)

    private const val UPPER = 2.5
    private const val LOWER = 5.0

    private val builder = Builder().apply {
        program(
            "rfitness_basic", "r/Fitness Basic Beginner Routine",
            "Three lifts a session, two sessions alternated three times a week. Add weight every workout you hit the reps. Run it for up to three months, then move to GZCLP or 5/3/1 for Beginners.",
            setOf(Goal.GET_STRONGER, Goal.GENERAL_FITNESS, Goal.LOSE_FAT), Experience.BEGINNER, 3,
        ) {
            day("a", "Workout A") {
                slot("barbell_row", 3, amrap(5), linear(UPPER))
                slot("bench_press", 3, amrap(5), linear(UPPER))
                slot("squat", 3, amrap(5), linear(LOWER))
            }
            day("b", "Workout B") {
                slot("chin_up", 3, amrap(5), note = "Lat pulldown if you cannot do chin-ups yet.")
                slot("overhead_press", 3, amrap(5), linear(UPPER))
                slot("deadlift", 3, amrap(5), linear(LOWER))
            }
        }

        program(
            "gzclp", "GZCLP",
            "Cody LeFever's linear progression. Each day has a heavy T1 lift, a moderate T2 lift and a light T3 pull. Four days rotated three times a week: A1, B1, A2, B2, A1…",
            setOf(Goal.GET_STRONGER, Goal.BUILD_MUSCLE), Experience.BEGINNER, 3,
        ) {
            val t1Lower = ladder(LOWER, LOWER * 2, 5 x amrap(3), 6 x amrap(2), 10 x amrap(1))
            val t1Upper = ladder(UPPER, UPPER * 2, 5 x amrap(3), 6 x amrap(2), 10 x amrap(1))
            val t2Lower = ladder(LOWER, LOWER * 2, 3 x fixed(10), 3 x fixed(8), 3 x fixed(6))
            val t2Upper = ladder(UPPER, UPPER * 2, 3 x fixed(10), 3 x fixed(8), 3 x fixed(6))
            val t3 = double(15, 25, UPPER)
            val t3Note = "T3: add weight once the last set reaches 25 reps."
            day("a1", "A1") {
                slot("squat", 5, amrap(3), t1Lower, note = "T1: last set as many reps as possible.")
                slot("bench_press", 3, fixed(10), t2Upper, note = "T2")
                slot("lat_pulldown", 3, amrap(15), t3, note = t3Note)
            }
            day("b1", "B1") {
                slot("overhead_press", 5, amrap(3), t1Upper, note = "T1: last set as many reps as possible.")
                slot("deadlift", 3, fixed(10), t2Lower, note = "T2")
                slot("db_row", 3, amrap(15), t3, note = t3Note)
            }
            day("a2", "A2") {
                slot("bench_press", 5, amrap(3), t1Upper, note = "T1: last set as many reps as possible.")
                slot("squat", 3, fixed(10), t2Lower, note = "T2")
                slot("lat_pulldown", 3, amrap(15), t3, note = t3Note)
            }
            day("b2", "B2") {
                slot("deadlift", 5, amrap(3), t1Lower, note = "T1: last set as many reps as possible.")
                slot("overhead_press", 3, fixed(10), t2Upper, note = "T2")
                slot("db_row", 3, amrap(15), t3, note = t3Note)
            }
        }

        program(
            "531_beginners", "5/3/1 for Beginners",
            "Jim Wendler's beginner template: two main lifts a day on the 5/3/1 wave, five back-off sets at the first working weight, then 50–100 reps of push, pull and single-leg or core work.",
            setOf(Goal.GET_STRONGER), Experience.INTERMEDIATE, 3,
        ) {
            val wave = "5/3/1 wave on a training max of 90% of your 1RM: week 1 65/75/85% ×5, week 2 70/80/90% ×3, week 3 75/85/95% ×5/3/1; last set as many as possible. Then 5×5 at the first working weight. Add 2.5 kg upper / 5 kg lower to the training max after each 3-week cycle."
            day("d1", "Day 1") {
                slot("squat", 8, fixed(5), lastSetAmrap = false, note = wave)
                slot("bench_press", 8, fixed(5), note = wave)
                slot("dip", 5, fixed(10), note = "Push: 50–100 reps total.")
                slot("chin_up", 5, fixed(10), note = "Pull: 50–100 reps total.")
                slot("leg_raise", 5, fixed(10), note = "Single-leg / core: 50–100 reps total.")
            }
            day("d2", "Day 2") {
                slot("deadlift", 8, fixed(5), note = wave)
                slot("overhead_press", 8, fixed(5), note = wave)
                slot("push_up", 5, fixed(10), note = "Push: 50–100 reps total.")
                slot("db_row", 5, fixed(10), note = "Pull: 50–100 reps total.")
                slot("back_extension", 5, fixed(10), note = "Single-leg / core: 50–100 reps total.")
            }
            day("d3", "Day 3") {
                slot("bench_press", 8, fixed(5), note = wave)
                slot("squat", 8, fixed(5), note = wave)
                slot("triceps_pushdown", 5, fixed(10), note = "Push: 50–100 reps total.")
                slot("face_pull", 5, fixed(10), note = "Pull: 50–100 reps total.")
                slot("ab_wheel", 5, fixed(10), note = "Single-leg / core: 50–100 reps total.")
            }
        }

        val ppl = program(
            "ppl_6", "Reddit PPL",
            "Metallicadpa's push/pull/legs. Six days a week, one rest day: the barbell lifts add weight every session, everything else builds size in the 8–12 range.",
            setOf(Goal.BUILD_MUSCLE), Experience.INTERMEDIATE, 6,
        ) {
            fun SlotsBuilder.pullAccessories() {
                slot("pull_up", 3, range(8, 12), note = "Or lat pulldown.")
                slot("seated_cable_row", 3, range(8, 12), note = "Or chest supported row.")
                slot("face_pull", 5, range(15, 20))
                slot("hammer_curl", 4, range(8, 12))
                slot("db_curl", 4, range(8, 12))
            }
            fun SlotsBuilder.pushAccessories() {
                slot("db_incline_bench_press", 3, range(8, 12))
                slot("triceps_pushdown", 3, range(8, 12), note = "Superset with lateral raises.")
                slot("overhead_triceps_extension", 3, range(8, 12), note = "Superset with lateral raises.")
                slot("lateral_raise", 6, range(15, 20), note = "Three sets after each triceps exercise.")
            }
            fun SlotsBuilder.legs() {
                slot("squat", 3, fixed(5), linear(UPPER), lastSetAmrap = true)
                slot("romanian_deadlift", 3, range(8, 12))
                slot("leg_press", 3, range(8, 12))
                slot("leg_curl", 3, range(8, 12))
                slot("calf_raise", 5, range(8, 12))
            }
            day("pull_a", "Pull A") {
                slot("deadlift", 1, amrap(5), linear(LOWER))
                pullAccessories()
            }
            day("push_a", "Push A") {
                slot("bench_press", 5, fixed(5), linear(UPPER), lastSetAmrap = true)
                slot("overhead_press", 3, range(8, 12))
                pushAccessories()
            }
            day("legs_a", "Legs A") { legs() }
            day("pull_b", "Pull B") {
                slot("barbell_row", 5, fixed(5), linear(UPPER), lastSetAmrap = true)
                pullAccessories()
            }
            day("push_b", "Push B") {
                slot("overhead_press", 5, fixed(5), linear(UPPER), lastSetAmrap = true)
                slot("bench_press", 3, range(8, 12))
                pushAccessories()
            }
            day("legs_b", "Legs B") { legs() }
        }

        program(
            "ppl_3", "Reddit PPL (3 days)",
            "The same push/pull/legs days run once a week each instead of twice. Good when six sessions do not fit.",
            setOf(Goal.BUILD_MUSCLE, Goal.GENERAL_FITNESS), Experience.BEGINNER, 3,
        ) { reuse(*ppl.days.toTypedArray()) }

        program(
            "upper_lower_4", "Upper / Lower",
            "Four days: two upper, two lower. Compounds in the 6–8 range, accessories 8–15, reps climb before weight does.",
            setOf(Goal.BUILD_MUSCLE, Goal.GENERAL_FITNESS), Experience.INTERMEDIATE, 4,
        ) {
            val dbl68 = double(6, 8, UPPER)
            val dbl810 = double(8, 10, UPPER)
            val dbl1012 = double(10, 12, UPPER)
            val dbl1215 = double(12, 15, UPPER)
            val dbl1015 = double(10, 15, UPPER)
            day("upper_a", "Upper A") {
                slot("bench_press", 4, range(6, 8), dbl68)
                slot("barbell_row", 4, range(6, 8), dbl68)
                slot("overhead_press", 3, range(8, 10), dbl810)
                slot("lat_pulldown", 3, range(8, 10), dbl810)
                slot("db_curl", 3, range(10, 12), dbl1012)
                slot("triceps_pushdown", 3, range(10, 12), dbl1012)
            }
            day("lower_a", "Lower A") {
                slot("squat", 4, range(6, 8), double(6, 8, LOWER))
                slot("romanian_deadlift", 3, range(8, 10), double(8, 10, LOWER))
                slot("leg_press", 3, range(10, 12), double(10, 12, LOWER))
                slot("leg_curl", 3, range(10, 12), dbl1012)
                slot("calf_raise", 4, range(10, 15), dbl1015)
                slot("plank", 3, fixed(45), note = "Seconds per set.")
            }
            day("upper_b", "Upper B") {
                slot("overhead_press", 4, range(6, 8), dbl68)
                slot("pull_up", 4, range(6, 8), note = "Add weight once 4×8 is easy.")
                slot("db_incline_bench_press", 3, range(8, 10), dbl810)
                slot("seated_cable_row", 3, range(8, 10), dbl810)
                slot("lateral_raise", 3, range(12, 15), dbl1215)
                slot("hammer_curl", 3, range(10, 12), dbl1012)
                slot("skull_crusher", 3, range(10, 12), dbl1012)
            }
            day("lower_b", "Lower B") {
                slot("deadlift", 3, fixed(5), linear(LOWER))
                slot("bulgarian_split_squat", 3, range(8, 10), dbl810, note = "Per leg.")
                slot("hip_thrust", 3, range(8, 10), double(8, 10, LOWER))
                slot("leg_extension", 3, range(12, 15), dbl1215)
                slot("seated_calf_raise", 4, range(10, 15), dbl1015)
                slot("leg_raise", 3, range(10, 15))
            }
        }

        program(
            "bwf_rr", "r/bodyweightfitness Recommended Routine",
            "No gym needed: a pull-up bar and something to do rows and dips on. Three identical sessions a week; when you hit 3×8 on a movement, move to its harder variation.",
            setOf(Goal.GENERAL_FITNESS, Goal.LOSE_FAT, Goal.BUILD_MUSCLE), Experience.BEGINNER, 3,
        ) {
            fun SlotsBuilder.session() {
                val progress = double(5, 8, 0.0)
                slot("pull_up", 3, range(5, 8), progress, note = "Pair 1 with squats. Progression: scapular pulls → negatives → pull-ups → weighted.")
                slot("bodyweight_squat", 3, range(5, 8), progress, note = "Pair 1. Progression: assisted → bodyweight → split → Bulgarian → pistol.")
                slot("dip", 3, range(5, 8), progress, note = "Pair 2 with hinge. Progression: support hold → negatives → dips → weighted.")
                slot("single_leg_rdl", 3, range(5, 8), progress, note = "Pair 2. Progression: Romanian deadlift → single-leg → banded/weighted.")
                slot("inverted_row", 3, range(5, 8), progress, note = "Pair 3 with push-ups. Progression: vertical → incline → horizontal → wide → weighted.")
                slot("push_up", 3, range(5, 8), progress, note = "Pair 3. Progression: incline → full → diamond → pseudo planche.")
                slot("leg_raise", 3, range(8, 12), note = "Core triplet, anti-extension: deadbug → hanging knee raise → hanging leg raise.")
                slot("pallof_press", 3, range(8, 12), note = "Core triplet, anti-rotation.")
                slot("back_extension_iso", 3, range(8, 12), note = "Core triplet, extension: reverse hyper → arch hold / superman.")
            }
            day("s1", "Session A") { session() }
            day("s2", "Session B") { session() }
            day("s3", "Session C") { session() }
        }
    }

    val builtIn: List<Program> = builder.programs

    fun byId(id: String): Program? = builtIn.firstOrNull { it.id == id }
}
