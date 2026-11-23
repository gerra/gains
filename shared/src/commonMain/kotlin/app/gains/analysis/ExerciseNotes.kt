package app.gains.analysis

import app.gains.domain.Session
import kotlinx.datetime.LocalDate
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * The notes a lifter keeps on an exercise: the one written in each workout, and a standing note
 * per exercise ("seat on 4, grip one finger wider") shown on its card whenever that workout's note is
 * empty. The standing notes are stored together as one synced setting, by exercise id.
 */
object ExerciseNotes {
    /** A note written on [exerciseId] in the workout [sessionId], on [date]. */
    data class Past(val sessionId: String, val date: LocalDate, val note: String)

    /** Every note written on [exerciseId], newest first; [excludeSessionId] is the workout being edited. */
    fun history(sessions: List<Session>, exerciseId: String, excludeSessionId: String? = null): List<Past> =
        byExercise(sessions, excludeSessionId)[exerciseId].orEmpty()

    /** Every exercise's notes at once, newest first, by exercise id; one pass for a workout's cards. */
    fun byExercise(sessions: List<Session>, excludeSessionId: String? = null): Map<String, List<Past>> {
        val notes = LinkedHashMap<String, MutableList<Past>>()
        sessions.filter { it.id != excludeSessionId }.sortedByDescending { it.timestamp }.forEach { session ->
            session.exercises.forEach { entry ->
                val text = entry.note?.trim().orEmpty()
                val list = notes.getOrPut(entry.exerciseId) { mutableListOf() }
                // The same lift twice in one workout lists its note once.
                if (text.isNotEmpty() && list.lastOrNull()?.sessionId != session.id) list += Past(session.id, session.date, text)
            }
        }
        return notes.filterValues { it.isNotEmpty() }
    }

    private val serializer = MapSerializer(String.serializer(), String.serializer())
    private val json = Json { ignoreUnknownKeys = true }

    /** The standing notes as stored: blank ones are dropped, so clearing a note removes it. */
    fun encode(notes: Map<String, String>): String =
        json.encodeToString(serializer, notes.mapValues { it.value.trim() }.filterValues { it.isNotEmpty() }.entries.sortedBy { it.key }.associate { it.key to it.value })

    /** Nothing stored, or something unreadable, is no notes rather than an error. */
    fun decode(raw: String?): Map<String, String> =
        raw?.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

    /** [notes] with the standing note of [exerciseId] set to [note], or removed when it is blank. */
    fun withNote(notes: Map<String, String>, exerciseId: String, note: String): Map<String, String> =
        if (note.isBlank()) notes - exerciseId else notes + (exerciseId to note.trim())
}
