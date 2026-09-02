package app.gains.csv

object Fixtures {
    const val HEADER = "Date,Duration,Workout Name,Exercise Name,Set Order,Weight,Reps,Distance,Seconds,RPE,Notes"

    /** The sample rows from the spec, plus warm-up sets for bench. */
    val SAMPLE = """
        $HEADER
        2026-02-18 20:40:47,01 hours 00 minutes 00 seconds,,Bench Press,2,132.277357311,8,0,0,,
        2026-02-18 20:40:47,01 hours 00 minutes 00 seconds,,Bench Press,0,44.092452437,20,0,0,,
        2026-02-18 20:40:47,01 hours 00 minutes 00 seconds,,Bench Press,1,110.231131093,12,0,0,,
        2026-02-18 20:40:47,01 hours 00 minutes 00 seconds,,Dumbbell Lateral Raise,0,13.2277357311,15,0,0,,"I used one dumbbell and didn't do any rest (almost) between sets "
        2026-02-18 20:40:47,01 hours 00 minutes 00 seconds,,Dumbbell Lateral Raise,1,13.2277357311,15,0,0,,"I used one dumbbell and didn't do any rest (almost) between sets "
        2026-01-13 22:04:26,01 hours 41 minutes 04 seconds,,Sled Leg Press,0,116.84499895805,15,0,0,,+53kg
        2026-03-21 13:29:26,01 hours 01 minutes 45 seconds,,Dead Hang,0,0,0,0,60,,
        2026-04-27 17:22:29,00 hours 33 minutes 08 seconds,,Running,0,0,0,6.437376,1980,,
        2026-03-21 13:29:26,01 hours 01 minutes 45 seconds,,Pull Up,0,0,8,0,0,,
    """.trimIndent()

    /** Rows from three years interleaved in no particular order. */
    val OUT_OF_ORDER = """
        $HEADER
        2025-06-01 10:00:00,00 hours 45 minutes 00 seconds,,Squat,0,220.462262185,5,0,0,,
        2023-01-05 09:00:00,00 hours 45 minutes 00 seconds,,Squat,0,176.369809748,5,0,0,,
        2026-02-10 18:30:00,00 hours 45 minutes 00 seconds,,Squat,0,264.554714622,5,0,0,,
        2024-11-20 07:15:00,00 hours 45 minutes 00 seconds,,Squat,0,198.416036 ,5,0,0,,
        2023-01-05 09:00:00,00 hours 45 minutes 00 seconds,,Squat,1,176.369809748,5,0,0,,
    """.trimIndent()

    /** Same workout logged twice on one day at different timestamps with identical sets. */
    val DUPLICATES = """
        $HEADER
        2026-05-02 10:00:00,01 hours 00 minutes 00 seconds,,Bench Press,0,132.277357311,8,0,0,,
        2026-05-02 10:00:00,01 hours 00 minutes 00 seconds,,Bench Press,1,132.277357311,8,0,0,,
        2026-05-02 10:00:00,01 hours 00 minutes 00 seconds,,Lat Pulldown,0,110.231131093,10,0,0,,
        2026-05-02 11:37:12,01 hours 00 minutes 00 seconds,,Bench Press,0,132.277357311,8,0,0,,
        2026-05-02 11:37:12,01 hours 00 minutes 00 seconds,,Bench Press,1,132.277357311,8,0,0,,
        2026-05-02 11:37:12,01 hours 00 minutes 00 seconds,,Lat Pulldown,0,110.231131093,10,0,0,,
        2026-05-03 10:00:00,01 hours 00 minutes 00 seconds,,Bench Press,0,132.277357311,8,0,0,,
        2026-05-03 10:00:00,01 hours 00 minutes 00 seconds,,Bench Press,1,132.277357311,8,0,0,,
        2026-05-03 10:00:00,01 hours 00 minutes 00 seconds,,Lat Pulldown,0,110.231131093,10,0,0,,
    """.trimIndent()

