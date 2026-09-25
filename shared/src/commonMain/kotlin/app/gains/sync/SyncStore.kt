package app.gains.sync

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOne
import app.cash.sqldelight.coroutines.mapToOneOrNull
import app.gains.data.writeProgramRows
import app.gains.data.writeSessionRows
import app.gains.db.GainsDatabase
import app.gains.domain.ExerciseEntry
import app.gains.domain.ProgramDayRef
import app.gains.domain.Session
import app.gains.domain.SetEntry
import app.gains.domain.SetType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/** One row of `sync_change`: a document this device has changed and not pushed yet. */
data class PendingChange(val kind: String, val id: String, val changedAt: String, val deleted: Boolean)

/**
 * The sync's side of the device database: the change log the triggers in Sync.sq keep, the
 * documents built from the tables for a push, the tables written from documents on a pull, and
 * the values kept between runs (the pull cursor, when the last run went through). The bearer
 * token is kept by [vault]: the Keychain on iOS, the Keystore on Android. See docs/sync.md.
 */
class SyncStore(
    private val db: GainsDatabase,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val vault: TokenVault = SqliteTokenVault(db, io),
) {
    private val q get() = db.syncQueries

    /**
     * Whether a token row from before [vault] may still sit in `sync_state`. Never for a
     * [SqliteTokenVault], whose own row it is; for any other vault it is checked once, on the
     * first read of the token.
     */
    private var legacyTokenUnchecked = vault !is SqliteTokenVault

    // --- the change log ---------------------------------------------------------------------

    suspend fun pendingChanges(): List<PendingChange> = withContext(io) {
        q.selectChanges().executeAsList().map { PendingChange(it.kind, it.id, it.changed_at, it.deleted != 0L) }
    }

    /** How many documents wait to be pushed; the controller wakes up when it grows. */
    fun observePendingCount(): Flow<Long> = q.countChanges().asFlow().mapToOne(io).flowOn(io)

    /** Forgets a pushed change, unless the document changed again in the meantime. */
    suspend fun clearPushed(change: PendingChange) = withContext(io) {
        q.clearChangeIfUnchanged(change.kind, change.id, change.changedAt)
    }

    /**
     * Marks everything that would be synced as changed now, so the next push uploads the whole
     * device: what a sign-in on a device with guest data does, to merge it with the account's.
     */
    suspend fun markAllPending() = withContext(io) {
        val now = now()
        db.transaction {
            q.selectSessionIdsForSync().executeAsList().forEach { q.markChange(SyncKinds.SESSION, it, now, 0) }
            q.selectPhotoIdsForSync().executeAsList().forEach { q.markChange(SyncKinds.SESSION_PHOTO, it, now, 0) }
            q.selectCustomExerciseIds().executeAsList().forEach { q.markChange(SyncKinds.EXERCISE, it, now, 0) }
            q.selectAliasNames().executeAsList().forEach { q.markChange(SyncKinds.ALIAS, it, now, 0) }
            q.selectOverrideIds().executeAsList().forEach { q.markChange(SyncKinds.OVERRIDE, it, now, 0) }
            q.selectBodyweightDates().executeAsList().forEach { q.markChange(SyncKinds.BODYWEIGHT, it, now, 0) }
            q.selectProgramIds().executeAsList().forEach { q.markChange(SyncKinds.PROGRAM, it, now, 0) }
            q.selectSettingKeys().executeAsList().filter { it in SyncKinds.settingKeys }.forEach { q.markChange(SyncKinds.SETTING, it, now, 0) }
        }
    }

    // --- documents out ----------------------------------------------------------------------

    /**
     * The document for a pending change, built from the tables. A change whose row has gone (a
     * delete that was not marked as one, which the triggers never produce, but a guard is cheap)
     * becomes a tombstone. Photos have no payload here: their bytes go through [photo].
     */
    suspend fun load(change: PendingChange): SyncDocument = withContext(io) {
        val payload = if (change.deleted) null else payloadOf(change.kind, change.id)
        if (payload == null) {
            SyncDocument(change.kind, change.id, change.changedAt, deleted = true)
        } else {
            SyncDocument(change.kind, change.id, change.changedAt, deleted = false, payload = payload)
        }
    }

    suspend fun photo(sessionId: String): ByteArray? = withContext(io) { db.sessionQueries.selectPhoto(sessionId).executeAsOneOrNull() }

    private fun payloadOf(kind: String, id: String): String? = when (kind) {
        SyncKinds.SESSION -> readSession(id)?.let { SyncJson.encodeToString(SessionDoc.serializer(), SessionDoc.of(it)) }
        SyncKinds.SESSION_PHOTO -> if (db.sessionQueries.selectPhotoIds().executeAsList().contains(id)) "{}" else null
        SyncKinds.EXERCISE -> db.exerciseQueries.selectExerciseById(id).executeAsOneOrNull()?.takeIf { it.is_builtin == 0L }?.let {
            SyncJson.encodeToString(ExerciseDoc.serializer(), ExerciseDoc(it.name, it.canonical_name, it.modality, it.is_dumbbell != 0L, it.muscles, it.equipment))
        }
        SyncKinds.ALIAS -> db.exerciseQueries.selectAlias(id).executeAsOneOrNull()?.let { SyncJson.encodeToString(AliasDoc.serializer(), AliasDoc(it.exercise_id)) }
        SyncKinds.OVERRIDE -> db.exerciseQueries.selectOverride(id).executeAsOneOrNull()?.let { SyncJson.encodeToString(OverrideDoc.serializer(), OverrideDoc(it.working_set_ratio)) }
        SyncKinds.BODYWEIGHT -> db.bodyweightQueries.selectByDate(id).executeAsOneOrNull()?.let { SyncJson.encodeToString(BodyweightDoc.serializer(), BodyweightDoc(it.weight_kg)) }
        SyncKinds.PROGRAM -> readProgram(id)?.let { SyncJson.encodeToString(ProgramDoc.serializer(), it) }
        SyncKinds.SETTING -> if (id in SyncKinds.settingKeys) {
            db.settingsQueries.selectValue(id).executeAsOneOrNull()?.let { SyncJson.encodeToString(SettingDoc.serializer(), SettingDoc(it)) }
        } else null
        else -> null
    }

    private fun readSession(id: String): Session? {
        val sq = db.sessionQueries
        val row = sq.selectSessionById(id).executeAsOneOrNull() ?: return null
        val sets = sq.selectSetsForSession(id).executeAsList().groupBy { it.entry_id }
        return Session(
            id = row.id,
            timestamp = LocalDateTime.parse(row.timestamp),
            durationMinutes = row.duration_minutes?.toInt(),
            source = row.source,
            program = if (row.program_id.isNullOrBlank() || row.program_day_id.isNullOrBlank()) null else ProgramDayRef(row.program_id, row.program_day_id),
            caption = row.caption?.takeIf { it.isNotBlank() },
            exercises = sq.selectEntriesForSession(id).executeAsList().map { e ->
                ExerciseEntry(
                    exerciseId = e.exercise_id,
                    note = e.note,
                    sets = (sets[e.id] ?: emptyList()).map { st ->
                        SetEntry(st.set_order.toInt(), SetType.valueOf(st.type), st.weight_kg, st.reps?.toInt(), st.seconds?.toInt(), st.distance_km, st.rpe, st.is_warmup != 0L)
                    },
                )
            },
        )
    }

    private fun readProgram(id: String): ProgramDoc? {
        val pq = db.programQueries
        val row = pq.selectProgramById(id).executeAsOneOrNull() ?: return null
        val slots = pq.selectSlotsForProgram(id).executeAsList().groupBy { it.day_id }
        return ProgramDoc(
            name = row.name,
            description = row.description,
            goals = row.goals,
            level = row.level,
            daysPerWeek = row.days_per_week.toInt(),
            createdAt = row.created_at,
            days = pq.selectDaysForProgram(id).executeAsList().map { d ->
                DayDoc(d.id, d.name, (slots[d.id] ?: emptyList()).map { s ->
                    SlotDoc(s.exercise_id, s.sets.toInt(), s.reps, s.last_set_amrap != 0L, s.progression, s.note)
                })
            },
        )
    }

    // --- documents in -----------------------------------------------------------------------

    /**
     * Applies one page of pulled documents and advances the cursor, all in one transaction. A
     * document this device changed more recently than [SyncDocument.updatedAt] is left alone: the
     * local version will push and win. Every other one replaces the rows it stands for, and its
     * change row is deleted in the same transaction, so what the triggers recorded for the apply
     * is never pushed back. [photos] holds the bytes fetched for the `session_photo` documents.
     */
    suspend fun applyPage(documents: List<SyncDocument>, photos: Map<String, ByteArray>, cursor: Long) = withContext(io) {
        db.transaction {
            for (doc in documents) {
                val local = q.selectChange(doc.kind, doc.id).executeAsOneOrNull()
                if (local != null && local.changed_at > doc.updatedAt) continue
                if (apply(doc, photos[doc.id])) q.clearChange(doc.kind, doc.id)
            }
            q.upsertState(KEY_CURSOR, cursor.toString())
        }
    }

    /** Writes the document's rows. False when the kind is unknown or the payload cannot be read, which leaves the change row alone. */
    private fun apply(doc: SyncDocument, photo: ByteArray?): Boolean {
        val sq = db.sessionQueries
        val eq = db.exerciseQueries
        val pq = db.programQueries
        return runCatching {
            when (doc.kind) {
                SyncKinds.SESSION -> if (doc.deleted) {
                    sq.deleteSetsForSession(doc.id); sq.deleteEntriesForSession(doc.id); sq.deletePhoto(doc.id); sq.deleteSession(doc.id)
                    q.clearChange(SyncKinds.SESSION_PHOTO, doc.id)
                } else {
                    writeSessionRows(sq, SyncJson.decodeFromString(SessionDoc.serializer(), doc.payload).toSession(doc.id))
                }
                SyncKinds.SESSION_PHOTO -> if (doc.deleted) sq.deletePhoto(doc.id) else {
                    // A photo whose bytes could not be fetched keeps its change row and is tried again.
                    if (photo == null) return false
                    if (sq.selectSessionById(doc.id).executeAsOneOrNull() != null) sq.upsertPhoto(doc.id, photo)
                }
                SyncKinds.EXERCISE -> if (doc.deleted) eq.deleteExercise(doc.id) else {
                    val e = SyncJson.decodeFromString(ExerciseDoc.serializer(), doc.payload)
                    eq.upsertExercise(doc.id, e.name, e.canonicalName, e.modality, if (e.isDumbbell) 1L else 0L, 0L, e.muscles, e.equipment)
                }
                SyncKinds.ALIAS -> if (doc.deleted) eq.deleteAlias(doc.id) else {
                    eq.upsertAlias(doc.id, SyncJson.decodeFromString(AliasDoc.serializer(), doc.payload).exerciseId)
                }
                SyncKinds.OVERRIDE -> if (doc.deleted) eq.deleteOverride(doc.id) else {
                    eq.upsertOverride(doc.id, SyncJson.decodeFromString(OverrideDoc.serializer(), doc.payload).workingSetRatio)
                }
                SyncKinds.BODYWEIGHT -> if (doc.deleted) db.bodyweightQueries.delete(doc.id) else {
                    db.bodyweightQueries.upsert(doc.id, SyncJson.decodeFromString(BodyweightDoc.serializer(), doc.payload).kg)
                }
                SyncKinds.PROGRAM -> if (doc.deleted) {
                    pq.deleteSlotsForProgram(doc.id); pq.deleteDaysForProgram(doc.id); pq.deleteProgram(doc.id)
                } else {
                    val p = SyncJson.decodeFromString(ProgramDoc.serializer(), doc.payload)
                    writeProgramRows(pq, p.toProgram(doc.id), p.createdAt)
                }
                SyncKinds.SETTING -> if (!doc.deleted && doc.id in SyncKinds.settingKeys) {
                    db.settingsQueries.upsert(doc.id, SyncJson.decodeFromString(SettingDoc.serializer(), doc.payload).value)
                }
                else -> return false
            }
            true
        }.getOrDefault(false)
    }

    // --- state ------------------------------------------------------------------------------

    suspend fun cursor(): Long = withContext(io) { q.selectState(KEY_CURSOR).executeAsOneOrNull()?.toLongOrNull() ?: 0L }

    suspend fun token(): String? = vault.get() ?: moveLegacyToken()

    suspend fun setToken(token: String) {
        vault.set(token)
        dropLegacyToken()
    }

    /**
     * The token an earlier version kept in `sync_state`, moved into [vault] and its row deleted,
     * so an update keeps the person signed in. Null when there is none or it was already moved.
     */
    private suspend fun moveLegacyToken(): String? {
        if (!legacyTokenUnchecked) return null
        val legacy = withContext(io) { q.selectState(KEY_TOKEN).executeAsOneOrNull()?.ifBlank { null } }
        if (legacy != null) vault.set(legacy)
        dropLegacyToken()
        return legacy
    }

    /** Deletes the old token row once [vault] holds the token, or holds none on purpose. */
    private suspend fun dropLegacyToken() {
        if (vault is SqliteTokenVault) return
        withContext(io) { q.deleteState(KEY_TOKEN) }
        legacyTokenUnchecked = false
    }

    /** When the token was last issued, so the controller knows when to ask for a fresh one. */
    suspend fun tokenIssuedAt(): String? = withContext(io) { q.selectState(KEY_TOKEN_ISSUED).executeAsOneOrNull() }

    suspend fun setTokenIssuedAt(at: String) = withContext(io) { q.upsertState(KEY_TOKEN_ISSUED, at) }

    /** The id of the user the cursor belongs to; a different one signing in starts the feed over. */
    suspend fun userId(): Long? = withContext(io) { q.selectState(KEY_USER).executeAsOneOrNull()?.toLongOrNull() }

    /**
     * Starts following [userId]'s feed from the beginning. Local data stays and, on a change of
     * user (or the first sign-in), is marked as changed so it merges into the account.
     */
    suspend fun startFeed(userId: Long) = withContext(io) {
        val previous = q.selectState(KEY_USER).executeAsOneOrNull()?.toLongOrNull()
        if (previous != userId) {
            db.transaction {
                q.upsertState(KEY_USER, userId.toString())
                q.upsertState(KEY_CURSOR, "0")
                // The last run was another account's; this one has not synced yet.
                q.deleteState(KEY_LAST_SYNCED)
            }
            markAllPending()
        }
    }

    /**
     * When a sync last went through, in [now]'s format, or null before the first one. Stored
     * rather than kept on [SyncEngine.status] so Settings can still say "Synced 5 min ago" after
     * a restart.
     */
    fun observeLastSyncedAt(): Flow<String?> = q.selectState(KEY_LAST_SYNCED).asFlow().mapToOneOrNull(io).flowOn(io)

    suspend fun setLastSyncedAt(at: String) = withContext(io) { q.upsertState(KEY_LAST_SYNCED, at) }

    /** Forgets the token. The cursor and the user stay, so signing back in resumes rather than re-merges. */
    suspend fun clearToken() {
        vault.clear()
        dropLegacyToken()
        withContext(io) { q.deleteState(KEY_TOKEN_ISSUED) }
    }

    /**
     * Forgets whose feed this device follows and how far it has read, so the next sign-in, to
     * whichever account, starts the feed over and uploads everything on the device. What a deleted
     * account needs: its documents are gone from the server, so the device must not rely on the
     * next account's user id differing from the old one's (a server rebuilt from an empty database
     * hands out the same ids again). See docs/sync.md, "Signing in".
     */
    suspend fun forgetFeed() = withContext(io) {
        db.transaction { q.deleteState(KEY_USER); q.deleteState(KEY_CURSOR); q.deleteState(KEY_LAST_SYNCED) }
    }

    companion object {
        const val KEY_CURSOR = "cursor"
        /** The token's row in `sync_state`: a [SqliteTokenVault]'s, or one left by a version before the vault. */
        const val KEY_TOKEN = "token"
        const val KEY_TOKEN_ISSUED = "token_issued_at"
        const val KEY_USER = "user_id"
        const val KEY_LAST_SYNCED = "last_synced_at"

        /**
         * This moment as the triggers write it: UTC, millisecond precision, `Z`. The same shape
         * on both sides keeps the plain string comparison last-writer-wins relies on honest.
         */
        fun now(): String = format(Clock.System.now().toLocalDateTime(TimeZone.UTC))

        fun format(t: LocalDateTime): String {
            fun two(n: Int) = n.toString().padStart(2, '0')
            val millis = (t.nanosecond / 1_000_000).toString().padStart(3, '0')
            return "${t.year}-${two(t.month.number)}-${two(t.day)}T${two(t.hour)}:${two(t.minute)}:${two(t.second)}.${millis}Z"
        }
    }
}
