package app.gains.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.gains.catalogue.ExerciseCatalogue
import app.gains.catalogue.NameNormalizer
import app.gains.db.GainsDatabase
import app.gains.domain.BodyweightEntry
import app.gains.domain.Exercise
import app.gains.domain.ExerciseEntry
import app.gains.domain.Modality
import app.gains.domain.MuscleContribution
import app.gains.domain.MuscleGroup
import app.gains.domain.ProgramDayRef
import app.gains.domain.ProgramLink
import app.gains.domain.Session
import app.gains.domain.SetEntry
import app.gains.domain.SetType
import app.gains.domain.WeightUnit
import app.gains.importer.ImportAnalyzer
import app.gains.importer.StoredSessionSummary
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime

/*
 * Threading: every observe*() flow ends in flowOn(io). SQLDelight's asFlow() registers and
 * removes query listeners in the collector's context, and the native driver guards its
 * listener map with the same lock it takes on every write. Collecting from the main thread
 * would make the UI thread wait on that lock while an IO thread (default QoS) holds it, a
 * priority inversion Xcode's Thread Performance Checker reports as a hang risk. Keeping
 * registration, query execution and row mapping on the IO threads avoids it entirely.
 */

class SessionRepository(
    private val db: GainsDatabase,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val q get() = db.sessionQueries

    /** All sessions, chronologically, without the warm-up rule applied. */
    fun observeRawSessions(): Flow<List<Session>> = combine(
        q.selectSessions().asFlow().mapToList(io),
        q.selectEntries().asFlow().mapToList(io),
        q.selectSets().asFlow().mapToList(io),
    ) { sessions, entries, sets ->
        val setsByEntry = sets.groupBy { it.entry_id }
        val entriesBySession = entries.groupBy { it.session_id }
        sessions.map { s ->
            Session(
                id = s.id,
                timestamp = LocalDateTime.parse(s.timestamp),
                durationMinutes = s.duration_minutes?.toInt(),
                source = s.source,
                program = programRef(s.program_id, s.program_day_id),
                exercises = (entriesBySession[s.id] ?: emptyList()).sortedBy { it.position }.map { e ->
                    ExerciseEntry(
                        exerciseId = e.exercise_id,
                        note = e.note,
                        sets = (setsByEntry[e.id] ?: emptyList()).sortedBy { it.set_order }.map { st ->
                            SetEntry(
                                order = st.set_order.toInt(),
                                type = SetType.valueOf(st.type),
                                weightKg = st.weight_kg,
                                reps = st.reps?.toInt(),
                                seconds = st.seconds?.toInt(),
                                distanceKm = st.distance_km,
                                rpe = st.rpe,
                            )
                        },
                    )
                },
            )
        }
    }.flowOn(io)

    /** Sessions started from a program day, oldest first. Cheap: no sets are loaded. */
    fun observeProgramLinks(): Flow<List<ProgramLink>> = q.selectProgramSessions().asFlow().mapToList(io).map { rows ->
        rows.mapNotNull { r -> programRef(r.program_id, r.program_day_id)?.let { ProgramLink(r.id, LocalDateTime.parse(r.timestamp), it) } }
    }.flowOn(io)

    suspend fun ids(): Set<String> = withContext(io) { q.selectSessionIds().executeAsList().toSet() }

    suspend fun summaries(): List<StoredSessionSummary> = withContext(io) {
        q.selectSummaries().executeAsList().map {
            StoredSessionSummary(it.id, LocalDate.parse(it.date), it.fingerprint, it.content_hash)
        }
    }

    suspend fun isometricHistory(): Map<String, List<Int>> = withContext(io) {
        q.selectIsometricSeconds().executeAsList()
            .groupBy({ it.exercise_id }, { it.seconds.toInt() })
    }

    /** Inserts or replaces whole sessions in one transaction. */
    suspend fun upsertAll(sessions: List<Session>) = withContext(io) {
        db.transaction {
            for (session in sessions) {
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
                        )
                    }
                }
            }
        }
    }

    suspend fun upsert(session: Session) = upsertAll(listOf(session))

    suspend fun deleteSession(id: String) = withContext(io) {
        db.transaction {
            q.deleteSetsForSession(id)
            q.deleteEntriesForSession(id)
            q.deleteSession(id)
        }
    }

    suspend fun deleteAll() = withContext(io) {
        db.transaction {
            q.deleteAllSets()
            q.deleteAllEntries()
            q.deleteAllSessions()
        }
    }

    private fun programRef(programId: String?, dayId: String?): ProgramDayRef? =
        if (programId.isNullOrBlank() || dayId.isNullOrBlank()) null else ProgramDayRef(programId, dayId)
}

