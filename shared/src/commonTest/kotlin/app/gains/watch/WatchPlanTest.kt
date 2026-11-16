package app.gains.watch

import app.gains.domain.Exercise
import app.gains.domain.LiveExercise
import app.gains.domain.LiveSession
import app.gains.domain.Modality
import app.gains.domain.RestTimer
import app.gains.domain.SetDraft
import app.gains.domain.WeightUnit
import app.gains.program.Gzclp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchPlanTest {
    private val start = 1_700_000_000_000L
    private val squat = Exercise("squat", "Squat", "squat", emptyList(), Modality.WEIGHTED)
    private val curl = Exercise("curl", "Dumbbell curl", "dumbbell curl", emptyList(), Modality.WEIGHTED, isDumbbell = true)
    private val plank = Exercise("plank", "Plank", "plank", emptyList(), Modality.ISOMETRIC)
    private val byId = listOf(squat, curl, plank).associateBy { it.id }

    private fun set(weight: String = "100", reps: String = "5", done: Boolean = false, warmup: Boolean = false) =
        SetDraft(weight = weight, reps = reps, done = done, isWarmup = warmup)

    private fun session(vararg exercises: LiveExercise, rest: RestTimer? = null) =
        LiveSession(startedAtMs = start, title = "Day A", exercises = exercises.toList(), rest = rest)

    private val rest90 = { _: String, warmup: Boolean -> WatchPlan.restSeconds(null, warmup) }

    @Test
    fun theWorkoutCarriesNamesKindsLabelsAndCounts() {
        val live = session(
            LiveExercise("squat", listOf(set("40", "10", done = true, warmup = true), set(done = true), set())),
            LiveExercise("curl", listOf(set("12", "10"))),
            LiveExercise("plank", listOf(SetDraft(seconds = "60"))),
        )
        val w = WatchPlan.workout(live, WeightUnit.KG, byId, mapOf("squat" to "Приседания"))
        assertEquals("Day A", w.title)
        assertEquals("kg", w.unit)
        assertEquals(listOf("Приседания", "Dumbbell curl", "Plank"), w.exercises.map { it.name })
        assertEquals(listOf(WatchKind.WEIGHTED, WatchKind.WEIGHTED, WatchKind.TIMED), w.exercises.map { it.kind })
        assertEquals(listOf(false, true, false), w.exercises.map { it.perDumbbell })
        assertEquals(listOf("W1", "1", "2"), w.exercises[0].sets.map { it.label })
        assertEquals(2, w.done)
        assertEquals(5, w.total)
        assertEquals(WatchSetRef(0, 2), w.current)
        assertEquals(WatchSetRef(1, 0), w.next)
    }

    @Test
    fun anUnknownLiftShowsByIdAndIsJudgedByItsSets() {
        val live = session(LiveExercise("mystery", listOf(SetDraft(reps = "12"))))
        val w = WatchPlan.workout(live, WeightUnit.LBS, emptyMap(), emptyMap())
        assertEquals("mystery", w.exercises.single().name)
        assertEquals(WatchKind.BODYWEIGHT, w.exercises.single().kind)
        assertEquals("lbs", w.unit)
    }

    @Test
    fun currentFollowsTheLiftTheRestBelongsTo() {
        // Squat skipped, curls started: the watch stays on curls, then the plank, then back to squats.
        val done = listOf(listOf(false, false), listOf(true, false), listOf(false))
        assertEquals(WatchSetRef(1, 1), WatchPlan.current(done, anchor = 1))
        assertEquals(WatchSetRef(2, 0), WatchPlan.after(done, WatchSetRef(1, 1)))
        assertEquals(WatchSetRef(0, 0), WatchPlan.current(done, anchor = null))
        assertEquals(WatchSetRef(0, 1), WatchPlan.after(done, WatchSetRef(0, 0)))
    }

    @Test
    fun currentWrapsAroundAndNextIsNullForTheLastSetLeft() {
        val done = listOf(listOf(false), listOf(true, true))
        assertEquals(WatchSetRef(0, 0), WatchPlan.current(done, anchor = 1))
        assertNull(WatchPlan.after(done, WatchSetRef(0, 0)))
        assertNull(WatchPlan.current(listOf(listOf(true), listOf(true)), anchor = 0))
        assertNull(WatchPlan.current(emptyList(), anchor = null))
    }

    @Test
    fun nextFallsBackToAnEarlierSetOfTheSameLift() {
        val done = listOf(listOf(false, true, false))
        assertEquals(WatchSetRef(0, 0), WatchPlan.after(done, WatchSetRef(0, 2)))
    }

    @Test
    fun completeTicksWithTheWatchValuesAndStartsTheRest() {
        val live = session(LiveExercise("squat", listOf(set(done = true), set())))
        val now = start + 600_000
        val after = WatchPlan.apply(live, WatchCommand.Complete(start, "squat", 1, weight = "102.5", reps = "4"), now, rest90)
        assertNotNull(after)
        assertEquals(SetDraft("102.5", "4", done = true), after.exercises[0].sets[1])
        assertEquals(RestTimer("squat", now + 90_000, 90), after.rest)
    }

    @Test
    fun completeKeepsThePhoneValuesWhenTheWatchSendsNone() {
        val live = session(LiveExercise("squat", listOf(set("80", "8"))))
        val after = WatchPlan.apply(live, WatchCommand.Complete(start, "squat", 0), start, rest90)
        assertEquals(SetDraft("80", "8", done = true), after?.exercises?.get(0)?.sets?.get(0))
    }

    @Test
    fun aWarmUpRestsShorterAndATierRestsItsOwnLength() {
        val live = session(LiveExercise("squat", listOf(set(warmup = true), set())))
        val warm = WatchPlan.apply(live, WatchCommand.Complete(start, "squat", 0), start, rest90)
        assertEquals(Gzclp.WARMUP_REST.first, warm?.rest?.totalSeconds)
        val t1 = { _: String, warmup: Boolean -> WatchPlan.restSeconds(Gzclp.Tier.T1, warmup) }
        assertEquals(180, WatchPlan.apply(live, WatchCommand.Complete(start, "squat", 1), start, t1)?.rest?.totalSeconds)
    }

    @Test
    fun completeRefusesWhatCannotBeTicked() {
        val live = session(LiveExercise("squat", listOf(set(done = true), SetDraft())))
        // Already done, empty with nothing sent, a lift or set that is not there, another workout.
        assertNull(WatchPlan.apply(live, WatchCommand.Complete(start, "squat", 0), start, rest90))
        assertNull(WatchPlan.apply(live, WatchCommand.Complete(start, "squat", 1), start, rest90))
        assertNull(WatchPlan.apply(live, WatchCommand.Complete(start, "bench", 0), start, rest90))
        assertNull(WatchPlan.apply(live, WatchCommand.Complete(start, "squat", 5), start, rest90))
        assertNull(WatchPlan.apply(live, WatchCommand.Complete(start - 1, "squat", 1, reps = "5"), start, rest90))
        // An empty set with reps from the watch can be ticked.
        assertNotNull(WatchPlan.apply(live, WatchCommand.Complete(start, "squat", 1, reps = "5"), start, rest90))
    }

    @Test
    fun undoUnticksAndLeavesTheRest() {
        val rest = RestTimer("squat", start + 90_000, 90)
        val live = session(LiveExercise("squat", listOf(set(done = true))), rest = rest)
        val after = WatchPlan.apply(live, WatchCommand.Undo(start, "squat", 0), start, rest90)
        assertEquals(false, after?.exercises?.get(0)?.sets?.get(0)?.done)
        assertEquals(rest, after?.rest)
        assertNull(WatchPlan.apply(after!!, WatchCommand.Undo(start, "squat", 0), start, rest90))
    }

    @Test
    fun skipRestDropsItAndIsANoOpWithout() {
        val live = session(LiveExercise("squat", listOf(set())), rest = RestTimer("squat", start + 90_000, 90))
        assertNull(WatchPlan.apply(live, WatchCommand.SkipRest(start), start, rest90)?.rest)
        assertNull(WatchPlan.apply(live.copy(rest = null), WatchCommand.SkipRest(start), start, rest90))
    }

    @Test
    fun extendRestAddsToARunningRestOrStartsANewOne() {
        val live = session(LiveExercise("squat", listOf(set())), rest = RestTimer("squat", start + 90_000, 90))
        assertEquals(RestTimer("squat", start + 120_000, 120), WatchPlan.apply(live, WatchCommand.ExtendRest(start, 30), start + 10_000, rest90)?.rest)
        // Over already: thirty seconds from now.
        assertEquals(RestTimer("squat", start + 230_000, 30), WatchPlan.apply(live, WatchCommand.ExtendRest(start, 30), start + 200_000, rest90)?.rest)
        assertEquals(RestTimer("squat", start + 30_000, 30), WatchPlan.apply(live.copy(rest = null), WatchCommand.ExtendRest(start, 30), start, rest90)?.rest)
        assertNull(WatchPlan.apply(live, WatchCommand.ExtendRest(start, 0), start, rest90))
    }

    @Test
    fun commandsRoundTripThroughJson() {
        val commands = listOf(
            WatchCommand.Complete(start, "squat", 2, weight = "100", reps = "5"),
            WatchCommand.Complete(start, "plank", 0, seconds = "45"),
            WatchCommand.Undo(start, "squat", 1),
            WatchCommand.SkipRest(start),
            WatchCommand.ExtendRest(start, 30),
        )
        for (c in commands) assertEquals(c, WatchJson.decodeCommand(WatchJson.encode(c)))
        assertTrue(WatchJson.encode(WatchCommand.SkipRest(start)).contains("\"type\":\"skipRest\""))
        assertNull(WatchJson.decodeCommand("{\"type\":\"dance\",\"startedAtMs\":1}"))
        assertNull(WatchJson.decodeCommand("not json"))
    }

    @Test
    fun stateRoundTripsAndIgnoresFieldsItDoesNotKnow() {
        val live = session(LiveExercise("squat", listOf(set(done = true), set())), rest = RestTimer("squat", start + 90_000, 90))
        val state = WatchState(sentAtMs = start + 1, workout = WatchPlan.workout(live, WeightUnit.KG, byId, emptyMap()))
        assertEquals(state, WatchJson.decodeState(WatchJson.encode(state)))
        val newer = WatchJson.encode(state).replaceFirst("{", "{\"somethingNew\":[1,2],")
        assertEquals(state, WatchJson.decodeState(newer))
    }
}
