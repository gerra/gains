package app.gains.catalogue

import app.gains.domain.Exercise
import app.gains.domain.Modality
import app.gains.domain.MuscleContribution
import app.gains.domain.MuscleGroup
import app.gains.domain.MuscleGroup.*

/**
 * Built-in exercises with muscle-group contributions (1.0 primary, 0.5 secondary)
 * and the aliases under which exports have been seen to log them.
 */
object ExerciseCatalogue {

    private class Builder {
        val exercises = ArrayList<Exercise>()
        val aliases = LinkedHashMap<String, String>()

        fun ex(
            id: String,
            name: String,
            modality: Modality = Modality.WEIGHTED,
            dumbbell: Boolean = false,
            primary: List<MuscleGroup>,
            secondary: List<MuscleGroup> = emptyList(),
            vararg alias: String,
        ) {
            val groups = primary.map { MuscleContribution(it, 1.0) } + secondary.map { MuscleContribution(it, 0.5) }
            exercises.add(Exercise(id, name, name, groups, modality, dumbbell, isBuiltIn = true))
            aliases[NameNormalizer.normalize(name)] = id
            alias.forEach { aliases[NameNormalizer.normalize(it)] = id }
        }
    }

    private val builder = Builder().apply {
        // Chest
        ex("bench_press", "Bench Press", primary = listOf(CHEST), secondary = listOf(TRICEPS, FRONT_DELTS), alias = arrayOf("Barbell Bench Press", "Flat Bench Press", "Bench Press (Barbell)"))
        ex("incline_bench_press", "Incline Bench Press", primary = listOf(CHEST, FRONT_DELTS), secondary = listOf(TRICEPS), alias = arrayOf("Incline Barbell Bench Press", "Incline Bench Press (Barbell)"))
        ex("decline_bench_press", "Decline Bench Press", primary = listOf(CHEST), secondary = listOf(TRICEPS), alias = arrayOf("Decline Barbell Bench Press"))
        ex("db_bench_press", "Dumbbell Bench Press", dumbbell = true, primary = listOf(CHEST), secondary = listOf(TRICEPS, FRONT_DELTS), alias = arrayOf("Flat Dumbbell Bench Press", "Dumbbell Press", "Bench Press (Dumbbell)"))
        ex("db_incline_bench_press", "Incline Dumbbell Bench Press", dumbbell = true, primary = listOf(CHEST, FRONT_DELTS), secondary = listOf(TRICEPS), alias = arrayOf("Incline Dumbbell Press", "Dumbbell Incline Bench Press", "Incline Bench Press (Dumbbell)"))
        ex("chest_press_machine", "Chest Press (Machine)", primary = listOf(CHEST), secondary = listOf(TRICEPS, FRONT_DELTS), alias = arrayOf("Machine Chest Press", "Chest Press", "Seated Chest Press"))
        ex("chest_fly", "Chest Fly", primary = listOf(CHEST), alias = arrayOf("Dumbbell Fly", "Dumbbell Chest Fly", "Pec Deck", "Chest Fly (Machine)", "Machine Fly", "Pec Fly"))
        ex("cable_fly", "Cable Fly", primary = listOf(CHEST), alias = arrayOf("Cable Crossover", "Cable Chest Fly", "Low Cable Fly", "High Cable Fly"))
        ex("push_up", "Push Up", modality = Modality.BODYWEIGHT, primary = listOf(CHEST), secondary = listOf(TRICEPS, FRONT_DELTS), alias = arrayOf("Push-Up", "Pushup", "Push Ups", "Pushups", "Press Up"))
        ex("dip", "Dip", modality = Modality.BODYWEIGHT, primary = listOf(CHEST, TRICEPS), secondary = listOf(FRONT_DELTS), alias = arrayOf("Dips", "Chest Dip", "Chest Dips", "Weighted Dip", "Parallel Bar Dip", "Tricep Dip"))

        // Shoulders
        ex("overhead_press", "Overhead Press", primary = listOf(FRONT_DELTS, SIDE_DELTS), secondary = listOf(TRICEPS), alias = arrayOf("Barbell Overhead Press", "Military Press", "Standing Overhead Press", "Strict Press", "Shoulder Press (Barbell)", "Barbell Shoulder Press", "OHP"))
        ex("db_shoulder_press", "Dumbbell Shoulder Press", dumbbell = true, primary = listOf(FRONT_DELTS, SIDE_DELTS), secondary = listOf(TRICEPS), alias = arrayOf("Seated Dumbbell Shoulder Press", "Seated Shoulder Press", "Shoulder Press", "Seated Dumbbell Press", "Dumbbell Overhead Press", "Seated DB Press", "Shoulder Press (Dumbbell)", "Standing Dumbbell Shoulder Press"))
        ex("shoulder_press_machine", "Shoulder Press (Machine)", primary = listOf(FRONT_DELTS, SIDE_DELTS), secondary = listOf(TRICEPS), alias = arrayOf("Machine Shoulder Press", "Seated Shoulder Press (Machine)"))
        ex("arnold_press", "Arnold Press", dumbbell = true, primary = listOf(FRONT_DELTS, SIDE_DELTS), secondary = listOf(TRICEPS))
        ex("lateral_raise", "Lateral Raise", dumbbell = true, primary = listOf(SIDE_DELTS), alias = arrayOf("Dumbbell Lateral Raise", "Seated Dumbbell Lateral Raise", "Seated Lateral Raise", "Side Lateral Raise", "Lateral Raise (Dumbbell)", "DB Lateral Raise", "Side Raise"))
        ex("cable_lateral_raise", "Cable Lateral Raise", primary = listOf(SIDE_DELTS), alias = arrayOf("Lateral Raise (Cable)", "Single Arm Cable Lateral Raise"))
        ex("lateral_raise_machine", "Lateral Raise (Machine)", primary = listOf(SIDE_DELTS), alias = arrayOf("Machine Lateral Raise"))
        ex("front_raise", "Front Raise", dumbbell = true, primary = listOf(FRONT_DELTS), alias = arrayOf("Dumbbell Front Raise", "Plate Front Raise", "Cable Front Raise"))
        ex("rear_delt_fly", "Rear Delt Fly", dumbbell = true, primary = listOf(REAR_DELTS), secondary = listOf(UPPER_BACK), alias = arrayOf("Reverse Fly", "Rear Delt Raise", "Reverse Pec Deck", "Rear Delt Fly (Machine)", "Bent Over Reverse Fly", "Dumbbell Reverse Fly", "Rear Delt Machine", "Reverse Fly (Machine)"))
        ex("face_pull", "Face Pull", primary = listOf(REAR_DELTS), secondary = listOf(UPPER_BACK, TRAPS), alias = arrayOf("Cable Face Pull", "Face Pulls"))
        ex("upright_row", "Upright Row", primary = listOf(SIDE_DELTS, TRAPS), alias = arrayOf("Barbell Upright Row", "Cable Upright Row"))

        // Back
        ex("pull_up", "Pull Up", modality = Modality.BODYWEIGHT, primary = listOf(LATS), secondary = listOf(BICEPS, UPPER_BACK), alias = arrayOf("Pull-Up", "Pullup", "Pull Ups", "Pullups", "Weighted Pull Up", "Wide Grip Pull Up"))
        ex("chin_up", "Chin Up", modality = Modality.BODYWEIGHT, primary = listOf(LATS, BICEPS), secondary = listOf(UPPER_BACK), alias = arrayOf("Chin-Up", "Chinup", "Chin Ups", "Weighted Chin Up"))
        ex("lat_pulldown", "Lat Pulldown", primary = listOf(LATS), secondary = listOf(BICEPS, UPPER_BACK), alias = arrayOf("Lat Pulldown (Cable)", "Wide Grip Lat Pulldown", "Pulldown", "Cable Pulldown", "Lat Pull Down", "Close Grip Lat Pulldown", "Lat Pulldown (Machine)"))
        ex("barbell_row", "Barbell Row", primary = listOf(UPPER_BACK, LATS), secondary = listOf(BICEPS, REAR_DELTS), alias = arrayOf("Bent Over Row", "Bent Over Barbell Row", "Bent-Over Row", "Pendlay Row", "Barbell Bent Over Row", "Row (Barbell)"))
        ex("db_row", "Dumbbell Row", dumbbell = true, primary = listOf(UPPER_BACK, LATS), secondary = listOf(BICEPS, REAR_DELTS), alias = arrayOf("One Arm Dumbbell Row", "Single Arm Dumbbell Row", "Single Arm Row", "One-Arm Row", "Dumbbell Bent Over Row", "Row (Dumbbell)", "DB Row"))
        ex("seated_cable_row", "Seated Cable Row", primary = listOf(UPPER_BACK), secondary = listOf(LATS, BICEPS), alias = arrayOf("Cable Row", "Seated Row", "Seated Row (Cable)", "Low Row", "Low Cable Row"))
        ex("row_machine", "Row (Machine)", primary = listOf(UPPER_BACK), secondary = listOf(LATS, BICEPS), alias = arrayOf("Machine Row", "Seated Row (Machine)", "Chest Supported Row", "Hammer Strength Row", "Iso-Lateral Row", "High Row"))
        ex("t_bar_row", "T-Bar Row", primary = listOf(UPPER_BACK), secondary = listOf(LATS, BICEPS), alias = arrayOf("T Bar Row", "Landmine Row"))
        ex("straight_arm_pulldown", "Straight Arm Pulldown", primary = listOf(LATS), alias = arrayOf("Cable Pullover", "Straight-Arm Pulldown", "Rope Pullover"))
        ex("db_pullover", "Dumbbell Pullover", dumbbell = true, primary = listOf(LATS, CHEST), alias = arrayOf("Pullover"))
        ex("shrug", "Shrug", primary = listOf(TRAPS), alias = arrayOf("Barbell Shrug", "Dumbbell Shrug", "Shrugs", "Trap Bar Shrug", "Shrug (Dumbbell)", "Shrug (Barbell)"))
        ex("deadlift", "Deadlift", primary = listOf(HAMSTRINGS, GLUTES, LOWER_BACK), secondary = listOf(UPPER_BACK, TRAPS, QUADS, FOREARMS), alias = arrayOf("Barbell Deadlift", "Conventional Deadlift", "Deadlift (Barbell)"))
        ex("sumo_deadlift", "Sumo Deadlift", primary = listOf(GLUTES, QUADS, HAMSTRINGS), secondary = listOf(LOWER_BACK, UPPER_BACK))
        ex("trap_bar_deadlift", "Trap Bar Deadlift", primary = listOf(QUADS, GLUTES, HAMSTRINGS), secondary = listOf(LOWER_BACK, TRAPS), alias = arrayOf("Hex Bar Deadlift"))
        ex("romanian_deadlift", "Romanian Deadlift", primary = listOf(HAMSTRINGS, GLUTES), secondary = listOf(LOWER_BACK), alias = arrayOf("RDL", "Barbell Romanian Deadlift", "Dumbbell Romanian Deadlift", "Stiff Leg Deadlift", "Stiff-Legged Deadlift", "Romanian Deadlift (Barbell)", "Romanian Deadlift (Dumbbell)"))
        ex("good_morning", "Good Morning", primary = listOf(HAMSTRINGS, LOWER_BACK), secondary = listOf(GLUTES))
        ex("back_extension", "Back Extension", modality = Modality.BODYWEIGHT, primary = listOf(LOWER_BACK), secondary = listOf(GLUTES, HAMSTRINGS), alias = arrayOf("Hyperextension", "45 Degree Back Extension", "Hyperextensions", "Reverse Hyperextension"))

        // Arms
        ex("barbell_curl", "Barbell Curl", primary = listOf(BICEPS), secondary = listOf(FOREARMS), alias = arrayOf("Bicep Curl (Barbell)", "Barbell Bicep Curl", "EZ Bar Curl", "EZ-Bar Curl", "Ez Bar Curl", "Bicep Curl", "Curl"))
        ex("db_curl", "Dumbbell Curl", dumbbell = true, primary = listOf(BICEPS), secondary = listOf(FOREARMS), alias = arrayOf("Bicep Curl (Dumbbell)", "Dumbbell Bicep Curl", "Alternating Dumbbell Curl", "Standing Dumbbell Curl", "Seated Dumbbell Curl", "DB Curl"))
        ex("hammer_curl", "Hammer Curl", dumbbell = true, primary = listOf(BICEPS, FOREARMS), alias = arrayOf("Dumbbell Hammer Curl", "Hammer Curl (Dumbbell)", "Rope Hammer Curl", "Cross Body Hammer Curl"))
        ex("incline_curl", "Incline Dumbbell Curl", dumbbell = true, primary = listOf(BICEPS), alias = arrayOf("Incline Curl"))
        ex("preacher_curl", "Preacher Curl", primary = listOf(BICEPS), alias = arrayOf("Preacher Curl (Machine)", "Machine Preacher Curl", "EZ Bar Preacher Curl", "Dumbbell Preacher Curl", "Preacher Curl (Barbell)"))
        ex("cable_curl", "Cable Curl", primary = listOf(BICEPS), alias = arrayOf("Bicep Curl (Cable)", "Cable Bicep Curl", "Bayesian Curl"))
        ex("concentration_curl", "Concentration Curl", dumbbell = true, primary = listOf(BICEPS))
        ex("triceps_pushdown", "Triceps Pushdown", primary = listOf(TRICEPS), alias = arrayOf("Tricep Pushdown", "Cable Pushdown", "Rope Pushdown", "Triceps Pushdown (Cable)", "Tricep Pushdown (Cable)", "Pushdown", "Tricep Extension (Cable)", "Rope Tricep Pushdown", "Cable Tricep Extension", "Straight Bar Pushdown", "V-Bar Pushdown"))
        ex("overhead_triceps_extension", "Overhead Triceps Extension", primary = listOf(TRICEPS), alias = arrayOf("Overhead Tricep Extension", "Overhead Cable Tricep Extension", "Dumbbell Overhead Tricep Extension", "Tricep Extension", "Triceps Extension", "Overhead Extension", "Seated Tricep Extension", "Overhead Rope Extension"))
        ex("skull_crusher", "Skull Crusher", primary = listOf(TRICEPS), alias = arrayOf("Skullcrusher", "Lying Triceps Extension", "Lying Tricep Extension", "EZ Bar Skull Crusher", "Skull Crushers", "Skullcrushers"))
        ex("close_grip_bench_press", "Close Grip Bench Press", primary = listOf(TRICEPS, CHEST), secondary = listOf(FRONT_DELTS), alias = arrayOf("Close-Grip Bench Press", "Close Grip Bench"))
        ex("triceps_kickback", "Triceps Kickback", dumbbell = true, primary = listOf(TRICEPS), alias = arrayOf("Tricep Kickback", "Kickback", "Dumbbell Kickback", "Cable Kickback"))
        ex("wrist_curl", "Wrist Curl", primary = listOf(FOREARMS), alias = arrayOf("Barbell Wrist Curl", "Dumbbell Wrist Curl", "Reverse Wrist Curl", "Wrist Curls"))
        ex("reverse_curl", "Reverse Curl", primary = listOf(FOREARMS, BICEPS), alias = arrayOf("Reverse Barbell Curl", "Reverse Grip Curl", "Reverse Curls"))
        ex("farmers_walk", "Farmer's Walk", primary = listOf(FOREARMS, TRAPS), secondary = listOf(CORE), alias = arrayOf("Farmers Walk", "Farmer Walk", "Farmers Carry", "Farmer's Carry", "Loaded Carry"))
        ex("dead_hang", "Dead Hang", modality = Modality.ISOMETRIC, primary = listOf(FOREARMS), secondary = listOf(LATS), alias = arrayOf("Dead Hangs", "Bar Hang", "Hang"))

        // Legs
        ex("squat", "Squat", primary = listOf(QUADS, GLUTES), secondary = listOf(HAMSTRINGS, LOWER_BACK, CORE), alias = arrayOf("Barbell Squat", "Back Squat", "Squat (Barbell)", "High Bar Squat", "Low Bar Squat", "Barbell Back Squat"))
        ex("front_squat", "Front Squat", primary = listOf(QUADS), secondary = listOf(GLUTES, CORE), alias = arrayOf("Barbell Front Squat", "Front Squat (Barbell)"))
        ex("goblet_squat", "Goblet Squat", dumbbell = true, primary = listOf(QUADS, GLUTES), alias = arrayOf("Dumbbell Goblet Squat", "Kettlebell Goblet Squat", "Dumbbell Squat"))
        ex("hack_squat", "Hack Squat", primary = listOf(QUADS), secondary = listOf(GLUTES), alias = arrayOf("Hack Squat (Machine)", "Machine Hack Squat"))
        ex("smith_squat", "Smith Machine Squat", primary = listOf(QUADS, GLUTES), alias = arrayOf("Squat (Smith Machine)", "Smith Squat"))
        ex("leg_press", "Leg Press", primary = listOf(QUADS), secondary = listOf(GLUTES, HAMSTRINGS), alias = arrayOf("Sled Leg Press", "45 Degree Leg Press", "Leg Press (Machine)", "Horizontal Leg Press", "Seated Leg Press", "Machine Leg Press"))
        ex("bulgarian_split_squat", "Bulgarian Split Squat", dumbbell = true, primary = listOf(QUADS, GLUTES), secondary = listOf(HAMSTRINGS), alias = arrayOf("Split Squat", "Rear Foot Elevated Split Squat", "RFESS", "Dumbbell Bulgarian Split Squat"))
        ex("lunge", "Lunge", dumbbell = true, primary = listOf(QUADS, GLUTES), secondary = listOf(HAMSTRINGS), alias = arrayOf("Walking Lunge", "Dumbbell Lunge", "Reverse Lunge", "Lunges", "Barbell Lunge", "Walking Lunges", "Dumbbell Walking Lunge", "Lunge (Dumbbell)"))
        ex("step_up", "Step Up", dumbbell = true, primary = listOf(QUADS, GLUTES), alias = arrayOf("Step-Up", "Dumbbell Step Up", "Step Ups"))
        ex("leg_extension", "Leg Extension", primary = listOf(QUADS), alias = arrayOf("Leg Extensions", "Leg Extension (Machine)", "Machine Leg Extension", "Quad Extension"))
        ex("leg_curl", "Leg Curl", primary = listOf(HAMSTRINGS), alias = arrayOf("Lying Leg Curl", "Seated Leg Curl", "Hamstring Curl", "Leg Curls", "Leg Curl (Machine)", "Lying Leg Curl (Machine)", "Seated Leg Curl (Machine)", "Machine Leg Curl", "Standing Leg Curl"))
        ex("nordic_curl", "Nordic Hamstring Curl", modality = Modality.BODYWEIGHT, primary = listOf(HAMSTRINGS), alias = arrayOf("Nordic Curl", "Nordics"))
        ex("hip_thrust", "Hip Thrust", primary = listOf(GLUTES), secondary = listOf(HAMSTRINGS), alias = arrayOf("Barbell Hip Thrust", "Hip Thrust (Barbell)", "Hip Thrust (Machine)", "Glute Bridge", "Barbell Glute Bridge", "Machine Hip Thrust"))
        ex("hip_abduction", "Hip Abduction", primary = listOf(GLUTES), alias = arrayOf("Hip Abduction (Machine)", "Abductor Machine", "Abductors", "Machine Hip Abduction", "Cable Hip Abduction"))
        ex("hip_adduction", "Hip Adduction", primary = listOf(QUADS), alias = arrayOf("Hip Adduction (Machine)", "Adductor Machine", "Adductors", "Machine Hip Adduction"))
        ex("cable_kickback", "Glute Kickback", primary = listOf(GLUTES), alias = arrayOf("Cable Glute Kickback", "Glute Kickback (Cable)", "Kickbacks", "Glute Kickback (Machine)"))
        ex("calf_raise", "Standing Calf Raise", primary = listOf(CALVES), alias = arrayOf("Calf Raise", "Calf Raises", "Standing Calf Raise (Machine)", "Calf Raise (Machine)", "Smith Machine Calf Raise", "Dumbbell Calf Raise", "Leg Press Calf Raise", "Calf Press", "Calf Press (Leg Press)", "Calf Raise (Leg Press)", "Single Leg Calf Raise", "Standing Calf Raises"))
        ex("seated_calf_raise", "Seated Calf Raise", primary = listOf(CALVES), alias = arrayOf("Seated Calf Raise (Machine)", "Seated Calf Raises"))
        ex("wall_sit", "Wall Sit", modality = Modality.ISOMETRIC, primary = listOf(QUADS))

        // Core
        ex("plank", "Plank", modality = Modality.ISOMETRIC, primary = listOf(CORE), alias = arrayOf("Front Plank", "Planks", "Forearm Plank", "Weighted Plank"))
        ex("side_plank", "Side Plank", modality = Modality.ISOMETRIC, primary = listOf(CORE), alias = arrayOf("Side Planks"))
        ex("hollow_hold", "Hollow Hold", modality = Modality.ISOMETRIC, primary = listOf(CORE), alias = arrayOf("Hollow hold", "Hollow Body Hold", "Hollow Body", "Hollow Rock"))
        ex("l_sit", "L-Sit", modality = Modality.ISOMETRIC, primary = listOf(CORE), secondary = listOf(TRICEPS), alias = arrayOf("L Sit", "L-sit Hold"))
        ex("crunch", "Crunch", modality = Modality.BODYWEIGHT, primary = listOf(CORE), alias = arrayOf("Crunches", "Sit Up", "Sit-Up", "Sit Ups", "Situp", "Decline Crunch", "Decline Sit Up", "Weighted Crunch", "Bicycle Crunch"))
        ex("cable_crunch", "Cable Crunch", primary = listOf(CORE), alias = arrayOf("Kneeling Cable Crunch", "Rope Crunch", "Crunch (Cable)", "Ab Crunch Machine", "Crunch (Machine)", "Machine Crunch"))
        ex("leg_raise", "Hanging Leg Raise", modality = Modality.BODYWEIGHT, primary = listOf(CORE), secondary = listOf(FOREARMS), alias = arrayOf("Leg Raise", "Leg Raises", "Hanging Knee Raise", "Knee Raise", "Lying Leg Raise", "Captain's Chair Leg Raise", "Hanging Leg Raises", "Toes To Bar", "Toes-To-Bar"))
        ex("ab_wheel", "Ab Wheel Rollout", modality = Modality.BODYWEIGHT, primary = listOf(CORE), secondary = listOf(LATS), alias = arrayOf("Ab Rollout", "Ab Wheel", "Rollout"))
        ex("russian_twist", "Russian Twist", modality = Modality.BODYWEIGHT, primary = listOf(CORE), alias = arrayOf("Russian Twists"))
        ex("pallof_press", "Pallof Press", primary = listOf(CORE), alias = arrayOf("Cable Pallof Press"))
        ex("back_extension_iso", "Superman Hold", modality = Modality.ISOMETRIC, primary = listOf(LOWER_BACK), secondary = listOf(GLUTES), alias = arrayOf("Superman"))

        // Neck
        ex("neck_curl", "Neck Curl", primary = listOf(NECK), alias = arrayOf("Neck Flexion", "Plate Neck Curl", "Neck Curls"))
        ex("neck_extension", "Neck Extension", primary = listOf(NECK), alias = arrayOf("Neck Extensions", "Plate Neck Extension", "Neck Harness", "Neck Harness Extension"))
        ex("neck_side", "Lateral Neck Flexion", primary = listOf(NECK), alias = arrayOf("Neck Side Flexion", "Side Neck Curl"))

        // Cardio
        ex("running", "Running", modality = Modality.CARDIO, primary = emptyList(), alias = arrayOf("Run", "Treadmill", "Treadmill Running", "Jogging", "Outdoor Run", "Running (Treadmill)", "Running (Outdoor)"))
        ex("walking", "Walking", modality = Modality.CARDIO, primary = emptyList(), alias = arrayOf("Walk", "Incline Walk", "Treadmill Walk", "Incline Treadmill Walk", "Walking (Treadmill)"))
        ex("cycling", "Cycling", modality = Modality.CARDIO, primary = emptyList(), alias = arrayOf("Bike", "Stationary Bike", "Spin Bike", "Cycling (Indoor)", "Cycling (Outdoor)", "Assault Bike", "Air Bike", "Exercise Bike"))
        ex("rowing", "Rowing", modality = Modality.CARDIO, primary = emptyList(), alias = arrayOf("Rowing Machine", "Row Machine", "Rower", "Erg", "Rowing (Machine)", "Concept 2"))
        ex("elliptical", "Elliptical", modality = Modality.CARDIO, primary = emptyList(), alias = arrayOf("Elliptical Trainer", "Cross Trainer", "Elliptical Machine"))
        ex("stair_climber", "Stair Climber", modality = Modality.CARDIO, primary = emptyList(), alias = arrayOf("Stairmaster", "StairMaster", "Stair Master", "Stairs", "Stair Stepper"))
        ex("swimming", "Swimming", modality = Modality.CARDIO, primary = emptyList(), alias = arrayOf("Swim"))
        ex("jump_rope", "Jump Rope", modality = Modality.CARDIO, primary = listOf(CALVES), alias = arrayOf("Skipping", "Jump Rope (Cardio)", "Jumping Rope"))
    }

