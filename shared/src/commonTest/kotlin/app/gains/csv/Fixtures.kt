package app.gains.csv

object Fixtures {
    const val HEADER = "Date,Duration,Workout Name,Exercise Name,Set Order,Weight,Reps,Distance,Seconds,RPE,Notes"

    /**
     * One row of each kind a Liftoff export holds, with warm-up sets for bench out of order.
     * Made up: the real export these once came from is personal data and stays out of the tree.
     */
    val SAMPLE = """
        $HEADER
        2026-02-24 18:15:00,00 hours 55 minutes 00 seconds,,Bench Press,2,154.3235835295,6,0,0,,
        2026-02-24 18:15:00,00 hours 55 minutes 00 seconds,,Bench Press,0,44.092452437,15,0,0,,
        2026-02-24 18:15:00,00 hours 55 minutes 00 seconds,,Bench Press,1,126.76580075638,10,0,0,,
        2026-02-24 18:15:00,00 hours 55 minutes 00 seconds,,Dumbbell Lateral Raise,0,17.6369809748,12,0,0,,"Slow tempo, couldn’t keep the pause "
        2026-02-24 18:15:00,00 hours 55 minutes 00 seconds,,Dumbbell Lateral Raise,1,17.6369809748,12,0,0,,"Slow tempo, couldn’t keep the pause "
        2025-10-02 07:30:00,01 hours 12 minutes 30 seconds,,Sled Leg Press,0,88.184904874,12,0,0,,+40kg
        2025-12-14 09:05:00,00 hours 48 minutes 20 seconds,,Dead Hang,0,0,0,0,45,,
        2026-05-09 06:40:00,00 hours 27 minutes 10 seconds,,Running,0,0,0,5.2,1560,,
        2025-12-14 09:05:00,00 hours 48 minutes 20 seconds,,Pull Up,0,0,6,0,0,,
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
        2026-01-01 10:00:00,212 hours 14 minutes 09 seconds,,Bench Press,0,132.277357311,8,0,0,,
        2026-01-02 10:00:00,130 hours 05 minutes,,Bench Press,0,132.277357311,8,0,0,,
        2026-01-03 10:00:00,88 hours 20 minutes,,Bench Press,0,132.277357311,8,0,0,,
        2026-01-04 10:00:00,01 hours 37 minutes 12 seconds,,Bench Press,0,132.277357311,8,0,0,,
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
        2026-02-20 19:00:00,01 hours 00 minutes 00 seconds,,Dumbbell Lateral Raise,0,13.2277357311,15,0,0,,"Slow negatives, ""paused"" at the top"
        2026-02-20 19:00:00,01 hours 00 minutes 00 seconds,,Dumbbell Lateral Raise,1,13.2277357311,15,0,0,,"Slow negatives, ""paused"" at the top"
        2026-02-20 19:00:00,01 hours 00 minutes 00 seconds,,Dumbbell Lateral Raise,2,13.2277357311,12,0,0,,"Slow negatives, ""paused"" at the top"
        2026-02-20 19:00:00,01 hours 00 minutes 00 seconds,,Bench Press,0,132.277357311,8,0,0,,"Line one
        line two, with comma"
    """.trimIndent()

    val EMPTY_ROWS = """
        $HEADER
        2026-02-20 19:00:00,01 hours 00 minutes 00 seconds,,,0,0,0,0,0,,
        2026-02-20 19:00:00,01 hours 00 minutes 00 seconds,,Bench Press,0,0,0,0,0,,
        2026-02-20 19:00:00,01 hours 00 minutes 00 seconds,,Bench Press,1,132.277357311,8,0,0,,
        ,,,,,,,,,,
        2026-02-20 19:00:00,01 hours 00 minutes 00 seconds,,,0,132.277357311,8,0,0,,

    """.trimIndent()

    val SHUFFLED_SET_ORDER = """
        $HEADER
        2026-02-20 19:00:00,01 hours 00 minutes 00 seconds,,Bench Press,3,121.254244339,8,0,0,,
        2026-02-20 19:00:00,01 hours 00 minutes 00 seconds,,Bench Press,0,44.092452437,20,0,0,,
        2026-02-20 19:00:00,01 hours 00 minutes 00 seconds,,Bench Press,2,132.277357311,10,0,0,,
        2026-02-20 19:00:00,01 hours 00 minutes 00 seconds,,Bench Press,1,88.184904874,15,0,0,,
    """.trimIndent()

    val CRLF = HEADER + "\r\n" +
        "2026-02-20 19:00:00,01 hours 00 minutes 00 seconds,,Bench Press,0,132.277357311,8,0,0,7.5,\"note, with comma\"\r\n" +
        "2026-02-20 19:00:00,01 hours 00 minutes 00 seconds,,Bench Press,1,132.277357311,8,0,0,,\r\n"
}
