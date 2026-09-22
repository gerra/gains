package app.gains.data

import app.gains.db.ProgramQueries
import app.gains.db.SessionQueries
import app.gains.domain.Program
import app.gains.domain.Session
import app.gains.importer.ImportAnalyzer

/*
 * Writing a whole session or a whole program to its rows. The repositories call these from their
 * own transactions and the sync's apply step calls them from its, so a document that arrives from
 * another device is stored exactly the way one saved here is. Both must be called inside a
 * transaction: they are several statements that only make sense together.
 */

/** Replaces the session's row, entries and sets. The photo and its table are not touched. */
internal fun writeSessionRows(q: SessionQueries, session: Session) {
    q.deleteSetsForSession(session.id)
    q.deleteEntriesForSession(session.id)
    q.insertSession(
        id = session.id,
        timestamp = session.timestamp.toString(),
        date = session.date.toString(),
        duration_minutes = session.durationMinutes?.toLong(),
        fingerprint = ImportAnalyzer.fingerprint(session),
        content_hash = ImportAnalyzer.contentHash(session),
        source = session.source,
        program_id = session.program?.programId,
        program_day_id = session.program?.dayId,
        caption = session.caption?.takeIf { it.isNotBlank() },
    )
    session.exercises.forEachIndexed { position, entry ->
        q.insertEntry(session.id, entry.exerciseId, position.toLong(), entry.note)
        val entryId = q.lastInsertedId().executeAsOne()
        for (set in entry.sets) {
            q.insertSet(
                entry_id = entryId,
                set_order = set.order.toLong(),
                type = set.type.name,
                weight_kg = set.weightKg,
                reps = set.reps?.toLong(),
                seconds = set.seconds?.toLong(),
                distance_km = set.distanceKm,
                rpe = set.rpe,
                is_warmup = if (set.isWarmup) 1L else 0L,
            )
        }
    }
}

/** Replaces the program's row, days and slots. [createdAt] is kept from the row when there is one. */
internal fun writeProgramRows(q: ProgramQueries, program: Program, createdAt: String) {
    q.deleteSlotsForProgram(program.id)
    q.deleteDaysForProgram(program.id)
    q.upsertProgram(
        id = program.id, name = program.name, description = program.description,
        goals = ProgramCodec.encodeGoals(program.goals), level = program.level.name,
        days_per_week = program.daysPerWeek.toLong(), created_at = createdAt,
    )
    program.days.forEachIndexed { di, day ->
        q.insertDay(day.id, program.id, di.toLong(), day.name)
        day.slots.forEachIndexed { si, slot ->
            q.insertSlot(
                day_id = day.id, position = si.toLong(), exercise_id = slot.exerciseId,
                sets = slot.sets.toLong(), reps = ProgramCodec.encodeReps(slot.reps),
                last_set_amrap = if (slot.lastSetAmrap) 1L else 0L,
                progression = ProgramCodec.encodeRule(slot.progression), note = slot.note,
            )
        }
    }
}
