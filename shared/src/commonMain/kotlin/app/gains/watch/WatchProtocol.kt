package app.gains.watch

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * What the iPhone and the Apple Watch say to each other (docs/watch.md). The phone sends a
 * [WatchState] every time the workout in progress changes; the watch sends a [WatchCommand] when
 * the lifter ticks a set, undoes one, or skips or stretches the rest. Both travel as JSON strings
 * inside WatchConnectivity's dictionaries, so the Swift side decodes them with plain Codable
 * structs (iosApp/GainsWatch/WatchProtocol.swift). Field names are part of the protocol: rename
 * one here and there together, and bump [WatchState.VERSION] when an old watch could misread
 * the new shape.
 */

/** Everything the watch shows, as of [sentAtMs]. */
@Serializable
data class WatchState(
    val version: Int = VERSION,
    /** The phone's clock when this was sent, so the watch can tell an old state from a new one. */
    val sentAtMs: Long,
    /** The workout in progress; null while none runs. */
    val workout: WatchWorkout? = null,
    /**
     * The last workout to stop, so the watch can close what it opened for it: save its Health
     * workout when the session was stored, drop it when the session was discarded.
     */
    val ended: WatchEnded? = null,
) {
    companion object {
        /** Bumped when a field changes meaning or a required one is added. */
        const val VERSION = 1
    }
}

/** A workout in progress, as the watch draws it. */
@Serializable
data class WatchWorkout(
    /** Wall-clock start in epoch milliseconds: the clock counts from here, and commands name it. */
    val startedAtMs: Long,
    val title: String,
    /** The unit every weight here is typed in: "kg" or "lbs". */
    val unit: String,
    /** The rest counting down, if one is. Its end is wall-clock time, so the watch counts it itself. */
    val rest: WatchRest? = null,
    val exercises: List<WatchExercise>,
    /** The set to do now; null once every set is ticked. */
    val current: WatchSetRef? = null,
    /** The set after [current]; null when [current] is the last one left. */
    val next: WatchSetRef? = null,
    /** Sets ticked off, and every set in the workout. */
    val done: Int,
    val total: Int,
)

@Serializable
data class WatchRest(
    /** Which exercise's set started it. */
    val exerciseId: String,
    val endsAtMs: Long,
    val totalSeconds: Int,
)

@Serializable
data class WatchExercise(
    val id: String,
    /** In the phone's language. */
    val name: String,
    /** How its sets are logged, which decides what the watch lets the lifter change. */
    val kind: WatchKind,
    /** Weights are per dumbbell. */
    val perDumbbell: Boolean = false,
    val sets: List<WatchSet>,
)

@Serializable
enum class WatchKind {
    /** Weight and reps. */
    @SerialName("weighted") WEIGHTED,
    /** Reps only. */
    @SerialName("bodyweight") BODYWEIGHT,
    /** Seconds held. */
    @SerialName("timed") TIMED,
    /** Distance and time. */
    @SerialName("cardio") CARDIO,
}

/** A set as typed on the phone: text, exactly as the editor holds it ("62.5", "8", ""). */
@Serializable
data class WatchSet(
    /** "W1", "W2" for warm-ups and "1", "2" for work sets, as the editor numbers them. */
    val label: String,
    val weight: String = "",
    val reps: String = "",
    val seconds: String = "",
    val distanceKm: String = "",
    val warmup: Boolean = false,
    val done: Boolean = false,
    /**
     * The rest, in seconds, that ticking this set starts: the watch starts it at once, before the
     * phone has answered, so a tick out of the phone's reach still counts down the right time.
     */
    val rest: Int = 0,
)

/** A set by its place: the exercise's index in [WatchWorkout.exercises] and the set's within it. */
@Serializable
data class WatchSetRef(val exercise: Int, val set: Int)

@Serializable
data class WatchEnded(val startedAtMs: Long, val saved: Boolean)

/**
 * Something done on the watch. Every command names the workout it was meant for by its start,
 * so one that arrives late (WatchConnectivity queues them while the phone is out of reach) is
 * dropped rather than applied to the next workout. A set is named by its exercise's id and its
 * index, so a reorder on the phone in the meantime cannot tick the wrong lift.
 */
@Serializable
sealed class WatchCommand {
    abstract val startedAtMs: Long

    /** Tick a set off, with the values the lifter settled on at the watch (null keeps the phone's). */
    @Serializable
    @SerialName("complete")
    data class Complete(
        override val startedAtMs: Long,
        val exerciseId: String,
        val set: Int,
        val weight: String? = null,
        val reps: String? = null,
        val seconds: String? = null,
    ) : WatchCommand()

    /** Un-tick a set. */
    @Serializable
    @SerialName("undo")
    data class Undo(override val startedAtMs: Long, val exerciseId: String, val set: Int) : WatchCommand()

    @Serializable
    @SerialName("skipRest")
    data class SkipRest(override val startedAtMs: Long) : WatchCommand()

    /** Add [seconds] to the rest, or start a rest that long when the last one is over. */
    @Serializable
    @SerialName("extendRest")
    data class ExtendRest(override val startedAtMs: Long, val seconds: Int) : WatchCommand()
}

/** The one JSON setup both ends of the link use. */
object WatchJson {
    val json = Json {
        // An older phone or watch may not know a newer field: skip it rather than fail.
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
        explicitNulls = false
    }

    fun encode(state: WatchState): String = json.encodeToString(WatchState.serializer(), state)
    fun decodeState(text: String): WatchState? = runCatching { json.decodeFromString(WatchState.serializer(), text) }.getOrNull()
    fun encode(command: WatchCommand): String = json.encodeToString(WatchCommand.serializer(), command)
    fun decodeCommand(text: String): WatchCommand? = runCatching { json.decodeFromString(WatchCommand.serializer(), text) }.getOrNull()
}
