package app.gains.catalogue

import app.gains.domain.Equipment
import app.gains.domain.Exercise
import app.gains.domain.Modality
import app.gains.domain.MuscleContribution
import app.gains.domain.MuscleGroup
import app.gains.domain.MuscleGroup.*
import app.gains.domain.TrainingModality

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
            trainingModality: TrainingModality = if (modality == Modality.CARDIO) TrainingModality.CARDIO else TrainingModality.STRENGTH,
            /** Null = guess from the name and flags (see [guessEquipment]). */
            equipment: Set<Equipment>? = null,
            vararg alias: String,
        ) {
            val groups = primary.map { MuscleContribution(it, 1.0) } + secondary.map { MuscleContribution(it, 0.5) }
            val gear = equipment ?: guessEquipment(name, modality, dumbbell)
            exercises.add(Exercise(id, name, name, groups, modality, dumbbell, isBuiltIn = true, equipment = gear, trainingModality = trainingModality))
            aliases[NameNormalizer.normalize(name)] = id
            alias.forEach { aliases[NameNormalizer.normalize(it)] = id }
        }

        /** Extra export spellings for an exercise declared above. */
        fun alias(id: String, vararg names: String) {
            require(exercises.any { it.id == id }) { "alias for unknown exercise $id" }
            for (n in names) aliases.getOrPut(NameNormalizer.normalize(n)) { id }
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
        ex("push_up", "Push Ups", modality = Modality.BODYWEIGHT, primary = listOf(CHEST), secondary = listOf(TRICEPS, FRONT_DELTS), alias = arrayOf("Push-Up", "Pushup", "Push Ups", "Pushups", "Press Up"))
        ex("dip", "Dips", modality = Modality.BODYWEIGHT, primary = listOf(CHEST, TRICEPS), secondary = listOf(FRONT_DELTS), alias = arrayOf("Dips", "Chest Dip", "Chest Dips", "Weighted Dip", "Parallel Bar Dip", "Tricep Dip"))

        // Shoulders
        ex("overhead_press", "Overhead Press", primary = listOf(FRONT_DELTS, SIDE_DELTS), secondary = listOf(TRICEPS), alias = arrayOf("Barbell Overhead Press", "Military Press", "Standing Overhead Press", "Strict Press", "Shoulder Press (Barbell)", "Barbell Shoulder Press", "OHP"))
        ex("db_shoulder_press", "Seated Dumbbell Shoulder Press", dumbbell = true, primary = listOf(FRONT_DELTS, SIDE_DELTS), secondary = listOf(TRICEPS), alias = arrayOf("Seated Dumbbell Shoulder Press", "Seated Shoulder Press", "Shoulder Press", "Seated Dumbbell Press", "Dumbbell Overhead Press", "Seated DB Press", "Shoulder Press (Dumbbell)", "Standing Dumbbell Shoulder Press"))
        ex("shoulder_press_machine", "Shoulder Press (Machine)", primary = listOf(FRONT_DELTS, SIDE_DELTS), secondary = listOf(TRICEPS), alias = arrayOf("Machine Shoulder Press", "Seated Shoulder Press (Machine)"))
        ex("arnold_press", "Arnold Press", dumbbell = true, primary = listOf(FRONT_DELTS, SIDE_DELTS), secondary = listOf(TRICEPS))
        ex("lateral_raise", "Dumbbell Lateral Raise", dumbbell = true, primary = listOf(SIDE_DELTS), alias = arrayOf("Dumbbell Lateral Raise", "Seated Dumbbell Lateral Raise", "Seated Lateral Raise", "Side Lateral Raise", "Lateral Raise (Dumbbell)", "DB Lateral Raise", "Side Raise"))
        ex("cable_lateral_raise", "Cable Lateral Raise", primary = listOf(SIDE_DELTS), alias = arrayOf("Lateral Raise (Cable)", "Single Arm Cable Lateral Raise"))
        ex("lateral_raise_machine", "Lateral Raise (Machine)", primary = listOf(SIDE_DELTS), alias = arrayOf("Machine Lateral Raise"))
        ex("front_raise", "Front Raise", dumbbell = true, primary = listOf(FRONT_DELTS), alias = arrayOf("Dumbbell Front Raise", "Plate Front Raise", "Cable Front Raise"))
        ex("rear_delt_fly", "Rear Delt Fly", dumbbell = true, primary = listOf(REAR_DELTS), secondary = listOf(UPPER_BACK), alias = arrayOf("Reverse Fly", "Rear Delt Raise", "Reverse Pec Deck", "Rear Delt Fly (Machine)", "Bent Over Reverse Fly", "Dumbbell Reverse Fly", "Rear Delt Machine", "Reverse Fly (Machine)"))
        ex("face_pull", "Face Pull", primary = listOf(REAR_DELTS), secondary = listOf(UPPER_BACK, TRAPS), alias = arrayOf("Cable Face Pull", "Face Pulls"))
        ex("upright_row", "Upright Row", primary = listOf(SIDE_DELTS, TRAPS), secondary = listOf(BICEPS), alias = arrayOf("Barbell Upright Row", "Cable Upright Row", "EZ Bar Upright Row", "EZ-Bar Upright Row"))

        // Back
        ex("pull_up", "Pull Up", modality = Modality.BODYWEIGHT, primary = listOf(LATS), secondary = listOf(BICEPS, UPPER_BACK), alias = arrayOf("Pull-Up", "Pullup", "Pull Ups", "Pullups", "Weighted Pull Up", "Wide Grip Pull Up"))
        ex("chin_up", "Chin Up", modality = Modality.BODYWEIGHT, primary = listOf(LATS, BICEPS), secondary = listOf(UPPER_BACK), alias = arrayOf("Chin-Up", "Chinup", "Chin Ups", "Weighted Chin Up"))
        ex("lat_pulldown", "Lat Pulldown", primary = listOf(LATS), secondary = listOf(BICEPS, UPPER_BACK), alias = arrayOf("Lat Pulldown (Cable)", "Wide Grip Lat Pulldown", "Pulldown", "Cable Pulldown", "Lat Pull Down", "Close Grip Lat Pulldown", "Lat Pulldown (Machine)"))
        ex("barbell_row", "Barbell Row", primary = listOf(UPPER_BACK, LATS), secondary = listOf(BICEPS, REAR_DELTS), alias = arrayOf("Bent Over Row", "Bent Over Barbell Row", "Bent-Over Row", "Pendlay Row", "Barbell Bent Over Row", "Row (Barbell)"))
        ex("db_row", "Dumbbell Row", dumbbell = true, primary = listOf(UPPER_BACK, LATS), secondary = listOf(BICEPS, REAR_DELTS), alias = arrayOf("One Arm Dumbbell Row", "Single Arm Dumbbell Row", "Single Arm Row", "One-Arm Row", "Dumbbell Bent Over Row", "Row (Dumbbell)", "DB Row"))
        ex("seated_cable_row", "Seated Cable Row", primary = listOf(UPPER_BACK), secondary = listOf(LATS, BICEPS), alias = arrayOf("Cable Row", "Seated Row", "Seated Row (Cable)", "Low Row", "Low Cable Row"))
        ex("row_machine", "Row (Machine)", primary = listOf(UPPER_BACK), secondary = listOf(LATS, BICEPS), alias = arrayOf("Machine Row", "Seated Row (Machine)", "Chest Supported Row", "Hammer Strength Row", "Iso-Lateral Row", "High Row"))
        ex("t_bar_row", "T-Bar Row", primary = listOf(UPPER_BACK), secondary = listOf(LATS, BICEPS), alias = arrayOf("T Bar Row", "Landmine Row"))
        ex("inverted_row", "Inverted Row", modality = Modality.BODYWEIGHT, primary = listOf(UPPER_BACK, LATS), secondary = listOf(BICEPS, REAR_DELTS), alias = arrayOf("Australian Pull Up", "Australian Pull-Up", "Bodyweight Row", "Ring Row", "Inverted Rows", "Body Row"))
        ex("straight_arm_pulldown", "Straight Arm Pulldown", primary = listOf(LATS), alias = arrayOf("Cable Pullover", "Straight-Arm Pulldown", "Rope Pullover"))
        ex("db_pullover", "Dumbbell Pullover", dumbbell = true, primary = listOf(LATS, CHEST), alias = arrayOf("Pullover"))
        ex("shrug", "Dumbbell Shrug", primary = listOf(TRAPS), alias = arrayOf("Barbell Shrug", "Dumbbell Shrug", "Shrugs", "Trap Bar Shrug", "Shrug (Dumbbell)", "Shrug (Barbell)"))
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
        ex("bodyweight_squat", "Bodyweight Squat", modality = Modality.BODYWEIGHT, primary = listOf(QUADS, GLUTES), secondary = listOf(HAMSTRINGS), alias = arrayOf("Air Squat", "Squat (Bodyweight)", "Bodyweight Squats", "Air Squats"))
        ex("pistol_squat", "Pistol Squat", modality = Modality.BODYWEIGHT, primary = listOf(QUADS, GLUTES), secondary = listOf(HAMSTRINGS, CORE), alias = arrayOf("Single Leg Squat", "Pistol Squats", "One Legged Squat"))
        ex("single_leg_rdl", "Single Leg Romanian Deadlift", dumbbell = true, primary = listOf(HAMSTRINGS, GLUTES), secondary = listOf(LOWER_BACK, CORE), alias = arrayOf("Single Leg RDL", "Single-Leg Romanian Deadlift", "One Leg Romanian Deadlift", "Single Leg Deadlift"))

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

        // Liftoff/custom names with deliberately authored mappings. Do not bucket unknown names.
        ex("incline_db_fly", "Incline Dumbbell Fly", dumbbell = true, primary = listOf(CHEST), secondary = listOf(FRONT_DELTS))
        ex("high_to_low_cable_fly", "High to Low Cable Fly", primary = listOf(CHEST), secondary = listOf(FRONT_DELTS))
        ex("pike_push_ups", "Pike Push Ups", modality = Modality.BODYWEIGHT, primary = listOf(FRONT_DELTS), secondary = listOf(TRICEPS, CHEST))
        ex("pseudo_planche_push_ups", "Pseudo Planche Push Ups", modality = Modality.BODYWEIGHT, primary = listOf(FRONT_DELTS), secondary = listOf(CHEST, TRICEPS))
        ex("handstand_hold", "Handstand Hold", modality = Modality.ISOMETRIC, primary = listOf(FRONT_DELTS), secondary = listOf(TRICEPS, CORE), trainingModality = TrainingModality.SKILL, alias = arrayOf("Handstand", "Wall Handstand", "Wall Handstand Hold", "Handstand Practice"))
        ex("face_to_wall_handstand_45", "Face to wall handstand (45)", modality = Modality.ISOMETRIC, primary = listOf(FRONT_DELTS), secondary = listOf(CORE), trainingModality = TrainingModality.SKILL)
        ex("abdominal_set", "Abdominal set", modality = Modality.BODYWEIGHT, primary = listOf(CORE))
        ex("wrist_prep", "Wrist prep", modality = Modality.ISOMETRIC, primary = emptyList(), trainingModality = TrainingModality.WARMUP)
        ex("shoulders_prep", "Shoulders prep", modality = Modality.ISOMETRIC, primary = emptyList(), trainingModality = TrainingModality.WARMUP)
        ex("core_prep", "Core prep", modality = Modality.ISOMETRIC, primary = emptyList(), trainingModality = TrainingModality.WARMUP)
        ex("boxing", "Boxing", modality = Modality.CARDIO, primary = emptyList(), trainingModality = TrainingModality.CARDIO)
        ex("hiit", "HIIT", modality = Modality.CARDIO, primary = emptyList(), trainingModality = TrainingModality.CARDIO)

        // Cardio
        ex("running", "Running", modality = Modality.CARDIO, primary = emptyList(), alias = arrayOf("Run", "Treadmill", "Treadmill Running", "Jogging", "Outdoor Run", "Running (Treadmill)", "Running (Outdoor)"))
        ex("walking", "Walking", modality = Modality.CARDIO, primary = emptyList(), alias = arrayOf("Walk", "Incline Walk", "Treadmill Walk", "Incline Treadmill Walk", "Walking (Treadmill)"))
        ex("cycling", "Cycling", modality = Modality.CARDIO, primary = emptyList(), alias = arrayOf("Bike", "Stationary Bike", "Spin Bike", "Cycling (Indoor)", "Cycling (Outdoor)", "Assault Bike", "Air Bike", "Exercise Bike"))
        ex("rowing", "Rowing Machine", modality = Modality.CARDIO, primary = emptyList(), alias = arrayOf("Rowing Machine", "Row Machine", "Rower", "Erg", "Rowing (Machine)", "Concept 2"))
        ex("elliptical", "Elliptical", modality = Modality.CARDIO, primary = emptyList(), alias = arrayOf("Elliptical Trainer", "Cross Trainer", "Elliptical Machine"))
        ex("stair_climber", "Stair Climber", modality = Modality.CARDIO, primary = emptyList(), alias = arrayOf("Stairmaster", "StairMaster", "Stair Master", "Stairs", "Stair Stepper"))
        ex("swimming", "Swimming", modality = Modality.CARDIO, primary = emptyList(), alias = arrayOf("Swim"))
        ex("jump_rope", "Jump Rope", modality = Modality.CARDIO, primary = listOf(CALVES), alias = arrayOf("Skipping", "Jump Rope (Cardio)", "Jumping Rope"))

        // Curated from free-exercise-db (public domain): gym staples the catalogue lacked.
        // Chest
        ex("cable_chest_press", "Cable Chest Press", equipment = setOf(Equipment.CABLE), primary = listOf(CHEST), secondary = listOf(FRONT_DELTS, TRICEPS), alias = arrayOf("Standing Cable Chest Press", "Cable Press", "Chest Press (Cable)")) // src: Cable Chest Press | beginner | compound
        ex("decline_dumbbell_bench_press", "Decline Dumbbell Bench Press", dumbbell = true, equipment = setOf(Equipment.DUMBBELL), primary = listOf(CHEST), secondary = listOf(FRONT_DELTS, TRICEPS), alias = arrayOf("Decline Dumbbell Press", "Decline Bench Press (Dumbbell)")) // src: Decline Dumbbell Bench Press | beginner | compound
        ex("decline_dumbbell_fly", "Decline Dumbbell Fly", dumbbell = true, equipment = setOf(Equipment.DUMBBELL), primary = listOf(CHEST), alias = arrayOf("Decline Dumbbell Flyes", "Decline Fly", "Decline Flye")) // src: Decline Dumbbell Flyes | beginner | compound
        ex("neutral_grip_dumbbell_bench_press", "Neutral Grip Dumbbell Bench Press", dumbbell = true, equipment = setOf(Equipment.DUMBBELL), primary = listOf(CHEST), secondary = listOf(FRONT_DELTS, TRICEPS), alias = arrayOf("Dumbbell Bench Press with Neutral Grip", "Hammer Grip Dumbbell Bench Press", "Neutral Grip Dumbbell Press")) // src: Dumbbell Bench Press with Neutral Grip | beginner | compound
        ex("dumbbell_floor_press", "Dumbbell Floor Press", dumbbell = true, equipment = setOf(Equipment.DUMBBELL), primary = listOf(CHEST, TRICEPS), secondary = listOf(FRONT_DELTS), alias = arrayOf("Floor Press (Dumbbell)")) // src: Dumbbell Floor Press | intermediate | compound
        ex("barbell_floor_press", "Barbell Floor Press", equipment = setOf(Equipment.BARBELL), primary = listOf(CHEST, TRICEPS), secondary = listOf(FRONT_DELTS), alias = arrayOf("Floor Press", "Floor Press (Barbell)", "One Arm Floor Press")) // src: Floor Press | intermediate | compound
        ex("neutral_grip_incline_dumbbell_press", "Neutral Grip Incline Dumbbell Press", dumbbell = true, equipment = setOf(Equipment.DUMBBELL), primary = listOf(CHEST), secondary = listOf(FRONT_DELTS, TRICEPS), alias = arrayOf("Hammer Grip Incline DB Bench Press", "Incline Dumbbell Bench With Palms Facing In", "Hammer Grip Incline Dumbbell Press", "Neutral Grip Incline Dumbbell Bench Press")) // src: Hammer Grip Incline DB Bench Press | beginner | compound
        ex("incline_cable_chest_press", "Incline Cable Chest Press", equipment = setOf(Equipment.CABLE), primary = listOf(CHEST), secondary = listOf(FRONT_DELTS, TRICEPS), alias = arrayOf("Incline Cable Press", "Low to High Cable Press")) // src: Incline Cable Chest Press | beginner | compound
        ex("incline_cable_fly", "Incline Cable Fly", equipment = setOf(Equipment.CABLE), primary = listOf(CHEST), secondary = listOf(FRONT_DELTS), alias = arrayOf("Incline Cable Flye", "Incline Cable Flyes", "Incline Cable Crossover")) // src: Incline Cable Flye | intermediate | isolation
        ex("decline_chest_press_machine", "Decline Chest Press (Machine)", equipment = setOf(Equipment.MACHINE), primary = listOf(CHEST), secondary = listOf(FRONT_DELTS, TRICEPS), alias = arrayOf("Leverage Decline Chest Press", "Machine Decline Press", "Decline Chest Press", "Hammer Strength Decline Press")) // src: Leverage Decline Chest Press | beginner | compound
        ex("incline_chest_press_machine", "Incline Chest Press (Machine)", equipment = setOf(Equipment.MACHINE), primary = listOf(CHEST), secondary = listOf(FRONT_DELTS, TRICEPS), alias = arrayOf("Leverage Incline Chest Press", "Machine Incline Press", "Incline Machine Press", "Hammer Strength Incline Press", "Incline Chest Press")) // src: Leverage Incline Chest Press | beginner | compound
        ex("smith_machine_bench_press", "Smith Machine Bench Press", equipment = setOf(Equipment.MACHINE), primary = listOf(CHEST), secondary = listOf(FRONT_DELTS, TRICEPS), alias = arrayOf("Bench Press (Smith Machine)", "Smith Bench Press")) // src: Smith Machine Bench Press | beginner | compound
        ex("smith_machine_decline_bench_press", "Smith Machine Decline Bench Press", equipment = setOf(Equipment.MACHINE), primary = listOf(CHEST), secondary = listOf(FRONT_DELTS, TRICEPS), alias = arrayOf("Smith Machine Decline Press", "Decline Smith Press", "Decline Bench Press (Smith Machine)")) // src: Smith Machine Decline Press | beginner | compound
        ex("smith_machine_incline_bench_press", "Smith Machine Incline Bench Press", equipment = setOf(Equipment.MACHINE), primary = listOf(CHEST), secondary = listOf(FRONT_DELTS, TRICEPS), alias = arrayOf("Incline Bench Press (Smith Machine)", "Smith Incline Press", "Smith Machine Incline Press")) // src: Smith Machine Incline Bench Press | beginner | compound
        ex("svend_press", "Svend Press", equipment = setOf(Equipment.OTHER), primary = listOf(CHEST), secondary = listOf(FOREARMS, FRONT_DELTS, TRICEPS), alias = arrayOf("Plate Squeeze Press", "Plate Press")) // src: Svend Press | beginner | compound
        ex("wide_grip_bench_press", "Wide Grip Bench Press", equipment = setOf(Equipment.BARBELL), primary = listOf(CHEST), secondary = listOf(FRONT_DELTS, TRICEPS), alias = arrayOf("Wide-Grip Barbell Bench Press", "Wide Grip Barbell Bench Press")) // src: Wide-Grip Barbell Bench Press | intermediate | compound

        // Shoulders
        ex("barbell_rear_delt_row", "Barbell Rear Delt Row", equipment = setOf(Equipment.BARBELL), primary = listOf(REAR_DELTS), secondary = listOf(BICEPS, LATS, UPPER_BACK), alias = arrayOf("Rear Delt Row", "Rear Delt Row (Barbell)")) // src: Barbell Rear Delt Row | beginner | compound
        ex("chest_supported_rear_delt_raise", "Chest Supported Rear Delt Raise", dumbbell = true, equipment = setOf(Equipment.DUMBBELL), primary = listOf(REAR_DELTS), alias = arrayOf("Bent Over Dumbbell Rear Delt Raise With Head On Bench", "Head Supported Rear Delt Raise", "Incline Rear Delt Raise", "Chest Supported Reverse Fly", "Incline Reverse Fly")) // src: Bent Over Dumbbell Rear Delt Raise With Head On Bench | beginner | isolation
        ex("cable_internal_rotation", "Cable Internal Rotation", equipment = setOf(Equipment.CABLE), primary = listOf(FRONT_DELTS), secondary = listOf(CHEST), alias = arrayOf("Internal Rotation with Cable", "Cable Shoulder Internal Rotation", "Internal Rotation (Cable)")) // src: Cable Internal Rotation | beginner | compound // REVIEW rotator cuff
        ex("cable_rear_delt_fly", "Cable Rear Delt Fly", equipment = setOf(Equipment.CABLE), primary = listOf(REAR_DELTS), alias = arrayOf("Cable Reverse Fly", "Rear Delt Fly (Cable)", "Cable Rear Delt Crossover")) // src: Cable Rear Delt Fly | beginner | isolation
        ex("cable_rear_delt_row", "Cable Rear Delt Row", equipment = setOf(Equipment.CABLE), primary = listOf(REAR_DELTS), secondary = listOf(BICEPS, UPPER_BACK), alias = arrayOf("Cable Rope Rear-Delt Rows", "Rope Rear Delt Row", "Rear Delt Row (Cable)")) // src: Cable Rope Rear-Delt Rows | beginner | compound
        ex("cable_shoulder_press", "Cable Shoulder Press", equipment = setOf(Equipment.CABLE), primary = listOf(FRONT_DELTS, SIDE_DELTS), secondary = listOf(TRICEPS), alias = arrayOf("Seated Cable Shoulder Press", "Alternating Cable Shoulder Press", "Shoulder Press (Cable)")) // src: Cable Shoulder Press | beginner | compound
        ex("cuban_press", "Cuban Press", dumbbell = true, equipment = setOf(Equipment.DUMBBELL), primary = listOf(REAR_DELTS, SIDE_DELTS), secondary = listOf(TRAPS), alias = arrayOf("Cuban Rotation")) // src: Cuban Press | intermediate | compound
        ex("dumbbell_scaption", "Dumbbell Scaption", dumbbell = true, equipment = setOf(Equipment.DUMBBELL), primary = listOf(FRONT_DELTS, SIDE_DELTS), secondary = listOf(TRAPS), alias = arrayOf("Scaption", "Scapular Plane Raise", "Y Raise")) // src: Dumbbell Scaption | beginner | isolation // REVIEW shoulders
        ex("dumbbell_external_rotation", "Dumbbell External Rotation", dumbbell = true, equipment = setOf(Equipment.DUMBBELL), primary = listOf(REAR_DELTS), alias = arrayOf("External Rotation", "Shoulder External Rotation", "Side Lying External Rotation", "External Rotation (Dumbbell)")) // src: External Rotation | beginner | isolation // REVIEW rotator cuff
        ex("cable_external_rotation", "Cable External Rotation", equipment = setOf(Equipment.CABLE), primary = listOf(REAR_DELTS), alias = arrayOf("External Rotation with Cable", "Cable Shoulder External Rotation", "External Rotation (Cable)")) // src: External Rotation with Cable | beginner | isolation // REVIEW rotator cuff
        ex("push_press", "Push Press", equipment = setOf(Equipment.BARBELL), primary = listOf(FRONT_DELTS, SIDE_DELTS), secondary = listOf(TRICEPS, QUADS), alias = arrayOf("Barbell Push Press")) // src: Push Press | expert | compound
        ex("seated_barbell_overhead_press", "Seated Barbell Overhead Press", equipment = setOf(Equipment.BARBELL), primary = listOf(FRONT_DELTS, SIDE_DELTS), secondary = listOf(TRICEPS), alias = arrayOf("Seated Barbell Military Press", "Seated Military Press", "Seated Barbell Shoulder Press", "Seated Overhead Press", "Seated Barbell Press")) // src: Seated Barbell Military Press | intermediate | compound
        ex("seated_rear_delt_raise", "Seated Rear Delt Raise", dumbbell = true, equipment = setOf(Equipment.DUMBBELL), primary = listOf(REAR_DELTS), alias = arrayOf("Seated Bent-Over Rear Delt Raise", "Seated Bent Over Rear Delt Fly", "Seated Reverse Fly", "Seated Rear Delt Fly")) // src: Seated Bent-Over Rear Delt Raise | intermediate | isolation
        ex("smith_machine_shoulder_press", "Smith Machine Shoulder Press", equipment = setOf(Equipment.MACHINE), primary = listOf(FRONT_DELTS, SIDE_DELTS), secondary = listOf(TRICEPS), alias = arrayOf("Smith Machine Overhead Shoulder Press", "Shoulder Press (Smith Machine)", "Smith Machine Overhead Press", "Smith Press")) // src: Smith Machine Overhead Shoulder Press | beginner | compound
        ex("behind_the_neck_press", "Behind the Neck Press", equipment = setOf(Equipment.BARBELL), primary = listOf(FRONT_DELTS, SIDE_DELTS), secondary = listOf(TRICEPS), alias = arrayOf("Standing Barbell Press Behind Neck", "Behind the Neck Shoulder Press", "BTN Press", "Behind Neck Press", "Push Press - Behind the Neck")) // src: Standing Barbell Press Behind Neck | intermediate | compound
        ex("dumbbell_upright_row", "Dumbbell Upright Row", dumbbell = true, equipment = setOf(Equipment.DUMBBELL), primary = listOf(SIDE_DELTS, TRAPS), secondary = listOf(BICEPS), alias = arrayOf("Standing Dumbbell Upright Row", "Upright Row (Dumbbell)")) // src: Standing Dumbbell Upright Row | beginner | compound
        ex("barbell_front_raise", "Barbell Front Raise", equipment = setOf(Equipment.BARBELL), primary = listOf(FRONT_DELTS), alias = arrayOf("Standing Front Barbell Raise Over Head", "Front Raise (Barbell)", "Barbell Front Delt Raise")) // src: Standing Front Barbell Raise Over Head | intermediate | isolation
        ex("neutral_grip_dumbbell_shoulder_press", "Neutral Grip Dumbbell Shoulder Press", dumbbell = true, equipment = setOf(Equipment.DUMBBELL), primary = listOf(FRONT_DELTS, SIDE_DELTS), secondary = listOf(TRICEPS), alias = arrayOf("Standing Palms-In Dumbbell Press", "Standing Palm-In One-Arm Dumbbell Press", "Hammer Grip Shoulder Press", "Neutral Grip Shoulder Press")) // src: Standing Palms-In Dumbbell Press | intermediate | compound
        ex("landmine_press", "Landmine Press", equipment = setOf(Equipment.BARBELL), primary = listOf(FRONT_DELTS, CHEST), secondary = listOf(TRICEPS, SIDE_DELTS, CORE), alias = arrayOf("Landmine Shoulder Press", "Single Arm Landmine Press", "Half Kneeling Landmine Press")) // src: manual | beginner | compound

        // Back
        ex("behind_the_back_shrug", "Behind the Back Shrug", equipment = setOf(Equipment.BARBELL), primary = listOf(TRAPS), secondary = listOf(FOREARMS, UPPER_BACK), alias = arrayOf("Barbell Shrug Behind The Back", "Smith Machine Behind the Back Shrug", "Behind the Back Barbell Shrug")) // src: Barbell Shrug Behind The Back | beginner | isolation
        ex("barbell_pullover", "Barbell Pullover", equipment = setOf(Equipment.BARBELL), primary = listOf(LATS), secondary = listOf(CHEST, TRICEPS), alias = arrayOf("Bent-Arm Barbell Pullover", "Wide-Grip Decline Barbell Pullover", "Pullover (Barbell)")) // src: Bent-Arm Barbell Pullover | intermediate | compound
        ex("bent_over_dumbbell_row", "Bent Over Dumbbell Row", dumbbell = true, equipment = setOf(Equipment.DUMBBELL), primary = listOf(UPPER_BACK), secondary = listOf(LATS, BICEPS, REAR_DELTS), alias = arrayOf("Bent Over Two-Dumbbell Row", "Bent Over Two-Dumbbell Row With Palms In", "Two Arm Dumbbell Row", "Double Dumbbell Row")) // src: Bent Over Two-Dumbbell Row | beginner | compound
        ex("cable_shrug", "Cable Shrug", equipment = setOf(Equipment.CABLE), primary = listOf(TRAPS), alias = arrayOf("Cable Shrugs", "Shrug (Cable)")) // src: Cable Shrugs | beginner | isolation
        ex("deficit_deadlift", "Deficit Deadlift", equipment = setOf(Equipment.BARBELL), primary = listOf(HAMSTRINGS, GLUTES, LOWER_BACK), secondary = listOf(UPPER_BACK, TRAPS, QUADS, FOREARMS), alias = arrayOf("Deadlift from Deficit")) // src: Deficit Deadlift | intermediate | compound
        ex("chest_supported_dumbbell_row", "Chest Supported Dumbbell Row", dumbbell = true, equipment = setOf(Equipment.DUMBBELL), primary = listOf(UPPER_BACK), secondary = listOf(LATS, BICEPS, FOREARMS, REAR_DELTS), alias = arrayOf("Dumbbell Incline Row", "Incline Dumbbell Row", "Chest Supported Row (Dumbbell)", "Incline Bench Dumbbell Row")) // src: Dumbbell Incline Row | beginner | compound
        ex("seal_row", "Seal Row", equipment = setOf(Equipment.BARBELL), primary = listOf(UPPER_BACK), secondary = listOf(LATS, REAR_DELTS), alias = arrayOf("Incline Bench Pull", "Straight Bar Bench Mid Rows", "Chest Supported Barbell Row", "Bench Row", "Barbell Seal Row")) // src: Incline Bench Pull | beginner | isolation
        ex("high_cable_row", "High Cable Row", equipment = setOf(Equipment.CABLE), primary = listOf(LATS), secondary = listOf(UPPER_BACK, BICEPS), alias = arrayOf("Kneeling High Pulley Row", "Kneeling Single-Arm High Pulley Row", "High Pulley Row", "High Row (Cable)", "Kneeling Cable Row")) // src: Kneeling High Pulley Row | beginner | compound
        ex("deadlift_machine", "Deadlift (Machine)", equipment = setOf(Equipment.MACHINE), primary = listOf(QUADS, GLUTES, HAMSTRINGS), secondary = listOf(LOWER_BACK), alias = arrayOf("Leverage Deadlift", "Machine Deadlift", "Hammer Strength Deadlift")) // src: Leverage Deadlift | beginner | compound
        ex("shrug_machine", "Shrug (Machine)", equipment = setOf(Equipment.MACHINE), primary = listOf(TRAPS), secondary = listOf(FOREARMS), alias = arrayOf("Leverage Shrug", "Machine Shrug", "Hammer Strength Shrug", "Shrug Machine")) // src: Leverage Shrug | beginner | isolation
        ex("meadows_row", "Meadows Row", equipment = setOf(Equipment.BARBELL), primary = listOf(UPPER_BACK), secondary = listOf(LATS, BICEPS), alias = arrayOf("One-Arm Long Bar Row", "Bent Over One-Arm Long Bar Row", "One Arm Landmine Row", "Single Arm Landmine Row")) // src: One-Arm Long Bar Row | beginner | compound
        ex("rack_pull", "Rack Pull", equipment = setOf(Equipment.BARBELL), primary = listOf(LOWER_BACK, GLUTES, HAMSTRINGS), secondary = listOf(TRAPS, UPPER_BACK, FOREARMS), alias = arrayOf("Rack Pulls", "Block Pull", "Rack Deadlift", "Rack Pull (Barbell)")) // src: Rack Pulls | intermediate | compound
        ex("reverse_grip_barbell_row", "Reverse Grip Barbell Row", equipment = setOf(Equipment.BARBELL), primary = listOf(UPPER_BACK), secondary = listOf(LATS, BICEPS, REAR_DELTS), alias = arrayOf("Reverse Grip Bent-Over Rows", "Underhand Barbell Row", "Yates Row", "Supinated Barbell Row", "Reverse Grip Bent Over Row")) // src: Reverse Grip Bent-Over Rows | intermediate | compound
        ex("single_arm_cable_row", "Single Arm Cable Row", equipment = setOf(Equipment.CABLE), primary = listOf(UPPER_BACK), secondary = listOf(LATS, BICEPS, TRAPS), alias = arrayOf("Seated One-arm Cable Pulley Rows", "One Arm Cable Row", "Single Arm Seated Cable Row", "One-Arm Seated Cable Row", "Shotgun Row")) // src: Seated One-arm Cable Pulley Rows | intermediate | compound
        ex("smith_machine_row", "Smith Machine Row", equipment = setOf(Equipment.MACHINE), primary = listOf(UPPER_BACK), secondary = listOf(LATS, BICEPS, REAR_DELTS), alias = arrayOf("Smith Machine Bent Over Row", "Row (Smith Machine)", "Smith Machine Bent-Over Row")) // src: Smith Machine Bent Over Row | beginner | compound
        ex("reverse_grip_lat_pulldown", "Reverse Grip Lat Pulldown", equipment = setOf(Equipment.CABLE), primary = listOf(LATS), secondary = listOf(UPPER_BACK, BICEPS, REAR_DELTS), alias = arrayOf("Underhand Cable Pulldowns", "Underhand Pulldown", "Underhand Lat Pulldown", "Supinated Pulldown", "Reverse Grip Pulldown")) // src: Underhand Cable Pulldowns | beginner | compound
        ex("v_bar_pulldown", "V-Bar Pulldown", equipment = setOf(Equipment.CABLE), primary = listOf(LATS), secondary = listOf(UPPER_BACK, BICEPS, REAR_DELTS), alias = arrayOf("Neutral Grip Pulldown", "Close Grip V-Bar Pulldown", "Neutral Grip Lat Pulldown")) // src: V-Bar Pulldown | intermediate | compound
        ex("behind_the_neck_pulldown", "Behind the Neck Pulldown", equipment = setOf(Equipment.CABLE), primary = listOf(LATS), secondary = listOf(UPPER_BACK, BICEPS, REAR_DELTS), alias = arrayOf("Wide-Grip Pulldown Behind The Neck", "Behind the Neck Lat Pulldown", "Behind Neck Pulldown")) // src: Wide-Grip Pulldown Behind The Neck | intermediate | compound

        // Arms
        ex("board_press", "Board Press", equipment = setOf(Equipment.BARBELL), primary = listOf(TRICEPS), secondary = listOf(CHEST, FOREARMS, LATS, FRONT_DELTS), alias = arrayOf("Bench Press to Boards")) // src: Board Press | intermediate | compound
        ex("close_grip_dumbbell_press", "Close Grip Dumbbell Press", dumbbell = true, equipment = setOf(Equipment.DUMBBELL), primary = listOf(TRICEPS), secondary = listOf(CHEST, FRONT_DELTS), alias = arrayOf("Crush Press", "Squeeze Press", "Crush Grip Dumbbell Press")) // src: Close-Grip Dumbbell Press | beginner | compound
        ex("dip_machine", "Dip (Machine)", equipment = setOf(Equipment.MACHINE), primary = listOf(TRICEPS, CHEST), secondary = listOf(FRONT_DELTS), alias = arrayOf("Dip Machine", "Machine Dip", "Seated Dip Machine", "Triceps Dip Machine")) // src: Dip Machine | beginner | compound
        ex("drag_curl", "Drag Curl", equipment = setOf(Equipment.BARBELL), primary = listOf(BICEPS), secondary = listOf(FOREARMS), alias = arrayOf("Barbell Drag Curl")) // src: Drag Curl | intermediate | compound
        ex("finger_curl", "Finger Curl", equipment = setOf(Equipment.BARBELL), primary = listOf(FOREARMS), alias = arrayOf("Finger Curls", "Barbell Finger Curl", "Finger Roll")) // src: Finger Curls | beginner | isolation
        ex("high_cable_curl", "High Cable Curl", equipment = setOf(Equipment.CABLE), primary = listOf(BICEPS), alias = arrayOf("High Cable Curls", "Overhead Cable Curl", "Cable Front Double Biceps Curl", "High Pulley Curl")) // src: High Cable Curls | intermediate | compound
        ex("incline_hammer_curl", "Incline Hammer Curl", dumbbell = true, equipment = setOf(Equipment.DUMBBELL), primary = listOf(BICEPS), alias = arrayOf("Incline Hammer Curls", "Incline Dumbbell Hammer Curl")) // src: Incline Hammer Curls | beginner | isolation
        ex("jm_press", "JM Press", equipment = setOf(Equipment.BARBELL), primary = listOf(TRICEPS), secondary = listOf(CHEST, FRONT_DELTS), alias = arrayOf("Barbell JM Press")) // src: JM Press | beginner | compound
        ex("dumbbell_skull_crusher", "Dumbbell Skull Crusher", dumbbell = true, equipment = setOf(Equipment.DUMBBELL), primary = listOf(TRICEPS), secondary = listOf(CHEST, FRONT_DELTS), alias = arrayOf("Lying Dumbbell Tricep Extension", "Dumbbell Lying Triceps Extension", "Skull Crusher (Dumbbell)", "Dumbbell Skullcrusher", "Decline Dumbbell Triceps Extension")) // src: Lying Dumbbell Tricep Extension | intermediate | isolation
        ex("bicep_curl_machine", "Bicep Curl (Machine)", equipment = setOf(Equipment.MACHINE), primary = listOf(BICEPS), alias = arrayOf("Machine Bicep Curl", "Machine Curl", "Bicep Curl Machine", "Machine Biceps Curl")) // src: Machine Bicep Curl | beginner | isolation
        ex("triceps_extension_machine", "Triceps Extension (Machine)", equipment = setOf(Equipment.MACHINE), primary = listOf(TRICEPS), alias = arrayOf("Machine Triceps Extension", "Machine Tricep Extension", "Seated Triceps Extension Machine", "Triceps Machine")) // src: Machine Triceps Extension | beginner | isolation
        ex("pin_press", "Pin Press", equipment = setOf(Equipment.BARBELL), primary = listOf(CHEST, TRICEPS), secondary = listOf(FOREARMS, LATS, UPPER_BACK, FRONT_DELTS), alias = arrayOf("Pin Presses", "Pin Bench Press", "Dead Bench Press")) // src: Pin Presses | intermediate | compound
        ex("plate_pinch", "Plate Pinch", modality = Modality.ISOMETRIC, equipment = setOf(Equipment.OTHER), primary = listOf(FOREARMS), alias = arrayOf("Plate Pinch Hold", "Pinch Grip")) // src: Plate Pinch | intermediate | isolation
        ex("cable_reverse_curl", "Cable Reverse Curl", equipment = setOf(Equipment.CABLE), primary = listOf(FOREARMS, BICEPS), alias = arrayOf("Reverse Cable Curl", "Reverse Grip Cable Curl", "Reverse Curl (Cable)")) // src: Reverse Cable Curl | beginner | isolation
        ex("reverse_grip_pushdown", "Reverse Grip Pushdown", equipment = setOf(Equipment.CABLE), primary = listOf(TRICEPS), alias = arrayOf("Reverse Grip Triceps Pushdown", "Underhand Pushdown", "Reverse Grip Tricep Pushdown", "Reverse Grip Triceps Extension")) // src: Reverse Grip Triceps Pushdown | beginner | isolation
        ex("reverse_grip_bench_press", "Reverse Grip Bench Press", equipment = setOf(Equipment.BARBELL), primary = listOf(TRICEPS), secondary = listOf(CHEST, FRONT_DELTS), alias = arrayOf("Reverse Triceps Bench Press", "Reverse Grip Barbell Bench Press", "Reverse Bench Press")) // src: Reverse Triceps Bench Press | intermediate | compound
        ex("smith_machine_close_grip_bench_press", "Smith Machine Close Grip Bench Press", equipment = setOf(Equipment.MACHINE), primary = listOf(TRICEPS), secondary = listOf(CHEST, FRONT_DELTS), alias = arrayOf("Close Grip Bench Press (Smith Machine)")) // src: Smith Machine Close-Grip Bench Press | beginner | compound
        ex("spider_curl", "Spider Curl", equipment = setOf(Equipment.BARBELL, Equipment.DUMBBELL), primary = listOf(BICEPS), alias = arrayOf("Dumbbell Prone Incline Curl", "Dumbbell Spider Curl", "Prone Incline Curl", "Lying High Bench Barbell Curl", "Standing One-Arm Dumbbell Curl Over Incline Bench")) // src: Spider Curl | beginner | isolation
        ex("barbell_overhead_triceps_extension", "Barbell Overhead Triceps Extension", equipment = setOf(Equipment.BARBELL), primary = listOf(TRICEPS), secondary = listOf(FRONT_DELTS), alias = arrayOf("Standing Overhead Barbell Triceps Extension", "Overhead Barbell Triceps Extension", "French Press", "Overhead Tricep Extension (Barbell)")) // src: Standing Overhead Barbell Triceps Extension | beginner | isolation
        ex("behind_the_back_wrist_curl", "Behind the Back Wrist Curl", equipment = setOf(Equipment.BARBELL), primary = listOf(FOREARMS), alias = arrayOf("Standing Palms-Up Barbell Behind The Back Wrist Curl", "Behind the Back Barbell Wrist Curl")) // src: Standing Palms-Up Barbell Behind The Back Wrist Curl | beginner | isolation
        ex("tate_press", "Tate Press", dumbbell = true, equipment = setOf(Equipment.DUMBBELL), primary = listOf(TRICEPS), secondary = listOf(CHEST, FRONT_DELTS), alias = arrayOf("Elbows Out Extension", "Dumbbell Tate Press")) // src: Tate Press | intermediate | isolation
        ex("wrist_roller", "Wrist Roller", equipment = setOf(Equipment.OTHER), primary = listOf(FOREARMS), secondary = listOf(FRONT_DELTS), alias = arrayOf("Wrist Roller Curl")) // src: Wrist Roller | beginner | isolation
        ex("zottman_curl", "Zottman Curl", dumbbell = true, equipment = setOf(Equipment.DUMBBELL), primary = listOf(BICEPS), secondary = listOf(FOREARMS), alias = arrayOf("Zottman Curls", "Dumbbell Zottman Curl", "Zottman Preacher Curl")) // src: Zottman Curl | intermediate | isolation

        // Legs
        ex("barbell_hack_squat", "Barbell Hack Squat", equipment = setOf(Equipment.BARBELL), primary = listOf(QUADS), secondary = listOf(GLUTES, CALVES, FOREARMS, HAMSTRINGS), alias = arrayOf("Hack Squat (Barbell)", "Reverse Deadlift")) // src: Barbell Hack Squat | intermediate | compound
        ex("side_split_squat", "Side Split Squat", equipment = setOf(Equipment.BARBELL), primary = listOf(QUADS), secondary = listOf(GLUTES, CALVES, HAMSTRINGS, LOWER_BACK), alias = arrayOf("Barbell Side Split Squat", "Lateral Squat", "Barbell Lateral Lunge", "Cossack Squat")) // src: Barbell Side Split Squat | beginner | compound
        ex("box_squat", "Box Squat", equipment = setOf(Equipment.BARBELL), primary = listOf(QUADS), secondary = listOf(GLUTES, CALVES, HAMSTRINGS, LOWER_BACK), alias = arrayOf("Barbell Box Squat", "Barbell Squat To A Bench", "Squat to Box", "Dumbbell Squat To A Bench", "Dumbbell Box Squat")) // src: Box Squat | intermediate | compound
        ex("cable_hip_adduction", "Cable Hip Adduction", equipment = setOf(Equipment.CABLE), primary = listOf(QUADS), alias = arrayOf("Cable Adduction", "Standing Cable Adduction")) // src: Cable Hip Adduction | beginner | isolation
        ex("donkey_calf_raise", "Donkey Calf Raise", equipment = setOf(Equipment.MACHINE), primary = listOf(CALVES), alias = arrayOf("Donkey Calf Raises", "Donkey Calf Raise (Machine)")) // src: Donkey Calf Raises | intermediate | isolation
        ex("deficit_reverse_lunge", "Deficit Reverse Lunge", equipment = setOf(Equipment.BARBELL), primary = listOf(QUADS), secondary = listOf(GLUTES, HAMSTRINGS), alias = arrayOf("Elevated Back Lunge", "Elevated Reverse Lunge", "Deficit Lunge")) // src: Elevated Back Lunge | intermediate | compound
        ex("glute_ham_raise", "Glute Ham Raise", equipment = setOf(Equipment.MACHINE), primary = listOf(HAMSTRINGS), secondary = listOf(CALVES, GLUTES), alias = arrayOf("GHR", "Glute Ham Raise (Machine)", "Glute Ham Developer")) // src: Glute Ham Raise | intermediate | compound
        ex("jefferson_squat", "Jefferson Squat", equipment = setOf(Equipment.BARBELL), primary = listOf(QUADS), secondary = listOf(GLUTES, CALVES, HAMSTRINGS, LOWER_BACK, TRAPS), alias = arrayOf("Jefferson Squats", "Jefferson Deadlift")) // src: Jefferson Squats | intermediate | compound
        ex("sumo_squat", "Sumo Squat", dumbbell = true, equipment = setOf(Equipment.DUMBBELL), primary = listOf(QUADS), secondary = listOf(GLUTES, CORE, CALVES, HAMSTRINGS), alias = arrayOf("Plie Dumbbell Squat", "Plie Squat", "Dumbbell Sumo Squat", "Sumo Squat (Dumbbell)", "Kettlebell Sumo Squat")) // src: Plie Dumbbell Squat | beginner | compound
        ex("cable_pull_through", "Cable Pull Through", equipment = setOf(Equipment.CABLE), primary = listOf(GLUTES), secondary = listOf(HAMSTRINGS, LOWER_BACK), alias = arrayOf("Pull Through", "Pull-Through", "Pull Throughs")) // src: Pull Through | beginner | compound
        ex("reverse_calf_raise", "Reverse Calf Raise", equipment = setOf(Equipment.MACHINE), primary = listOf(CALVES), alias = arrayOf("Smith Machine Reverse Calf Raises", "Tibialis Raise", "Tib Raise", "Reverse Calf Raises")) // src: Smith Machine Reverse Calf Raises | beginner | isolation
        ex("smith_machine_romanian_deadlift", "Smith Machine Romanian Deadlift", equipment = setOf(Equipment.MACHINE), primary = listOf(HAMSTRINGS), secondary = listOf(GLUTES, LOWER_BACK), alias = arrayOf("Smith Machine Stiff-Legged Deadlift", "Romanian Deadlift (Smith Machine)", "Smith Machine Stiff Leg Deadlift")) // src: Smith Machine Stiff-Legged Deadlift | beginner | compound
        ex("sissy_squat", "Sissy Squat", modality = Modality.BODYWEIGHT, equipment = setOf(Equipment.BODYWEIGHT), primary = listOf(QUADS), secondary = listOf(GLUTES, CALVES, HAMSTRINGS), alias = arrayOf("Weighted Sissy Squat", "Sissy Squats")) // src: Weighted Sissy Squat | expert | compound
        ex("zercher_squat", "Zercher Squat", equipment = setOf(Equipment.BARBELL), primary = listOf(QUADS), secondary = listOf(GLUTES, CALVES, HAMSTRINGS), alias = arrayOf("Zercher Squats", "Barbell Zercher Squat")) // src: Zercher Squats | expert | compound
        ex("barbell_thruster", "Barbell Thruster", equipment = setOf(Equipment.BARBELL), primary = listOf(QUADS, FRONT_DELTS, SIDE_DELTS), secondary = listOf(GLUTES, TRICEPS, CORE), alias = arrayOf("Thruster", "Thrusters", "Front Squat to Press")) // src: manual | intermediate | compound

        // Olympic Lifts
        ex("clean", "Clean", equipment = setOf(Equipment.BARBELL), primary = listOf(QUADS, GLUTES, HAMSTRINGS), secondary = listOf(TRAPS, LOWER_BACK, FRONT_DELTS, CORE), alias = arrayOf("Squat Clean", "Full Clean", "Barbell Clean")) // src: Clean | intermediate | compound
        ex("clean_pull", "Clean Pull", equipment = setOf(Equipment.BARBELL), primary = listOf(HAMSTRINGS, GLUTES, TRAPS), secondary = listOf(QUADS, LOWER_BACK, UPPER_BACK), alias = arrayOf("Barbell Clean Pull")) // src: Clean Pull | intermediate | compound
        ex("clean_and_jerk", "Clean and Jerk", equipment = setOf(Equipment.BARBELL), primary = listOf(QUADS, GLUTES, HAMSTRINGS, FRONT_DELTS), secondary = listOf(TRAPS, LOWER_BACK, TRICEPS, CORE), alias = arrayOf("Clean & Jerk", "C&J", "Barbell Clean and Jerk")) // src: Clean and Jerk | expert | compound
        ex("clean_and_press", "Clean and Press", equipment = setOf(Equipment.BARBELL), primary = listOf(FRONT_DELTS, SIDE_DELTS, GLUTES, HAMSTRINGS), secondary = listOf(TRICEPS, QUADS, LOWER_BACK, TRAPS), alias = arrayOf("Barbell Clean and Press", "Clean & Press")) // src: Clean and Press | intermediate | compound
        ex("dumbbell_clean", "Dumbbell Clean", dumbbell = true, equipment = setOf(Equipment.DUMBBELL), primary = listOf(QUADS, GLUTES, HAMSTRINGS), secondary = listOf(TRAPS, LOWER_BACK, FRONT_DELTS, CORE), alias = arrayOf("Dumbbell Power Clean")) // src: Dumbbell Clean | intermediate | compound
        ex("hang_clean", "Hang Clean", equipment = setOf(Equipment.BARBELL), primary = listOf(QUADS, GLUTES, HAMSTRINGS), secondary = listOf(TRAPS, LOWER_BACK, FRONT_DELTS, CORE), alias = arrayOf("Hang Power Clean", "Barbell Hang Clean", "Smith Machine Hang Power Clean")) // src: Hang Clean | intermediate | compound
        ex("hang_snatch", "Hang Snatch", equipment = setOf(Equipment.BARBELL), primary = listOf(QUADS, GLUTES, HAMSTRINGS), secondary = listOf(TRAPS, LOWER_BACK, FRONT_DELTS, CORE), alias = arrayOf("Hang Power Snatch", "Barbell Hang Snatch")) // src: Hang Snatch | expert | compound
        ex("muscle_snatch", "Muscle Snatch", equipment = setOf(Equipment.BARBELL), primary = listOf(TRAPS, FRONT_DELTS, SIDE_DELTS), secondary = listOf(HAMSTRINGS, GLUTES, UPPER_BACK), alias = arrayOf("Barbell Muscle Snatch")) // src: Muscle Snatch | intermediate | compound
        ex("overhead_squat", "Overhead Squat", equipment = setOf(Equipment.BARBELL), primary = listOf(QUADS, GLUTES), secondary = listOf(CORE, FRONT_DELTS, HAMSTRINGS, LOWER_BACK), alias = arrayOf("Barbell Overhead Squat", "OHS")) // src: Overhead Squat | expert | compound
        ex("power_clean", "Power Clean", equipment = setOf(Equipment.BARBELL), primary = listOf(QUADS, GLUTES, HAMSTRINGS), secondary = listOf(TRAPS, LOWER_BACK, FRONT_DELTS, CORE), alias = arrayOf("Barbell Power Clean")) // src: Power Clean | intermediate | compound
        ex("push_jerk", "Push Jerk", equipment = setOf(Equipment.BARBELL), primary = listOf(FRONT_DELTS, SIDE_DELTS, QUADS), secondary = listOf(TRICEPS, GLUTES, CORE), alias = arrayOf("Power Jerk", "Barbell Push Jerk", "Barbell Power Jerk")) // src: Power Jerk | expert | compound
        ex("power_snatch", "Power Snatch", equipment = setOf(Equipment.BARBELL), primary = listOf(QUADS, GLUTES, HAMSTRINGS), secondary = listOf(TRAPS, LOWER_BACK, FRONT_DELTS, CORE), alias = arrayOf("Barbell Power Snatch")) // src: Power Snatch | expert | compound
        ex("snatch", "Snatch", equipment = setOf(Equipment.BARBELL), primary = listOf(QUADS, GLUTES, HAMSTRINGS), secondary = listOf(TRAPS, LOWER_BACK, FRONT_DELTS, CORE), alias = arrayOf("Squat Snatch", "Full Snatch", "Barbell Snatch")) // src: Snatch | intermediate | compound
        ex("snatch_grip_deadlift", "Snatch Grip Deadlift", equipment = setOf(Equipment.BARBELL), primary = listOf(HAMSTRINGS, GLUTES, LOWER_BACK), secondary = listOf(UPPER_BACK, TRAPS, QUADS), alias = arrayOf("Snatch Deadlift", "Wide Grip Deadlift")) // src: Snatch Deadlift | intermediate | compound
        ex("snatch_pull", "Snatch Pull", equipment = setOf(Equipment.BARBELL), primary = listOf(HAMSTRINGS, GLUTES, TRAPS), secondary = listOf(QUADS, LOWER_BACK, UPPER_BACK), alias = arrayOf("Barbell Snatch Pull", "Snatch High Pull")) // src: Snatch Pull | intermediate | compound
        ex("split_jerk", "Split Jerk", equipment = setOf(Equipment.BARBELL), primary = listOf(FRONT_DELTS, SIDE_DELTS, QUADS), secondary = listOf(TRICEPS, GLUTES, CORE), alias = arrayOf("Barbell Split Jerk", "Jerk")) // src: Split Jerk | intermediate | compound

        // Core
        ex("heel_touches", "Heel Touches", modality = Modality.BODYWEIGHT, equipment = setOf(Equipment.BODYWEIGHT), primary = listOf(CORE), alias = arrayOf("Alternate Heel Touchers", "Heel Touchers", "Heel Taps", "Heel Touch")) // src: Alternate Heel Touchers | beginner | isolation
        ex("cable_russian_twist", "Cable Russian Twist", equipment = setOf(Equipment.CABLE), primary = listOf(CORE), alias = arrayOf("Cable Russian Twists", "Cable Torso Rotation", "Cable Twist", "Cable Rotation")) // src: Cable Russian Twists | beginner | compound
        ex("dead_bug", "Dead Bug", modality = Modality.BODYWEIGHT, equipment = setOf(Equipment.BODYWEIGHT), primary = listOf(CORE), alias = arrayOf("Dead Bugs", "Deadbug")) // src: Dead Bug | beginner | compound
        ex("dumbbell_side_bend", "Dumbbell Side Bend", dumbbell = true, equipment = setOf(Equipment.DUMBBELL), primary = listOf(CORE), alias = arrayOf("Side Bend", "Side Bends", "Barbell Side Bend", "Side Bend (Dumbbell)", "One-Arm High-Pulley Cable Side Bends", "Cable Side Bend")) // src: Dumbbell Side Bend | beginner | isolation
        ex("seated_knee_tuck", "Seated Knee Tuck", modality = Modality.BODYWEIGHT, equipment = setOf(Equipment.BODYWEIGHT), primary = listOf(CORE), alias = arrayOf("Flat Bench Leg Pull-In", "Leg Pull-In", "Seated Flat Bench Leg Pull-In", "Seated Leg Tucks", "Knee Tucks", "Bench Knee Tuck", "Seated Knee Tucks")) // src: Flat Bench Leg Pull-In | beginner | compound
        ex("flutter_kicks", "Flutter Kicks", modality = Modality.BODYWEIGHT, equipment = setOf(Equipment.BODYWEIGHT), primary = listOf(CORE), secondary = listOf(GLUTES), alias = arrayOf("Flutter Kick", "Scissor Kicks")) // src: Flutter Kicks | beginner | compound
        ex("v_up", "V-Up", modality = Modality.BODYWEIGHT, equipment = setOf(Equipment.BODYWEIGHT), primary = listOf(CORE), alias = arrayOf("Jackknife Sit-Up", "V-Ups", "V Ups", "Jackknife", "Jackknife Sit Up")) // src: Jackknife Sit-Up | beginner | compound
        ex("landmine_rotation", "Landmine Rotation", equipment = setOf(Equipment.BARBELL), primary = listOf(CORE), secondary = listOf(GLUTES, LOWER_BACK, FRONT_DELTS), alias = arrayOf("Landmine 180's", "Landmine 180", "Landmine Twist", "Landmine Rotations", "Landmine 180s")) // src: Landmine 180's | beginner | compound
        ex("oblique_crunch", "Oblique Crunch", modality = Modality.BODYWEIGHT, equipment = setOf(Equipment.BODYWEIGHT), primary = listOf(CORE), alias = arrayOf("Oblique Crunches", "Oblique Crunches - On The Floor", "Decline Oblique Crunch", "Side Crunch", "Side Crunches")) // src: Oblique Crunches | beginner | isolation
        ex("reverse_crunch", "Reverse Crunch", modality = Modality.BODYWEIGHT, equipment = setOf(Equipment.BODYWEIGHT), primary = listOf(CORE), alias = arrayOf("Reverse Crunches", "Bent-Knee Hip Raise", "Decline Reverse Crunch", "Cable Reverse Crunch", "Lying Knee Raise")) // src: Reverse Crunch | beginner | isolation
        ex("cable_lift", "Cable Lift", equipment = setOf(Equipment.CABLE), primary = listOf(CORE), secondary = listOf(FRONT_DELTS), alias = arrayOf("Standing Cable Lift", "Low to High Cable Chop", "Cable Reverse Chop", "Cable Low to High Woodchop")) // src: Standing Cable Lift | beginner | compound
        ex("cable_wood_chop", "Cable Wood Chop", equipment = setOf(Equipment.CABLE), primary = listOf(CORE), secondary = listOf(FRONT_DELTS), alias = arrayOf("Standing Cable Wood Chop", "Cable Woodchop", "Woodchopper", "Cable Chop", "High to Low Cable Chop", "Wood Chop")) // src: Standing Cable Wood Chop | beginner | compound

        // Neck
        ex("isometric_neck_hold", "Isometric Neck Hold", modality = Modality.ISOMETRIC, equipment = setOf(Equipment.BODYWEIGHT), primary = listOf(NECK), alias = arrayOf("Isometric Neck Exercise - Front And Back", "Isometric Neck Exercise - Sides", "Neck Isometrics", "Isometric Neck", "Neck Hold")) // src: Isometric Neck Exercise - Front And Back | beginner | isolation

        // Kettlebell
        ex("renegade_row", "Renegade Row", dumbbell = true, equipment = setOf(Equipment.DUMBBELL, Equipment.KETTLEBELL), primary = listOf(UPPER_BACK, CORE), secondary = listOf(LATS, BICEPS), alias = arrayOf("Alternating Renegade Row", "Dumbbell Renegade Row", "Kettlebell Renegade Row", "Plank Row", "Renegade Rows")) // src: Alternating Renegade Row | expert | compound
        ex("double_kettlebell_front_squat", "Double Kettlebell Front Squat", dumbbell = true, equipment = setOf(Equipment.KETTLEBELL), primary = listOf(QUADS), secondary = listOf(GLUTES, CALVES), alias = arrayOf("Front Squats With Two Kettlebells", "Kettlebell Front Squat", "KB Front Squat", "Front Squat (Kettlebell)")) // src: Front Squats With Two Kettlebells | intermediate | compound
        ex("kettlebell_halo", "Kettlebell Halo", dumbbell = true, equipment = setOf(Equipment.KETTLEBELL), primary = listOf(FRONT_DELTS, SIDE_DELTS), secondary = listOf(TRAPS, CORE, TRICEPS), alias = arrayOf("Halo", "Kettlebell Halo with Overhead Extension")) // src: Kettlebell Halo | beginner | compound
        ex("seated_kettlebell_press", "Seated Kettlebell Press", dumbbell = true, equipment = setOf(Equipment.KETTLEBELL), primary = listOf(FRONT_DELTS, SIDE_DELTS), secondary = listOf(TRICEPS), alias = arrayOf("Kettlebell Seated Press", "Seated Kettlebell Shoulder Press")) // src: Kettlebell Seated Press | intermediate | compound
        ex("kettlebell_sumo_high_pull", "Kettlebell Sumo High Pull", dumbbell = true, equipment = setOf(Equipment.KETTLEBELL), primary = listOf(TRAPS, SIDE_DELTS), secondary = listOf(GLUTES, HAMSTRINGS, QUADS), alias = arrayOf("Sumo High Pull", "KB High Pull", "Kettlebell High Pull")) // src: Kettlebell Sumo High Pull | intermediate | compound
        ex("kettlebell_thruster", "Kettlebell Thruster", dumbbell = true, equipment = setOf(Equipment.KETTLEBELL), primary = listOf(QUADS, FRONT_DELTS, SIDE_DELTS), secondary = listOf(GLUTES, TRICEPS, CORE), alias = arrayOf("Double Kettlebell Thruster")) // src: Kettlebell Thruster | intermediate | compound
        ex("turkish_get_up", "Turkish Get-Up", dumbbell = true, equipment = setOf(Equipment.KETTLEBELL), primary = listOf(FRONT_DELTS, CORE), secondary = listOf(GLUTES, QUADS, TRICEPS, HAMSTRINGS), alias = arrayOf("Kettlebell Turkish Get-Up (Lunge style)", "Kettlebell Turkish Get-Up (Squat style)", "TGU", "Kettlebell Turkish Get-Up", "Get Up", "Turkish Getup")) // src: Kettlebell Turkish Get-Up (Lunge style) | intermediate | compound
        ex("kettlebell_windmill", "Kettlebell Windmill", dumbbell = true, equipment = setOf(Equipment.KETTLEBELL), primary = listOf(CORE), secondary = listOf(GLUTES, HAMSTRINGS, FRONT_DELTS, TRICEPS), alias = arrayOf("Windmill", "Advanced Kettlebell Windmill", "Double Kettlebell Windmill")) // src: Kettlebell Windmill | intermediate | compound
        ex("kettlebell_clean", "Kettlebell Clean", dumbbell = true, equipment = setOf(Equipment.KETTLEBELL), primary = listOf(GLUTES, HAMSTRINGS), secondary = listOf(TRAPS, FOREARMS, FRONT_DELTS, CORE), alias = arrayOf("One-Arm Kettlebell Clean", "Two-Arm Kettlebell Clean", "Kettlebell Dead Clean", "Kettlebell Hang Clean", "Alternating Hang Clean", "Double Kettlebell Alternating Hang Clean", "Double Kettlebell Clean", "Bottoms-Up Clean From The Hang Position")) // src: One-Arm Kettlebell Clean | intermediate | compound
        ex("kettlebell_clean_and_jerk", "Kettlebell Clean and Jerk", dumbbell = true, equipment = setOf(Equipment.KETTLEBELL), primary = listOf(GLUTES, HAMSTRINGS, FRONT_DELTS), secondary = listOf(QUADS, TRICEPS, CORE, TRAPS), alias = arrayOf("One-Arm Kettlebell Clean and Jerk", "Kettlebell Clean & Jerk")) // src: One-Arm Kettlebell Clean and Jerk | intermediate | compound
        ex("kettlebell_floor_press", "Kettlebell Floor Press", dumbbell = true, equipment = setOf(Equipment.KETTLEBELL), primary = listOf(CHEST, TRICEPS), secondary = listOf(FRONT_DELTS), alias = arrayOf("One-Arm Kettlebell Floor Press", "Alternating Floor Press", "Floor Press (Kettlebell)", "Extended Range One-Arm Kettlebell Floor Press")) // src: One-Arm Kettlebell Floor Press | intermediate | compound
        ex("kettlebell_jerk", "Kettlebell Jerk", dumbbell = true, equipment = setOf(Equipment.KETTLEBELL), primary = listOf(FRONT_DELTS, SIDE_DELTS), secondary = listOf(TRICEPS, CALVES, QUADS), alias = arrayOf("One-Arm Kettlebell Jerk", "Double Kettlebell Jerk", "Two-Arm Kettlebell Jerk", "One-Arm Kettlebell Split Jerk")) // src: One-Arm Kettlebell Jerk | intermediate | compound
        ex("kettlebell_push_press", "Kettlebell Push Press", dumbbell = true, equipment = setOf(Equipment.KETTLEBELL), primary = listOf(FRONT_DELTS, SIDE_DELTS), secondary = listOf(TRICEPS, CALVES, QUADS), alias = arrayOf("One-Arm Kettlebell Push Press", "Double Kettlebell Push Press")) // src: One-Arm Kettlebell Push Press | intermediate | compound
        ex("kettlebell_row", "Kettlebell Row", dumbbell = true, equipment = setOf(Equipment.KETTLEBELL), primary = listOf(UPPER_BACK), secondary = listOf(LATS, BICEPS), alias = arrayOf("One-Arm Kettlebell Row", "Two-Arm Kettlebell Row", "Alternating Kettlebell Row", "Kettlebell Bent Over Row", "Row (Kettlebell)")) // src: One-Arm Kettlebell Row | intermediate | compound
        ex("kettlebell_snatch", "Kettlebell Snatch", dumbbell = true, equipment = setOf(Equipment.KETTLEBELL), primary = listOf(GLUTES, HAMSTRINGS, FRONT_DELTS), secondary = listOf(TRAPS, CORE, FOREARMS), alias = arrayOf("One-Arm Kettlebell Snatch", "Double Kettlebell Snatch")) // src: One-Arm Kettlebell Snatch | expert | compound
        ex("kettlebell_swing", "Kettlebell Swing", dumbbell = true, equipment = setOf(Equipment.KETTLEBELL), primary = listOf(GLUTES, HAMSTRINGS), secondary = listOf(LOWER_BACK, CORE), alias = arrayOf("One-Arm Kettlebell Swings", "One Arm Kettlebell Swing", "Russian Kettlebell Swing", "Two Handed Kettlebell Swing", "Kettlebell Swings", "Swing")) // src: One-Arm Kettlebell Swings | intermediate | compound
        ex("kettlebell_press", "Kettlebell Press", dumbbell = true, equipment = setOf(Equipment.KETTLEBELL), primary = listOf(FRONT_DELTS, SIDE_DELTS), secondary = listOf(TRICEPS), alias = arrayOf("Two-Arm Kettlebell Military Press", "Kettlebell Military Press", "Kettlebell Overhead Press", "Alternating Kettlebell Press", "Double Kettlebell Press", "Kettlebell Shoulder Press", "One Arm Kettlebell Press", "Kettlebell Seesaw Press", "One-Arm Kettlebell Military Press To The Side")) // src: Two-Arm Kettlebell Military Press | intermediate | compound

        // Bands
        ex("band_reverse_fly", "Band Reverse Fly", equipment = setOf(Equipment.BANDS), primary = listOf(REAR_DELTS), secondary = listOf(UPPER_BACK, TRICEPS), alias = arrayOf("Back Flyes - With Bands", "Band Rear Delt Fly", "Banded Reverse Fly", "Band Back Fly")) // src: Back Flyes - With Bands | beginner | compound
        ex("band_good_morning", "Band Good Morning", equipment = setOf(Equipment.BANDS), primary = listOf(HAMSTRINGS), secondary = listOf(GLUTES, LOWER_BACK), alias = arrayOf("Band Good Morning (Pull Through)", "Banded Good Morning", "Band Pull Through")) // src: Band Good Morning | beginner | compound
        ex("band_hip_adduction", "Band Hip Adduction", equipment = setOf(Equipment.BANDS), primary = listOf(QUADS), alias = arrayOf("Band Hip Adductions", "Banded Adduction", "Band Adduction")) // src: Band Hip Adductions | beginner | isolation
        ex("band_pull_apart", "Band Pull Apart", equipment = setOf(Equipment.BANDS), primary = listOf(REAR_DELTS), secondary = listOf(UPPER_BACK, TRAPS), alias = arrayOf("Band Pull Aparts", "Pull Apart", "Pull Aparts")) // src: Band Pull Apart | beginner | isolation
        ex("band_skull_crusher", "Band Skull Crusher", equipment = setOf(Equipment.BANDS), primary = listOf(TRICEPS), alias = arrayOf("Band Triceps Extension", "Banded Skull Crusher")) // src: Band Skull Crusher | beginner | isolation
        ex("band_chest_press", "Band Chest Press", equipment = setOf(Equipment.BANDS), primary = listOf(CHEST), secondary = listOf(FRONT_DELTS, TRICEPS), alias = arrayOf("Bench Press - With Bands", "Banded Chest Press", "Band Push", "Band Bench Press")) // src: Bench Press - With Bands | beginner | compound
        ex("band_calf_raise", "Band Calf Raise", equipment = setOf(Equipment.BANDS), primary = listOf(CALVES), alias = arrayOf("Calf Raises - With Bands", "Banded Calf Raise")) // src: Calf Raises - With Bands | beginner | isolation
        ex("band_chest_fly", "Band Chest Fly", equipment = setOf(Equipment.BANDS), primary = listOf(CHEST), secondary = listOf(FRONT_DELTS), alias = arrayOf("Cross Over - With Bands", "Banded Chest Fly", "Band Crossover", "Band Fly")) // src: Cross Over - With Bands | beginner | compound
        ex("band_external_rotation", "Band External Rotation", equipment = setOf(Equipment.BANDS), primary = listOf(REAR_DELTS), alias = arrayOf("External Rotation with Band", "Banded External Rotation", "External Rotation (Band)")) // src: External Rotation with Band | beginner | compound // REVIEW rotator cuff
        ex("band_glute_kickback", "Band Glute Kickback", equipment = setOf(Equipment.BANDS), primary = listOf(GLUTES), secondary = listOf(HAMSTRINGS), alias = arrayOf("Hip Extension with Bands", "Banded Kickback", "Band Kickback", "Standing Band Hip Extension", "Band Hip Extension")) // src: Hip Extension with Bands | beginner | compound
        ex("band_hip_flexion", "Band Hip Flexion", equipment = setOf(Equipment.BANDS), primary = listOf(QUADS), alias = arrayOf("Hip Flexion with Band", "Banded Hip Flexion", "Band Knee Raise")) // src: Hip Flexion with Band | beginner | compound
        ex("band_glute_bridge", "Band Glute Bridge", equipment = setOf(Equipment.BANDS), primary = listOf(GLUTES), secondary = listOf(CALVES, HAMSTRINGS), alias = arrayOf("Hip Lift with Band", "Banded Glute Bridge", "Banded Hip Thrust", "Band Hip Thrust")) // src: Hip Lift with Band | beginner | compound
        ex("band_internal_rotation", "Band Internal Rotation", equipment = setOf(Equipment.BANDS), primary = listOf(FRONT_DELTS), secondary = listOf(CHEST), alias = arrayOf("Internal Rotation with Band", "Banded Internal Rotation", "Internal Rotation (Band)")) // src: Internal Rotation with Band | beginner | isolation // REVIEW rotator cuff
        ex("band_lateral_raise", "Band Lateral Raise", equipment = setOf(Equipment.BANDS), primary = listOf(SIDE_DELTS), alias = arrayOf("Lateral Raise - With Bands", "Banded Lateral Raise", "Resistance Band Lateral Raise")) // src: Lateral Raise - With Bands | beginner | isolation
        ex("monster_walk", "Monster Walk", equipment = setOf(Equipment.BANDS), primary = listOf(GLUTES), alias = arrayOf("Band Monster Walk", "Banded Monster Walk", "Band Walk", "Lateral Band Walk")) // src: Monster Walk | beginner | compound
        ex("band_leg_curl", "Band Leg Curl", equipment = setOf(Equipment.BANDS), primary = listOf(HAMSTRINGS), alias = arrayOf("Seated Band Hamstring Curl", "Band Hamstring Curl", "Banded Leg Curl", "Banded Hamstring Curl")) // src: Seated Band Hamstring Curl | beginner | isolation
        ex("band_shoulder_press", "Band Shoulder Press", equipment = setOf(Equipment.BANDS), primary = listOf(FRONT_DELTS, SIDE_DELTS), secondary = listOf(TRICEPS), alias = arrayOf("Shoulder Press - With Bands", "Banded Shoulder Press", "Band Overhead Press")) // src: Shoulder Press - With Bands | beginner | compound
        ex("band_overhead_triceps_extension", "Band Overhead Triceps Extension", equipment = setOf(Equipment.BANDS), primary = listOf(TRICEPS), alias = arrayOf("Speed Band Overhead Triceps", "Banded Overhead Triceps Extension", "Band Overhead Extension")) // src: Speed Band Overhead Triceps | beginner | isolation
        ex("band_squat", "Band Squat", equipment = setOf(Equipment.BANDS), primary = listOf(QUADS), secondary = listOf(GLUTES, CALVES, HAMSTRINGS, LOWER_BACK), alias = arrayOf("Squats - With Bands", "Banded Squat", "Resistance Band Squat")) // src: Squats - With Bands | beginner | compound
        ex("band_upright_row", "Band Upright Row", equipment = setOf(Equipment.BANDS), primary = listOf(SIDE_DELTS, TRAPS), secondary = listOf(BICEPS), alias = arrayOf("Upright Row - With Bands", "Banded Upright Row")) // src: Upright Row - With Bands | beginner | compound

        // Bodyweight
        ex("assisted_pull_up", "Assisted Pull Up", modality = Modality.BODYWEIGHT, equipment = setOf(Equipment.MACHINE, Equipment.BANDS), primary = listOf(LATS), secondary = listOf(UPPER_BACK, BICEPS, CORE, FOREARMS), alias = arrayOf("Band Assisted Pull-Up", "Machine Assisted Pull Up", "Assisted Pull Up (Machine)", "Assisted Pullup", "Assisted Chin Up")) // src: Band Assisted Pull-Up | beginner | compound
        ex("bench_dip", "Bench Dip", modality = Modality.BODYWEIGHT, equipment = setOf(Equipment.BODYWEIGHT), primary = listOf(TRICEPS), secondary = listOf(CHEST, FRONT_DELTS), alias = arrayOf("Bench Dips", "Weighted Bench Dip", "Tricep Bench Dip", "Bench Dips (Triceps)", "Tricep Dips (Bench)")) // src: Bench Dips | beginner | compound
        ex("bodyweight_lunge", "Bodyweight Lunge", modality = Modality.BODYWEIGHT, equipment = setOf(Equipment.BODYWEIGHT), primary = listOf(QUADS), secondary = listOf(GLUTES, CALVES, HAMSTRINGS), alias = arrayOf("Bodyweight Walking Lunge", "Bodyweight Lunges", "Lunge (Bodyweight)", "Walking Lunge (Bodyweight)")) // src: Bodyweight Walking Lunge | beginner | compound
        ex("bodyweight_glute_bridge", "Bodyweight Glute Bridge", modality = Modality.BODYWEIGHT, equipment = setOf(Equipment.BODYWEIGHT), primary = listOf(GLUTES), secondary = listOf(HAMSTRINGS), alias = arrayOf("Butt Lift (Bridge)", "Floor Bridge", "Hip Bridge", "Bodyweight Hip Thrust", "Bridge")) // src: Butt Lift (Bridge) | beginner | isolation
        ex("decline_push_up", "Decline Push Up", modality = Modality.BODYWEIGHT, equipment = setOf(Equipment.BODYWEIGHT), primary = listOf(CHEST), secondary = listOf(FRONT_DELTS, TRICEPS), alias = arrayOf("Push-Ups With Feet Elevated", "Feet Elevated Push Up", "Decline Pushup", "Decline Push Ups")) // src: Decline Push-Up | beginner | compound
        ex("jump_squat", "Jump Squat", modality = Modality.BODYWEIGHT, equipment = setOf(Equipment.BODYWEIGHT), primary = listOf(QUADS), secondary = listOf(GLUTES, CALVES, HAMSTRINGS), alias = arrayOf("Freehand Jump Squat", "Jump Squats", "Squat Jump", "Squat Jumps", "Weighted Jump Squat")) // src: Freehand Jump Squat | intermediate | compound
        ex("handstand_push_up", "Handstand Push Up", modality = Modality.BODYWEIGHT, equipment = setOf(Equipment.BODYWEIGHT), primary = listOf(FRONT_DELTS, SIDE_DELTS), secondary = listOf(TRICEPS), alias = arrayOf("Handstand Push-Ups", "HSPU", "Handstand Pushup", "Wall Handstand Push Up")) // src: Handstand Push-Ups | expert | compound
        ex("incline_push_up", "Incline Push Up", modality = Modality.BODYWEIGHT, equipment = setOf(Equipment.BODYWEIGHT), primary = listOf(CHEST), secondary = listOf(FRONT_DELTS, TRICEPS), alias = arrayOf("Incline Push-Up Medium", "Incline Push-Up Wide", "Incline Pushup", "Elevated Push Up", "Incline Push Ups", "Incline Push-Up Reverse Grip")) // src: Incline Push-Up | beginner | compound
        ex("mountain_climbers", "Mountain Climbers", modality = Modality.BODYWEIGHT, equipment = setOf(Equipment.BODYWEIGHT), primary = listOf(CORE), secondary = listOf(QUADS, FRONT_DELTS, CHEST), alias = arrayOf("Mountain Climber")) // src: Mountain Climbers | beginner | compound
        ex("muscle_up", "Muscle Up", modality = Modality.BODYWEIGHT, equipment = setOf(Equipment.BODYWEIGHT), primary = listOf(LATS, CHEST, TRICEPS), secondary = listOf(BICEPS, UPPER_BACK, CORE), alias = arrayOf("Muscle Ups", "Bar Muscle Up", "Ring Muscle Up", "Kipping Muscle Up")) // src: Muscle Up | intermediate | compound
        ex("wide_push_up", "Wide Push Up", modality = Modality.BODYWEIGHT, equipment = setOf(Equipment.BODYWEIGHT), primary = listOf(CHEST), secondary = listOf(CORE, FRONT_DELTS, TRICEPS), alias = arrayOf("Push-Up Wide", "Wide Grip Push Up", "Wide Pushup", "Wide Push Ups")) // src: Push-Up Wide | beginner | compound
        ex("close_grip_push_up", "Close Grip Push Up", modality = Modality.BODYWEIGHT, equipment = setOf(Equipment.BODYWEIGHT), primary = listOf(TRICEPS), secondary = listOf(CHEST, FRONT_DELTS), alias = arrayOf("Push-Ups - Close Triceps Position", "Diamond Push Up", "Diamond Push-Up", "Triceps Push Up", "Close Push Up", "Close-Grip Push-Up off of a Dumbbell", "Incline Push-Up Close-Grip")) // src: Push-Ups - Close Triceps Position | intermediate | compound
        ex("ring_dip", "Ring Dip", modality = Modality.BODYWEIGHT, equipment = setOf(Equipment.BODYWEIGHT), primary = listOf(CHEST, TRICEPS), secondary = listOf(FRONT_DELTS), alias = arrayOf("Ring Dips", "Ring Dips (Bodyweight)", "Gymnastic Ring Dip")) // src: Ring Dips | intermediate | compound
        ex("rope_climb", "Rope Climb", modality = Modality.BODYWEIGHT, equipment = setOf(Equipment.OTHER), primary = listOf(LATS), secondary = listOf(BICEPS, FOREARMS, UPPER_BACK, FRONT_DELTS), alias = arrayOf("Rope Climbs")) // src: Rope Climb | intermediate | compound
        ex("scapular_pull_up", "Scapular Pull Up", modality = Modality.BODYWEIGHT, equipment = setOf(Equipment.BODYWEIGHT), primary = listOf(TRAPS), secondary = listOf(LATS, UPPER_BACK), alias = arrayOf("Scap Pull Up", "Scap Pull-Ups", "Scapular Pull Ups", "Scap Pull-Up")) // src: Scapular Pull-Up | beginner | isolation
        ex("single_leg_glute_bridge", "Single Leg Glute Bridge", modality = Modality.BODYWEIGHT, equipment = setOf(Equipment.BODYWEIGHT), primary = listOf(GLUTES), secondary = listOf(HAMSTRINGS), alias = arrayOf("One Leg Glute Bridge", "Single Leg Hip Thrust")) // src: Single Leg Glute Bridge | beginner | isolation
        ex("step_up_with_knee_raise", "Step Up with Knee Raise", modality = Modality.BODYWEIGHT, equipment = setOf(Equipment.BODYWEIGHT), primary = listOf(QUADS, GLUTES), secondary = listOf(CORE), alias = arrayOf("Step-Up with Knee Drive", "Step Up Knee Raise")) // src: Step-up with Knee Raise | beginner | compound
        ex("suspension_push_up", "Suspension Push Up", modality = Modality.BODYWEIGHT, equipment = setOf(Equipment.OTHER), primary = listOf(CHEST), secondary = listOf(FRONT_DELTS, TRICEPS), alias = arrayOf("Suspended Push-Up", "TRX Push Up", "Ring Push Up", "Ring Push-Up", "Suspension Trainer Push Up")) // src: Suspended Push-Up | beginner | compound
        ex("suspension_row", "Suspension Row", modality = Modality.BODYWEIGHT, equipment = setOf(Equipment.OTHER), primary = listOf(UPPER_BACK), secondary = listOf(LATS, BICEPS), alias = arrayOf("Suspended Row", "TRX Row", "Inverted Row with Straps", "Suspension Trainer Row")) // src: Suspended Row | beginner | compound
        ex("neutral_grip_pull_up", "Neutral Grip Pull Up", modality = Modality.BODYWEIGHT, equipment = setOf(Equipment.BODYWEIGHT), primary = listOf(LATS), secondary = listOf(UPPER_BACK, BICEPS, REAR_DELTS), alias = arrayOf("V-Bar Pullup", "V-Bar Pull Up", "Hammer Grip Pull Up", "Parallel Grip Pull Up", "Neutral Grip Pullup")) // src: V-Bar Pullup | beginner | compound
        ex("burpee", "Burpee", modality = Modality.BODYWEIGHT, equipment = setOf(Equipment.BODYWEIGHT), primary = listOf(QUADS, CHEST), secondary = listOf(CORE, TRICEPS, FRONT_DELTS, GLUTES), alias = arrayOf("Burpees")) // src: manual | beginner | compound
        ex("bird_dog", "Bird Dog", modality = Modality.BODYWEIGHT, equipment = setOf(Equipment.BODYWEIGHT), primary = listOf(CORE), secondary = listOf(LOWER_BACK, GLUTES), alias = arrayOf("Bird Dogs", "Quadruped Bird Dog")) // src: manual | beginner | isolation

        // Cardio
        ex("battle_ropes", "Battle Ropes", modality = Modality.CARDIO, equipment = setOf(Equipment.OTHER), primary = listOf(FRONT_DELTS, SIDE_DELTS), secondary = listOf(CORE, FOREARMS), alias = arrayOf("Battling Ropes", "Battle Rope", "Battle Rope Slams", "Battling Rope")) // src: Battling Ropes | beginner | compound
        ex("recumbent_bike", "Recumbent Bike", modality = Modality.CARDIO, equipment = setOf(Equipment.MACHINE), primary = emptyList(), alias = arrayOf("Recumbent Cycling", "Recumbent Bicycle")) // src: Recumbent Bike | beginner | -
        ex("skating", "Skating", modality = Modality.CARDIO, equipment = setOf(Equipment.OTHER), primary = emptyList(), alias = arrayOf("Ice Skating", "Inline Skating", "Rollerblading")) // src: Skating | intermediate | -
        ex("trail_running", "Trail Running", modality = Modality.CARDIO, equipment = setOf(Equipment.OTHER), primary = emptyList(), alias = arrayOf("Trail Running/Walking", "Trail Run", "Hiking", "Hike")) // src: Trail Running/Walking | beginner | -

        // Export names seen for exercises above (free-exercise-db spellings).
        alias("ab_wheel", "Ab Roller", "Barbell Ab Rollout", "Barbell Ab Rollout - On Knees", "Barbell Rollout from Bench")
        alias("arnold_press", "Arnold Dumbbell Press", "Kettlebell Arnold Press")
        alias("back_extension", "Hyperextensions (Back Extensions)")
        alias("back_extension_iso", "Hyperextensions With No Hyperextension Bench")
        alias("barbell_curl", "Close-Grip EZ Bar Curl", "Close-Grip Standing Barbell Curl", "Wide-Grip Standing Barbell Curl")
        alias("bench_press", "Barbell Bench Press - Medium Grip", "Bench Press - Powerlifting")
        alias("bodyweight_squat", "Bodyweight Squat")
        alias("bulgarian_split_squat", "One Leg Barbell Squat", "Smith Single-Leg Split Squat", "Split Squat with Dumbbells", "Suspended Split Squat")
        alias("cable_crunch", "Cable Seated Crunch", "Kneeling Cable Crunch With Alternating Oblique Twists", "Standing Rope Crunch")
        alias("cable_curl", "Lying Cable Curl", "Standing Biceps Cable Curl", "Standing One-Arm Cable Curl")
        alias("cable_fly", "Flat Bench Cable Flyes", "Low Cable Crossover", "Single-Arm Cable Crossover")
        alias("cable_kickback", "One-Legged Cable Kickback")
        alias("cable_lateral_raise", "Cable Seated Lateral Raise", "Standing Low-Pulley Deltoid Raise")
        alias("calf_raise", "Calf Press On The Leg Press Machine", "Rocking Standing Calf Raise", "Standing Barbell Calf Raise", "Standing Dumbbell Calf Raise")
        alias("chest_fly", "Butterfly", "Dumbbell Flyes", "One-Arm Flat Bench Dumbbell Flye")
        alias("chest_press_machine", "Leverage Chest Press", "Machine Bench Press")
        alias("chin_up", "One Arm Chin-Up")
        alias("close_grip_bench_press", "Close-Grip Barbell Bench Press", "Close-Grip EZ-Bar Press")
        alias("concentration_curl", "Concentration Curls", "Seated Close-Grip Concentration Barbell Curl", "Standing Concentration Curl")
        alias("crunch", "3/4 Sit-Up", "Cross-Body Crunch", "Crunch - Hands Overhead", "Elbow to Knee")
        alias("cycling", "Bicycling", "Bicycling, Stationary")
        alias("db_bench_press", "One Arm Dumbbell Bench Press")
        alias("db_curl", "Dumbbell Alternate Bicep Curl", "Seated Dumbbell Inner Biceps Curl", "Standing Inner-Biceps Curl")
        alias("db_pullover", "Bent-Arm Dumbbell Pullover", "Straight-Arm Dumbbell Pullover")
        alias("db_shoulder_press", "Dumbbell One-Arm Shoulder Press", "Dumbbell Shoulder Press", "See-Saw Press (Alternating Side Press)", "Standing Alternating Dumbbell Press", "Standing Dumbbell Press")
        alias("deadlift", "Clean Deadlift")
        alias("decline_bench_press", "Wide-Grip Decline Barbell Bench Press")
        alias("dip", "Dips - Chest Version", "Dips - Triceps Version")
        alias("farmers_walk", "Farmer's Walk")
        alias("front_raise", "Front Cable Raise", "Front Dumbbell Raise", "Front Incline Dumbbell Raise", "Front Plate Raise", "Front Two-Dumbbell Raise", "Single Dumbbell Raise", "Standing Dumbbell Straight-Arm Front Delt Raise Above Head")
        alias("front_squat", "Front Barbell Squat", "Front Barbell Squat To A Bench", "Front Squat (Clean Grip)")
        alias("good_morning", "Seated Good Mornings", "Stiff Leg Barbell Good Morning")
        alias("hack_squat", "Narrow Stance Hack Squats")
        alias("hammer_curl", "Alternate Hammer Curl", "Cable Hammer Curls - Rope Attachment", "Hammer Curls")
        alias("hip_abduction", "Leg Lift", "Thigh Abductor")
        alias("hip_adduction", "Thigh Adductor")
        alias("incline_bench_press", "Barbell Incline Bench Press - Medium Grip")
        alias("incline_curl", "Alternate Incline Dumbbell Curl", "Flexor Incline Dumbbell Curls", "Incline Inner Biceps Curl")
        alias("incline_db_fly", "Incline Dumbbell Flyes", "Incline Dumbbell Flyes - With A Twist")
        alias("inverted_row", "Bodyweight Mid Row")
        alias("jump_rope", "Rope Jumping")
        alias("lat_pulldown", "Close-Grip Front Lat Pulldown", "Full Range-Of-Motion Lat Pulldown", "One Arm Lat Pulldown")
        alias("lateral_raise", "Lying One-Arm Lateral Raise", "One-Arm Incline Lateral Raise", "One-Arm Side Laterals", "Seated Side Lateral Raise")
        alias("leg_curl", "Lying Leg Curls")
        alias("leg_extension", "Single-Leg Leg Extension")
        alias("leg_press", "Narrow Stance Leg Press", "Smith Machine Leg Press")
        alias("leg_raise", "Flat Bench Lying Leg Raise", "Hanging Pike", "Knee/Hip Raise On Parallel Bars")
        alias("lunge", "Barbell Walking Lunge", "Dumbbell Lunges", "Dumbbell Rear Lunge")
        alias("neck_curl", "Lying Face Up Plate Neck Resistance")
        alias("neck_extension", "Lying Face Down Plate Neck Resistance", "Seated Head Harness Neck Resistance")
        alias("nordic_curl", "Floor Glute-Ham Raise", "Natural Glute Ham Raise")
        alias("overhead_press", "Standing Military Press")
        alias("overhead_triceps_extension", "Cable Incline Triceps Extension", "Cable Rope Overhead Triceps Extension", "Dumbbell One-Arm Triceps Extension", "Kettlebell Overhead Triceps Extension", "Kneeling Cable Triceps Extension", "Low Cable Triceps Extension", "One Arm Pronated Dumbbell Triceps Extension", "One Arm Supinated Dumbbell Triceps Extension", "Seated Triceps Press", "Standing Dumbbell Triceps Extension", "Standing One-Arm Dumbbell Triceps Extension", "Triceps Overhead Extension with Rope")
        alias("pallof_press", "Pallof Press With Rotation")
        alias("pistol_squat", "Kettlebell Pistol Squat", "Smith Machine Pistol Squat")
        alias("preacher_curl", "Cable Preacher Curl", "Machine Preacher Curls", "One Arm Dumbbell Preacher Curl", "Preacher Hammer Dumbbell Curl", "Two-Arm Dumbbell Preacher Curl")
        alias("pull_up", "Mixed Grip Chin", "Side To Side Chins", "Weighted Pull Ups", "Wide-Grip Rear Pull-Up")
        alias("push_up", "Pushups (Close and Wide Hand Positions)", "Single-Arm Push-Up")
        alias("rear_delt_fly", "Dumbbell Lying One-Arm Rear Lateral Raise", "Dumbbell Lying Rear Lateral Raise", "Lying Rear Delt Raise", "Reverse Flyes", "Reverse Flyes With External Rotation", "Reverse Machine Flyes")
        alias("reverse_curl", "Reverse Barbell Preacher Curls", "Reverse Plate Curls", "Standing Dumbbell Reverse Curl")
        alias("romanian_deadlift", "Romanian Deadlift from Deficit", "Stiff-Legged Barbell Deadlift", "Stiff-Legged Dumbbell Deadlift")
        alias("row_machine", "Leverage High Row", "Leverage Iso Row")
        alias("rowing", "Rowing, Stationary")
        alias("running", "Jogging, Treadmill", "Running, Treadmill")
        alias("russian_twist", "Plate Twist")
        alias("seated_cable_row", "Elevated Cable Rows", "Seated Cable Rows")
        alias("seated_calf_raise", "Barbell Seated Calf Raise", "Dumbbell Seated One-Leg Calf Raise")
        alias("shoulder_press_machine", "Leverage Shoulder Press", "Machine Shoulder (Military) Press")
        alias("shrug", "Clean Shrug", "Snatch Shrug")
        alias("side_plank", "Side Bridge")
        alias("single_leg_rdl", "Kettlebell One-Legged Deadlift")
        alias("skull_crusher", "Cable Lying Triceps Extension", "Decline EZ Bar Triceps Extension", "Dumbbell Tricep Extension -Pronated Grip", "EZ-Bar Skullcrusher", "Incline Barbell Triceps Extension", "Lying Close-Grip Barbell Triceps Extension Behind The Head", "Lying Close-Grip Barbell Triceps Press To Chin", "Lying Triceps Press")
        alias("squat", "Barbell Full Squat", "Narrow Stance Squats", "Olympic Squat", "Wide Stance Barbell Squat")
        alias("stair_climber", "Step Mill")
        alias("step_up", "Barbell Step Ups", "Dumbbell Step Ups")
        alias("straight_arm_pulldown", "Cable Incline Pushdown", "Rope Straight-Arm Pulldown")
        alias("t_bar_row", "Bent Over Two-Arm Long Bar Row", "Lying T-Bar Row", "T-Bar Row with Handle")
        alias("triceps_kickback", "Seated Bent-Over One-Arm Dumbbell Triceps Extension", "Seated Bent-Over Two-Arm Dumbbell Triceps Extension", "Standing Bent-Over One-Arm Dumbbell Triceps Extension", "Standing Bent-Over Two-Arm Dumbbell Triceps Extension", "Tricep Dumbbell Kickback")
        alias("triceps_pushdown", "Cable One Arm Tricep Extension", "Standing Low-Pulley One-Arm Triceps Extension", "Triceps Pushdown - Rope Attachment", "Triceps Pushdown - V-Bar Attachment")
        alias("upright_row", "Dumbbell One-Arm Upright Row", "Smith Machine One-Arm Upright Row", "Smith Machine Upright Row", "Upright Barbell Row", "Upright Cable Row")
        alias("walking", "Walking, Treadmill")
        alias("wrist_curl", "Cable Wrist Curl", "Palms-Down Dumbbell Wrist Curl Over A Bench", "Palms-Down Wrist Curl Over A Bench", "Palms-Up Barbell Wrist Curl Over A Bench", "Palms-Up Dumbbell Wrist Curl Over A Bench", "Seated Dumbbell Palms-Down Wrist Curl", "Seated Dumbbell Palms-Up Wrist Curl", "Seated One-Arm Dumbbell Palms-Down Wrist Curl", "Seated One-Arm Dumbbell Palms-Up Wrist Curl", "Seated Palm-Up Barbell Wrist Curl", "Seated Palms-Down Barbell Wrist Curl", "Seated Two-Arm Palms-Up Low-Pulley Wrist Curl")
    }

    /** Equipment inferred from the name for catalogue entries that do not state it. */
    internal fun guessEquipment(name: String, modality: Modality, dumbbell: Boolean): Set<Equipment> {
        val n = name.lowercase()
        return when {
            modality == Modality.CARDIO -> if (Regex("treadmill|bike|cycling|rowing|elliptical|stair").containsMatchIn(n)) setOf(Equipment.MACHINE) else setOf(Equipment.OTHER)
            "kettlebell" in n -> setOf(Equipment.KETTLEBELL)
            dumbbell || "dumbbell" in n -> setOf(Equipment.DUMBBELL)
            "cable" in n || "pulldown" in n || "pushdown" in n || "face pull" in n || "pallof" in n -> setOf(Equipment.CABLE)
            "machine" in n || "smith" in n || "leg press" in n || "hack squat" in n || "leg extension" in n || "leg curl" in n ||
                "hip abduction" in n || "hip adduction" in n || "calf raise" in n || "chest press" in n && "dumbbell" !in n -> setOf(Equipment.MACHINE)
            "band" in n -> setOf(Equipment.BANDS)
            modality == Modality.BODYWEIGHT || modality == Modality.ISOMETRIC -> setOf(Equipment.BODYWEIGHT)
            Regex("barbell|deadlift|squat|bench|row|press|curl|shrug|good morning|skull|hip thrust|upright|extension|wrist").containsMatchIn(n) -> setOf(Equipment.BARBELL)
            else -> setOf(Equipment.OTHER)
        }
    }

    val builtIn: List<Exercise> = builder.exercises
    val builtInAliases: Map<String, String> = builder.aliases

    fun byId(id: String): Exercise? = builtIn.firstOrNull { it.id == id }

    /** Creates an explicitly unmapped custom exercise. Unknown names are never keyword-classified. */
    fun guess(rawName: String, modalityHint: Modality?): Exercise {
        val n = NameNormalizer.normalize(rawName)
        val training = when {
            "prep" in n -> TrainingModality.WARMUP
            MuscleGuesser.looksLikeCardio(n) -> TrainingModality.CARDIO
            else -> TrainingModality.STRENGTH
        }
        val load = if (training == TrainingModality.CARDIO) Modality.CARDIO else (modalityHint ?: Modality.WEIGHTED)
        return Exercise(NameNormalizer.toId(rawName), rawName.trim(), rawName.trim(), emptyList(), load,
            isDumbbell = "dumbbell" in n || n.startsWith("db ") || " db " in n,
            isBuiltIn = false, trainingModality = training)
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