class ExerciseRepository(
    private val db: GainsDatabase,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val q get() = db.exerciseQueries

    fun observeExercises(): Flow<List<Exercise>> = q.selectExercises().asFlow().mapToList(io).map { rows ->
        rows.map { r ->
            Exercise(
                id = r.id,
                name = r.name,
                canonicalName = r.canonical_name,
                modality = Modality.valueOf(r.modality),
                isDumbbell = r.is_dumbbell != 0L,
                isBuiltIn = r.is_builtin != 0L,
                muscleGroups = decodeMuscles(r.muscles),
                equipment = ProgramCodec.decodeEquipment(r.equipment),
            )
        }
    }.flowOn(io)

    /** normalized raw name -> exercise id */
    fun observeAliases(): Flow<Map<String, String>> = q.selectAliases().asFlow().mapToList(io).map { rows ->
        rows.associate { it.raw_name to it.exercise_id }
    }.flowOn(io)

    /** exercise id -> working-set ratio */
    fun observeWorkingSetRatios(): Flow<Map<String, Double>> = q.selectOverrides().asFlow().mapToList(io).map { rows ->
        rows.mapNotNull { r -> r.working_set_ratio?.let { r.exercise_id to it } }.toMap()
    }.flowOn(io)

    suspend fun exercises(): List<Exercise> = withContext(io) {
        q.selectExercises().executeAsList().map { r ->
            Exercise(r.id, r.name, r.canonical_name, decodeMuscles(r.muscles), Modality.valueOf(r.modality), r.is_dumbbell != 0L, r.is_builtin != 0L, ProgramCodec.decodeEquipment(r.equipment))
        }
    }

    suspend fun aliases(): Map<String, String> = withContext(io) {
        q.selectAliases().executeAsList().associate { it.raw_name to it.exercise_id }
    }

    suspend fun workingSetRatios(): Map<String, Double> = withContext(io) {
        q.selectOverrides().executeAsList().mapNotNull { r -> r.working_set_ratio?.let { r.exercise_id to it } }.toMap()
    }

    /** Writes the built-in catalogue. Built-ins are refreshed; nothing user-created is touched. */
    suspend fun seedCatalogue() = withContext(io) {
        db.transaction {
            for (e in ExerciseCatalogue.builtIn) {
                q.upsertExercise(e.id, e.name, e.canonicalName, e.modality.name, if (e.isDumbbell) 1L else 0L, 1L, encodeMuscles(e.muscleGroups), ProgramCodec.encodeEquipment(e.equipment))
            }
        }
    }

    suspend fun upsertAll(exercises: List<Exercise>) = withContext(io) {
        db.transaction {
            for (e in exercises) {
                q.upsertExercise(e.id, e.name, e.canonicalName, e.modality.name, if (e.isDumbbell) 1L else 0L, if (e.isBuiltIn) 1L else 0L, encodeMuscles(e.muscleGroups), ProgramCodec.encodeEquipment(e.equipment))
            }
        }
    }

    suspend fun insertIfMissing(exercises: List<Exercise>) = withContext(io) {
        db.transaction {
            for (e in exercises) {
                q.insertExerciseIfMissing(e.id, e.name, e.canonicalName, e.modality.name, if (e.isDumbbell) 1L else 0L, if (e.isBuiltIn) 1L else 0L, encodeMuscles(e.muscleGroups), ProgramCodec.encodeEquipment(e.equipment))
            }
        }
    }

    suspend fun setAlias(rawName: String, exerciseId: String) = withContext(io) {
        q.upsertAlias(NameNormalizer.normalize(rawName), exerciseId)
    }

    suspend fun removeAlias(rawName: String) = withContext(io) { q.deleteAlias(NameNormalizer.normalize(rawName)) }

    suspend fun setWorkingSetRatio(exerciseId: String, ratio: Double?) = withContext(io) {
        if (ratio == null) q.deleteOverride(exerciseId) else q.upsertOverride(exerciseId, ratio)
    }

    /**
     * Merges one exercise into another: history is re-pointed, an alias is recorded so
     * future imports resolve the same way, and a custom source exercise is deleted.
     */
    suspend fun merge(fromId: String, intoId: String, fromName: String) = withContext(io) {
        db.transaction {
            q.updateEntriesExercise(intoId, fromId)
            q.upsertAlias(NameNormalizer.normalize(fromName), intoId)
            q.deleteOverride(fromId)
            q.deleteExercise(fromId)
        }
    }

    companion object {
        fun encodeMuscles(groups: List<MuscleContribution>): String =
            groups.joinToString(",") { "${it.group.name}:${it.weight}" }

        fun decodeMuscles(raw: String): List<MuscleContribution> =
            raw.split(',').filter { it.isNotBlank() }.mapNotNull { token ->
                val (name, weight) = token.split(':').let { it[0] to (it.getOrNull(1)?.toDoubleOrNull() ?: 1.0) }
                MuscleGroup.entries.firstOrNull { it.name == name }?.let { MuscleContribution(it, weight) }
            }
    }
}

