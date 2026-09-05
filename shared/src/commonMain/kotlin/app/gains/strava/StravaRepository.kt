package app.gains.strava

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.gains.data.SettingsRepository
import app.gains.db.GainsDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Everything Gains remembers about Strava: the connection and credentials (JSON in the
 * settings table) and the session ↔ activity links (their own table).
 */
class StravaRepository(
    private val db: GainsDatabase,
    private val settings: SettingsRepository,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val q get() = db.stravaQueries

    fun observeConnection(): Flow<StravaConnection?> = settings.observe(KEY_CONNECTION).map { decode<StravaConnection>(it) }
    suspend fun connection(): StravaConnection? = observeConnection().first()
    suspend fun saveConnection(connection: StravaConnection?) =
        settings.set(KEY_CONNECTION, connection?.let { json.encodeToString(StravaConnection.serializer(), it) } ?: "")

    fun observeCredentials(): Flow<StravaCredentials?> = settings.observe(KEY_CREDENTIALS).map { decode<StravaCredentials>(it) }
    suspend fun credentials(): StravaCredentials? = observeCredentials().first()
    suspend fun saveCredentials(credentials: StravaCredentials?) =
        settings.set(KEY_CREDENTIALS, credentials?.let { json.encodeToString(StravaCredentials.serializer(), it) } ?: "")

    /** Unix seconds when the last download pass started; null before the first one. */
    fun observeLastSync(): Flow<Long?> = settings.observe(KEY_LAST_SYNC).map { it?.toLongOrNull() }
    suspend fun lastSync(): Long? = observeLastSync().first()
    suspend fun setLastSync(epochSeconds: Long?) = settings.set(KEY_LAST_SYNC, epochSeconds?.toString() ?: "")

    fun observeLinks(): Flow<List<StravaLink>> = q.selectLinks().asFlow().mapToList(io).map { rows -> rows.map(::link) }.flowOn(io)
    suspend fun links(): List<StravaLink> = withContext(io) { q.selectLinks().executeAsList().map(::link) }

    suspend fun upsertLinks(links: List<StravaLink>) = withContext(io) {
        db.transaction { for (l in links) q.upsertLink(l.sessionId, l.activityId, l.direction.name, l.syncedAt) }
    }

    suspend fun deleteLink(sessionId: String) = withContext(io) { q.deleteLink(sessionId) }

    /** Forgets every link, so the next sync downloads everything again and every session is uploadable. */
    suspend fun deleteAllLinks() = withContext(io) { q.deleteAllLinks() }

    private fun link(r: app.gains.db.Strava_link) = StravaLink(
        r.session_id, r.activity_id,
        SyncDirection.entries.firstOrNull { it.name == r.direction } ?: SyncDirection.DOWNLOAD,
        r.synced_at,
    )

    private inline fun <reified T> decode(raw: String?): T? =
        if (raw.isNullOrBlank()) null else runCatching { json.decodeFromString<T>(raw) }.getOrNull()

    companion object {
        const val KEY_CONNECTION = "strava_connection"
        const val KEY_CREDENTIALS = "strava_credentials"
        const val KEY_LAST_SYNC = "strava_last_sync"
    }
}