    val CORRUPT_DURATIONS = """
        $HEADER
        2026-01-01 10:00:00,325 hours 03 minutes 37 seconds,,Bench Press,0,132.277357311,8,0,0,,
        2026-01-02 10:00:00,109 hours 36 minutes,,Bench Press,0,132.277357311,8,0,0,,
        2026-01-03 10:00:00,95 hours 38 minutes,,Bench Press,0,132.277357311,8,0,0,,
        2026-01-04 10:00:00,01 hours 41 minutes 04 seconds,,Bench Press,0,132.277357311,8,0,0,,
        2026-01-05 10:00:00,,,Bench Press,0,132.277357311,8,0,0,,
        2026-01-06 10:00:00,03 hours 59 minutes 59 seconds,,Bench Press,0,132.277357311,8,0,0,,
    """.trimIndent()

    val ISOMETRIC_OUTLIERS = """
        $HEADER
        2026-01-01 10:00:00,01 hours 00 minutes 00 seconds,,Hollow hold,0,0,0,0,55,,
        2026-01-01 10:00:00,01 hours 00 minutes 00 seconds,,Hollow hold,1,0,0,0,50,,
        2026-01-08 10:00:00,01 hours 00 minutes 00 seconds,,Hollow hold,0,0,0,0,1800,,
        2026-01-08 10:00:00,01 hours 00 minutes 00 seconds,,Hollow hold,1,0,0,0,1800,,
        2026-01-15 10:00:00,01 hours 00 minutes 00 seconds,,Hollow hold,0,0,0,0,60,,
        2026-01-22 10:00:00,01 hours 00 minutes 00 seconds,,Hollow hold,0,0,0,0,1800,,
        2026-01-22 10:00:00,01 hours 00 minutes 00 seconds,,Plank,0,0,0,0,90,,
    """.trimIndent()

    val QUOTED_NOTES = """
        $HEADER
        2026-02-18 20:40:47,01 hours 00 minutes 00 seconds,,Dumbbell Lateral Raise,0,13.2277357311,15,0,0,,"I used one dumbbell, no rest, ""almost"" none"
        2026-02-18 20:40:47,01 hours 00 minutes 00 seconds,,Dumbbell Lateral Raise,1,13.2277357311,15,0,0,,"I used one dumbbell, no rest, ""almost"" none"
        2026-02-18 20:40:47,01 hours 00 minutes 00 seconds,,Dumbbell Lateral Raise,2,13.2277357311,12,0,0,,"I used one dumbbell, no rest, ""almost"" none"
        2026-02-18 20:40:47,01 hours 00 minutes 00 seconds,,Bench Press,0,132.277357311,8,0,0,,"Line one
        line two, with comma"
    """.trimIndent()

    val EMPTY_ROWS = """
        $HEADER
        2026-02-18 20:40:47,01 hours 00 minutes 00 seconds,,,0,0,0,0,0,,
        2026-02-18 20:40:47,01 hours 00 minutes 00 seconds,,Bench Press,0,0,0,0,0,,
        2026-02-18 20:40:47,01 hours 00 minutes 00 seconds,,Bench Press,1,132.277357311,8,0,0,,
        ,,,,,,,,,,
        2026-02-18 20:40:47,01 hours 00 minutes 00 seconds,,,0,132.277357311,8,0,0,,

    """.trimIndent()

    val SHUFFLED_SET_ORDER = """
        $HEADER
        2026-02-18 20:40:47,01 hours 00 minutes 00 seconds,,Bench Press,3,121.254244339,8,0,0,,
        2026-02-18 20:40:47,01 hours 00 minutes 00 seconds,,Bench Press,0,44.092452437,20,0,0,,
        2026-02-18 20:40:47,01 hours 00 minutes 00 seconds,,Bench Press,2,132.277357311,10,0,0,,
        2026-02-18 20:40:47,01 hours 00 minutes 00 seconds,,Bench Press,1,88.184904874,15,0,0,,
    """.trimIndent()

    val CRLF = HEADER + "\r\n" +
        "2026-02-18 20:40:47,01 hours 00 minutes 00 seconds,,Bench Press,0,132.277357311,8,0,0,7.5,\"note, with comma\"\r\n" +
        "2026-02-18 20:40:47,01 hours 00 minutes 00 seconds,,Bench Press,1,132.277357311,8,0,0,,\r\n"
}
