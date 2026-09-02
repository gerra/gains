package app.gains.connectors

import app.gains.csv.CsvFormatException
import app.gains.csv.Fixtures
import app.gains.domain.SetType
import app.gains.domain.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectorsTest {
    private val strong = """
        Date,Workout Name,Duration,Exercise Name,Set Order,Weight,Reps,Distance,Seconds,Notes,Workout Notes,RPE
        2026-03-02 18:05:00,Push,1h 5m,Bench Press (Barbell),1,60,8,0,0,,"felt good, easy",8
        2026-03-02 18:05:00,Push,1h 5m,Bench Press (Barbell),2,60,8,0,0,,"felt good, easy",8.5
        2026-03-02 18:05:00,Push,1h 5m,Plank,1,0,0,0,60,,"felt good, easy",
        2026-02-27 07:30:00,Legs,45m,Squat (Barbell),1,100,5,0,0,,,
    """.trimIndent()

    private val strongNew = """
        Date,Workout Name,Exercise Name,Set Order,Weight,Weight Unit,Reps,RPE,Distance,Distance Unit,Seconds,Notes,Workout Notes,Workout Duration
        2026-03-02 18:05:00,Push,Bench Press (Barbell),1,135,lbs,8,,0,,0,,,1h 5m
        2026-03-02 18:05:00,Push,Running,1,0,lbs,0,,2,miles,900,,,1h 5m
    """.trimIndent()

    private val hevy = """
        title,start_time,end_time,description,exercise_title,superset_id,exercise_notes,set_index,set_type,weight_kg,reps,distance_km,duration_seconds,rpe
        "Push Day","5 Jan 2026, 18:30","5 Jan 2026, 19:35",,Bench Press (Barbell),,,0,warmup,40,10,,,
        "Push Day","5 Jan 2026, 18:30","5 Jan 2026, 19:35",,Bench Press (Barbell),,,1,normal,60,8,,,
        "Push Day","5 Jan 2026, 18:30","5 Jan 2026, 19:35",,Lateral Raise (Dumbbell),,"slow eccentric, no swing",0,normal,8,15,,,
        "Run","7 Jan 2026, 07:00","7 Jan 2026, 07:32",,Running,,,0,normal,,,5.2,1900,
    """.trimIndent()

    private val generic = """
        date,exercise,weight (kg),reps,set
        2026-04-01,Deadlift,140,3,1
        2026-04-01,Deadlift,140,3,2
    """.trimIndent()

    @Test
    fun detectsEachFormat() {
        assertEquals("liftoff", Connectors.detect(Fixtures.SAMPLE).id)
        assertEquals("strong", Connectors.detect(strong).id)
        assertEquals("strong", Connectors.detect(strongNew).id)
        assertEquals("hevy", Connectors.detect(hevy).id)
        assertEquals("csv", Connectors.detect("Date,Exercise,Weight,Reps\n2026-01-01,Squat,100,5\n").id)
        assertFailsWith<CsvFormatException> { Connectors.detect("foo,bar\n1,2\n") }
        assertFailsWith<CsvFormatException> { Connectors.detect(generic) } // lower-case "weight (kg)" is not a known column
    }

    @Test
    fun strongExportsParseInTheUsersUnit() {
        val parsed = StrongConnector.parse(strong, ImportOptions(weightUnit = WeightUnit.KG))
        assertEquals(listOf("2026-02-27T07:30", "2026-03-02T18:05"), parsed.sessions.map { it.id })
        val push = parsed.sessions.last()
        assertEquals(65, push.durationMinutes)
        val bench = push.exercises.first { it.name == "Bench Press (Barbell)" }
        assertEquals(listOf(60.0, 60.0), bench.sets.map { it.weightKg })
        assertEquals(8.5, bench.sets[1].rpe)
        val plank = push.exercises.first { it.name == "Plank" }.sets.single()
        assertEquals(SetType.ISOMETRIC, plank.type)
        assertEquals(60, plank.seconds)
        assertEquals(45, parsed.sessions.first().durationMinutes)
    }

    @Test
    fun strongPerRowUnitsOverrideTheDefault() {
        val parsed = StrongConnector.parse(strongNew, ImportOptions(weightUnit = WeightUnit.KG))
        val session = parsed.sessions.single()
        val bench = session.exercises.first { it.name == "Bench Press (Barbell)" }.sets.single()
        assertEquals(61.25, bench.weightKg) // 135 lbs rounded to a quarter kilo
        val run = session.exercises.first { it.name == "Running" }.sets.single()
        assertEquals(SetType.CARDIO, run.type)
        assertEquals(3.218688, run.distanceKm!!, 1e-6)
        assertEquals(65, session.durationMinutes)
    }

    @Test
    fun hevyExportsUseColumnUnitsAndStartEndTimes() {
        val parsed = HevyConnector.parse(hevy, ImportOptions())
        assertEquals(2, parsed.sessions.size)
        val push = parsed.sessions.first { it.workoutName == "Push Day" }
        assertEquals("2026-01-05T18:30", push.id)
        assertEquals(65, push.durationMinutes)
        val bench = push.exercises.first { it.name == "Bench Press (Barbell)" }
        assertEquals(listOf(40.0, 60.0), bench.sets.map { it.weightKg })
        val raise = push.exercises.first { it.name == "Lateral Raise (Dumbbell)" }
        assertEquals("slow eccentric, no swing", raise.note)
        val run = parsed.sessions.first { it.workoutName == "Run" }.exercises.single().sets.single()
        assertEquals(SetType.CARDIO, run.type)
        assertEquals(5.2, run.distanceKm)
        assertEquals(1900, run.seconds)
        assertNull(run.weightKg)
    }

    @Test
    fun durationTextVariants() {
        assertEquals(65, WorkoutCsvParser.parseDurationText("1h 5m"))
        assertEquals(45, WorkoutCsvParser.parseDurationText("45m"))
        assertEquals(101, WorkoutCsvParser.parseDurationText("01 hours 41 minutes 04 seconds"))
        assertEquals(65, WorkoutCsvParser.parseDurationText("1:05:00"))
        assertEquals(65, WorkoutCsvParser.parseDurationText("65"))
        assertNull(WorkoutCsvParser.parseDurationText("garbage"))
    }

    @Test
    fun namedMonthTimestamps() {
        assertEquals("2026-01-05T18:30", WorkoutCsvParser.parseNamedMonthTimestamp("5 Jan 2026, 18:30").toString())
        assertEquals("2026-01-05T18:30", WorkoutCsvParser.parseNamedMonthTimestamp("Jan 5, 2026 6:30 PM").toString())
        assertEquals("2026-01-05T00:00", WorkoutCsvParser.parseNamedMonthTimestamp("2026-01-05").toString())
        assertNull(WorkoutCsvParser.parseNamedMonthTimestamp("someday"))
    }

    @Test
    fun everyConnectorRoundTripsThroughTheRegistry() {
        assertTrue(Connectors.all.map { it.id }.toSet().containsAll(listOf("liftoff", "strong", "hevy", "csv")))
        for (c in Connectors.all) assertEquals(c, Connectors.byId(c.id))
    }
}