    val builtIn: List<Exercise> = builder.exercises
    val builtInAliases: Map<String, String> = builder.aliases

    fun byId(id: String): Exercise? = builtIn.firstOrNull { it.id == id }

    /** Guesses muscle groups and modality for a name that matches nothing in the catalogue. */
    fun guess(rawName: String, modalityHint: Modality?): Exercise {
        val n = NameNormalizer.normalize(rawName)
        val groups = MuscleGuesser.guess(n)
        val modality = modalityHint ?: if (groups.isEmpty() && MuscleGuesser.looksLikeCardio(n)) Modality.CARDIO else Modality.WEIGHTED
        return Exercise(
            id = NameNormalizer.toId(rawName),
            name = rawName.trim(),
            canonicalName = rawName.trim(),
            muscleGroups = groups,
            modality = modality,
            isDumbbell = "dumbbell" in n || n.startsWith("db ") || " db " in n,
            isBuiltIn = false,
        )
    }
}

object NameNormalizer {
    private val abbreviations = mapOf(
        "db" to "dumbbell",
        "dbs" to "dumbbell",
        "bb" to "barbell",
        "kb" to "kettlebell",
        "ohp" to "overhead press",
        "rdl" to "romanian deadlift",
        "tri" to "tricep",
        "bi" to "bicep",
    )

