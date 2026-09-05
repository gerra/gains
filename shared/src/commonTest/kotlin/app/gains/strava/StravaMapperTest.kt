package app.gains.strava

import app.gains.catalogue.ExerciseCatalogue
import app.gains.domain.ExerciseEntry
import app.gains.domain.Modality
import app.gains.domain.ProgramDayRef
import app.gains.domain.Session
import app.gains.domain.SetEntry
import app.gains.domain.SetType
import app.gains.domain.WeightUnit
import app.gains.importer.ExerciseResolver
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StravaMapperTest {
    private val resolver get() = ExerciseResolver(emptyList(), emptyMap())
    private val exercisesById = ExerciseCatalogue.builtIn.associateBy { it.id }

    private fun activity(
        id: Long = 42, sport: String = "Run", start: String = "2026-09-05T07:12:34Z", elapsed: Int = 1900, moving: Int = 1800,
        distance: Double = 5230.0, name: String = "Morning Run", elevation: Double = 0.0, hr: Double? = null, trainer: Boolean = false,
    ) = StravaActivity(id = id, name = name, sportType = sport, startDateLocal = start, elapsedTime = elapsed, movingTime = moving, distance = distance, elevationGain = elevation, averageHeartrate = hr, trainer = trainer)

    @Test
    fun runBecomesOneCardioSetOnTheRunningExercise() {
        val session = StravaMapper.toSession(activity(elevation = 120.4, hr = 151.6), resolver)!!
        assertEquals("strava-42", session.id)
        assertEquals(Session.STRAVA, session.source)
        assertEquals(LocalDateTime(2026, 9, 5, 7, 12, 34), session.timestamp)
        assertEquals(32, session.durationMinutes)
        val entry = session.exercises.single()
        assertEquals("running", entry.exerciseId)
        val set = entry.sets.single()
        assertEquals(SetType.CARDIO, set.type)
        assertEquals(1800, set.seconds)
        assertEquals(5.23, set.distanceKm)
        assertEquals("Morning Run · ↑ 120 m · avg HR 152 bpm", entry.note)
    }

    @Test
    fun rideVariantsMapToCyclingAndWeightTrainingIsSkipped() {
        for (sport in listOf("Ride", "VirtualRide", "GravelRide", "MountainBikeRide", "EBikeRide")) {
            assertEquals("cycling", StravaMapper.toSession(activity(sport = sport), resolver)!!.exercises.single().exerciseId, sport)
        }
        assertNull(StravaMapper.toSession(activity(sport = "WeightTraining"), resolver))
        assertNull(StravaMapper.toSession(activity(sport = "Workout"), resolver))
        // Old activities carry only the legacy type field.
        val legacy = StravaActivity(id = 1, type = "Run", startDateLocal = "2019-03-01T10:00:00Z", elapsedTime = 600)
        assertEquals("running", StravaMapper.toSession(legacy, resolver)!!.exercises.single().exerciseId)
    }

    @Test
    fun unknownSportBecomesACustomCardioExerciseNamedAfterIt() {
        val r = resolver
        val session = StravaMapper.toSession(activity(sport = "AlpineSki", distance = 0.0), r)!!
        val created = r.newExercises.single()
        assertEquals("Alpine Ski", created.name)
        assertEquals(Modality.CARDIO, created.modality)
        assertEquals(created.id, session.exercises.single().exerciseId)
        assertNull(session.exercises.single().sets.single().distanceKm)
        assertEquals("EBike Ride", StravaMapper.humanize("EBikeRide"))
    }

    @Test
    fun localTimestampsAreReadWithoutTimezoneShift() {
        assertEquals(LocalDateTime(2026, 9, 5, 7, 12, 34), StravaMapper.parseLocal("2026-09-05T07:12:34Z"))
        assertEquals(LocalDateTime(2026, 9, 5, 7, 12, 34), StravaMapper.parseLocal("2026-09-05T07:12:34.000+02:00"))
        assertEquals(LocalDateTime(2026, 9, 5, 7, 12), StravaMapper.parseLocal("2026-09-05T07:12"))
        assertNull(StravaMapper.parseLocal("yesterday"))
        assertNull(StravaMapper.toSession(activity(start = "garbage"), resolver))
        assertEquals("2026-01-02T18:05:00Z", StravaMapper.formatLocal(LocalDateTime(2026, 1, 2, 18, 5)))
    }

    private fun lifting() = Session(
        id = "2026-09-04T18:30", timestamp = LocalDateTime(2026, 9, 4, 18, 30), durationMinutes = 55, source = Session.MANUAL,
        program = ProgramDayRef("gzclp", "a1"),
        exercises = listOf(
            ExerciseEntry("bench_press", listOf(
                SetEntry(0, SetType.WEIGHTED, 60.0, 5), SetEntry(1, SetType.WEIGHTED, 60.0, 5), SetEntry(2, SetType.WEIGHTED, 60.0, 5), SetEntry(3, SetType.WEIGHTED, 62.5, 3),
            )),
            ExerciseEntry("pull_up", listOf(SetEntry(0, SetType.BODYWEIGHT, reps = 8), SetEntry(1, SetType.BODYWEIGHT, reps = 6)), note = "wide grip"),
            ExerciseEntry("dead_hang", listOf(SetEntry(0, SetType.ISOMETRIC, seconds = 60), SetEntry(1, SetType.ISOMETRIC, seconds = 45))),
        ),
    )

    @Test
    fun liftingSessionUploadsAsWeightTrainingWithEverySetDescribed() {
        val activity = StravaMapper.toActivity(lifting(), exercisesById, WeightUnit.KG, name = "A1")
        assertEquals("A1", activity.name)
        assertEquals("WeightTraining", activity.sportType)
        assertEquals("2026-09-04T18:30:00Z", activity.startDateLocal)
        assertEquals(55 * 60, activity.elapsedSeconds)
        assertNull(activity.distanceMetres)
        assertEquals(
            "Bench Press: 60 kg × 5, 5, 5 · 62.5 kg × 3\nPull Up: 8, 6 (wide grip)\nDead Hang: 1:00, 45 s\n\nLogged with Gains",
            activity.description,
        )
    }

    @Test
    fun weightsFollowTheDisplayUnitAndMissingDurationIsEstimated() {
        val session = lifting().copy(durationMinutes = null, program = null)
        val activity = StravaMapper.toActivity(session, exercisesById, WeightUnit.LBS)
        assertEquals("Workout", activity.name)
        assertTrue(activity.description.startsWith("Bench Press: 132.3 lbs × 5, 5, 5 · 137.8 lbs × 3"), activity.description)
        // 8 sets × 3 minutes = 24 minutes.
        assertEquals(24 * 60, activity.elapsedSeconds)
        assertEquals(20 * 60, StravaMapper.estimateSeconds(session.copy(exercises = session.exercises.take(1))))
    }

    @Test
    fun cardioOnlySessionUploadsAsItsOwnSportWithDistance() {
        val run = Session(
            id = "r", timestamp = LocalDateTime(2026, 9, 3, 6, 0), source = Session.MANUAL,
            exercises = listOf(ExerciseEntry("running", listOf(SetEntry(0, SetType.CARDIO, seconds = 1500, distanceKm = 5.0)))),
        )
        val activity = StravaMapper.toActivity(run, exercisesById, WeightUnit.KG)
        assertEquals("Run", activity.sportType)
        assertEquals("Running", activity.name)
        assertEquals(1500, activity.elapsedSeconds)
        assertEquals(5000.0, activity.distanceMetres)
        assertEquals(false, activity.trainer)
        assertTrue(activity.description.startsWith("Running: 5 km in 25:00"), activity.description)

        val erg = run.copy(exercises = listOf(ExerciseEntry("rowing", listOf(SetEntry(0, SetType.CARDIO, seconds = 1200, distanceKm = 5.0)))))
        val rowing = StravaMapper.toActivity(erg, exercisesById, WeightUnit.KG)
        assertEquals("Rowing", rowing.sportType)
        assertTrue(rowing.trainer)

        // Cardio next to lifting stays a gym session.
        val mixed = lifting().copy(exercises = lifting().exercises + run.exercises)
        assertEquals("WeightTraining", StravaMapper.toActivity(mixed, exercisesById, WeightUnit.KG).sportType)
    }
}