class BodyweightRepository(
    private val db: GainsDatabase,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    fun observe(): Flow<List<BodyweightEntry>> = db.bodyweightQueries.selectAll().asFlow().mapToList(io).map { rows ->
        rows.map { BodyweightEntry(LocalDate.parse(it.date), it.weight_kg) }
    }.flowOn(io)

    suspend fun upsert(entry: BodyweightEntry) = withContext(io) {
        db.bodyweightQueries.upsert(entry.date.toString(), entry.weightKg)
    }

    suspend fun delete(date: LocalDate) = withContext(io) { db.bodyweightQueries.delete(date.toString()) }
}

class SettingsRepository(
    private val db: GainsDatabase,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val q get() = db.settingsQueries

    fun observeUnit(): Flow<WeightUnit> = q.selectValue(KEY_UNIT).asFlow().map { query ->
        query.executeAsOneOrNull()?.let { runCatching { WeightUnit.valueOf(it) }.getOrNull() } ?: WeightUnit.KG
    }.flowOn(io)

    suspend fun setUnit(unit: WeightUnit) = withContext(io) { q.upsert(KEY_UNIT, unit.name) }

    fun observe(key: String): Flow<String?> = q.selectValue(key).asFlow().map { query -> query.executeAsOneOrNull() }.flowOn(io)

    suspend fun set(key: String, value: String) = withContext(io) { q.upsert(key, value) }

    fun observeThemeMode(): Flow<ThemeMode> = q.selectValue(KEY_THEME).asFlow().map { query ->
        query.executeAsOneOrNull()?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.DARK
    }.flowOn(io)

    suspend fun setThemeMode(mode: ThemeMode) = withContext(io) { q.upsert(KEY_THEME, mode.name) }

    companion object {
        const val KEY_UNIT = "weight_unit"
        const val KEY_THEME = "theme_mode"
    }
}

/** Appearance preference. Dark is the default look. */
enum class ThemeMode(val label: String) { DARK("Dark"), LIGHT("Light"), SYSTEM("System") }