    /** Lower-case, punctuation-free, space-collapsed form used for alias look-ups. */
    fun normalize(raw: String): String {
        val lowered = raw.lowercase()
            .replace(Regex("[\\-_/]"), " ")
            .replace(Regex("[^a-z0-9 ()]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        val tokens = lowered.split(' ').map { token ->
            val plain = token.trim('(', ')')
            val expanded = abbreviations[plain] ?: plain
            if (token.startsWith("(") || token.endsWith(")")) token.replace(plain, expanded) else expanded
        }
        return tokens.joinToString(" ")
            .replace("triceps", "tricep")
            .replace("biceps", "bicep")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /** Looser form: drops position words and parenthesised qualifiers. Used as a second-pass match. */
    fun loose(raw: String): String {
        val n = normalize(raw).replace(Regex("\\(.*?\\)"), " ")
        val dropped = setOf("seated", "standing", "kneeling", "lying", "machine", "the", "with", "weighted", "single", "arm", "one", "alternating")
        return n.split(' ').filter { it.isNotEmpty() && it !in dropped }.joinToString(" ")
            .trimEnd('s') // crude plural strip on the last word ("dips" -> "dip")
    }

    fun toId(raw: String): String = "custom_" + normalize(raw).replace(Regex("[^a-z0-9]+"), "_").trim('_')
}

internal object MuscleGuesser {
    private val rules: List<Pair<Regex, List<MuscleContribution>>> = listOf(
        Regex("hollow|plank|crunch|sit ?up|ab |abs|leg raise|knee raise|rollout|russian|pallof|dead ?bug|l sit|toes to bar|core") to listOf(MuscleContribution(CORE, 1.0)),
        Regex("neck") to listOf(MuscleContribution(NECK, 1.0)),
        Regex("calf|calves") to listOf(MuscleContribution(CALVES, 1.0)),
        Regex("hamstring|leg curl|nordic|good morning|romanian|rdl|stiff") to listOf(MuscleContribution(HAMSTRINGS, 1.0), MuscleContribution(GLUTES, 0.5)),
        Regex("hip thrust|glute|bridge|abduct") to listOf(MuscleContribution(GLUTES, 1.0)),
        Regex("deadlift") to listOf(MuscleContribution(HAMSTRINGS, 1.0), MuscleContribution(GLUTES, 1.0), MuscleContribution(LOWER_BACK, 1.0), MuscleContribution(UPPER_BACK, 0.5)),
        Regex("squat|leg press|lunge|step up|leg extension|quad|adduct|split") to listOf(MuscleContribution(QUADS, 1.0), MuscleContribution(GLUTES, 0.5)),
        Regex("lateral raise|side raise|lat raise") to listOf(MuscleContribution(SIDE_DELTS, 1.0)),
        Regex("rear delt|reverse fly|face pull|reverse pec") to listOf(MuscleContribution(REAR_DELTS, 1.0), MuscleContribution(UPPER_BACK, 0.5)),
        Regex("front raise") to listOf(MuscleContribution(FRONT_DELTS, 1.0)),
        Regex("shoulder press|overhead press|military|arnold|push press|press behind") to listOf(MuscleContribution(FRONT_DELTS, 1.0), MuscleContribution(SIDE_DELTS, 1.0), MuscleContribution(TRICEPS, 0.5)),
        Regex("shrug|trap") to listOf(MuscleContribution(TRAPS, 1.0)),
        Regex("pull ?up|chin ?up|pulldown|pull down|pullover") to listOf(MuscleContribution(LATS, 1.0), MuscleContribution(BICEPS, 0.5), MuscleContribution(UPPER_BACK, 0.5)),
        Regex("\\brow\\b|rows") to listOf(MuscleContribution(UPPER_BACK, 1.0), MuscleContribution(LATS, 0.5), MuscleContribution(BICEPS, 0.5)),
        Regex("tricep|pushdown|push down|skull|kickback|extension") to listOf(MuscleContribution(TRICEPS, 1.0)),
        Regex("hammer|wrist|forearm|grip|hang|farmer|carry") to listOf(MuscleContribution(FOREARMS, 1.0)),
        Regex("curl|bicep") to listOf(MuscleContribution(BICEPS, 1.0)),
        Regex("bench|chest|fly|flye|pec|push ?up|dip|crossover") to listOf(MuscleContribution(CHEST, 1.0), MuscleContribution(TRICEPS, 0.5), MuscleContribution(FRONT_DELTS, 0.5)),
        Regex("back extension|hyperextension|lower back|superman") to listOf(MuscleContribution(LOWER_BACK, 1.0), MuscleContribution(GLUTES, 0.5)),
    )

    fun guess(normalized: String): List<MuscleContribution> {
        for ((regex, groups) in rules) if (regex.containsMatchIn(normalized)) return groups
        return emptyList()
    }

    fun looksLikeCardio(normalized: String): Boolean =
        Regex("run|jog|walk|bike|cycl|row(er|ing)|ellip|stair|swim|treadmill|sprint|hike|ski|cardio").containsMatchIn(normalized)
}
