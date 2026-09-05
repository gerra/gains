package app.gains.strava

import app.gains.data.ExerciseRepository
import app.gains.data.ProgramRepository
import app.gains.data.SessionRepository
import app.gains.data.SettingsRepository
import app.gains.domain.Session
import app.gains.importer.ExerciseResolver
import io.ktor.http.Url
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Instant

class StravaNotConfiguredException : IllegalStateException("Enter the client id and secret of your Strava API application first.")
class StravaNotConnectedException : IllegalStateException("Connect Strava first.")

/**
 * The Strava integration: authorising the app, pulling activities into sessions and pushing
 * sessions up as manual activities. Links in [StravaRepository] make both directions
 * idempotent and stop an uploaded workout from coming back down as a new session.
 */
class StravaService(
    private val api: StravaApi,
    private val repo: StravaRepository,
    private val sessions: SessionRepository,
    private val exercises: ExerciseRepository,
    private val settings: SettingsRepository,
    private val programs: ProgramRepository,
    private val config: StravaConfig = StravaConfig(),
    private val now: () -> Long = { Clock.System.now().epochSeconds },
    private val pageSize: Int = 200,
    private val newState: () -> String = { Random.nextBytes(16).joinToString("") { b -> (b.toInt() and 0xff).toString(16).padStart(2, '0') } },
) {
    private class PendingAuthorization(val state: String, val redirectUri: String)

    private var pending: PendingAuthorization? = null

    fun observeConnection(): Flow<StravaConnection?> = repo.observeConnection()
    fun observeLinks(): Flow<List<StravaLink>> = repo.observeLinks()
    fun observeLastSync(): Flow<Long?> = repo.observeLastSync()

    /** Credentials entered in the app, else the compiled-in defaults, else null. */
    fun observeCredentials(): Flow<StravaCredentials?> = repo.observeCredentials().map { it ?: config.credentials }
    suspend fun credentials(): StravaCredentials? = repo.credentials() ?: config.credentials
    suspend fun saveCredentials(clientId: String, clientSecret: String) =
        repo.saveCredentials(StravaCredentials(clientId.trim(), clientSecret.trim()).takeIf { it.isComplete })

    /** Sessions Gains can upload: logged or imported here, not from Strava, not uploaded yet. Oldest first. */
    fun observeUploadable(): Flow<List<Session>> = combine(sessions.observeRawSessions(), repo.observeLinks()) { all, links ->
        val linked = links.map { it.sessionId }.toSet()
        all.filter { !it.isFromStrava && it.id !in linked && it.exercises.isNotEmpty() }
    }

    // ---- Authorisation ----

    /** Starts an authorisation attempt and returns the URL to open. The callback must come back to [redirectUri]. */
    suspend fun beginAuthorization(redirectUri: String, mobile: Boolean): String {
        val credentials = credentials() ?: throw StravaNotConfiguredException()
        val state = newState()
        pending = PendingAuthorization(state, redirectUri)
        return api.authorizeUrl(credentials, redirectUri, state, mobile)
    }

    val hasPendingAuthorization: Boolean get() = pending != null
    fun cancelAuthorization() { pending = null }

    /** Handles the URL Strava redirected to: checks the state, exchanges the code and stores the connection. */
    suspend fun completeAuthorization(callbackUrl: String): StravaConnection {
        val params = queryParameters(callbackUrl)
        params["error"]?.let { error ->
            pending = null
            throw StravaException(0, when (error) {
                "access_denied" -> "Access to Strava was declined."
                "cancelled" -> "The Strava sign-in was cancelled."
                else -> "Strava reported: $error"
            })
        }
        val attempt = pending ?: throw StravaException(0, "No Strava sign-in is in progress. Start again from the Strava screen.")
        if (params["state"] != attempt.state) throw StravaException(0, "Strava's reply did not belong to this sign-in attempt. Try again.")
        val code = params["code"] ?: throw StravaException(0, "Strava did not send an authorisation code.")
        val credentials = credentials() ?: throw StravaNotConfiguredException()
        val token = api.exchangeCode(credentials, code)
        val scopes = params["scope"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val connection = StravaConnection(
            accessToken = token.accessToken,
            refreshToken = token.refreshToken,
            expiresAt = token.expiresAt,
            athleteId = token.athlete?.id ?: 0L,
            athleteName = token.athlete?.displayName ?: "Strava athlete",
            scopes = scopes,
        )
        repo.saveConnection(connection)
        pending = null
        return connection
    }

    /** Revokes the token on Strava (best effort) and forgets it here. Sessions and links are kept. */
    suspend fun disconnect() {
        repo.connection()?.let { runCatching { api.deauthorize(it.accessToken) } }
        repo.saveConnection(null)
        repo.setLastSync(null)
    }

    /** Forgets every session ↔ activity link. Used when all sessions are deleted, so a fresh sync starts clean. */
    suspend fun forgetLinks() {
        repo.deleteAllLinks()
        repo.setLastSync(null)
    }

    /** A working access token, refreshed through Strava when the stored one has expired. */
    private suspend fun accessToken(): String {
        val connection = repo.connection() ?: throw StravaNotConnectedException()
        if (!connection.isExpired(now())) return connection.accessToken
        val credentials = credentials() ?: throw StravaNotConfiguredException()
        val refreshed = try {
            api.refresh(credentials, connection.refreshToken)
        } catch (e: StravaException) {
            // A refresh token Strava rejects is gone for good: the athlete has to connect again.
            if (e.isUnauthorized || e.status == 400) repo.saveConnection(null)
            throw e
        }
        val updated = connection.copy(accessToken = refreshed.accessToken, refreshToken = refreshed.refreshToken, expiresAt = refreshed.expiresAt)
        repo.saveConnection(updated)
        return updated.accessToken
    }

    // ---- Strava -> Gains ----

    /**
     * Downloads activities newer than the last sync (less a margin, since activities can reach
     * Strava days after they happened) and stores the ones not seen before as sessions.
     */
    suspend fun syncActivities(): SyncReport {
        val token = accessToken()
        val startedAt = now()
        val after = repo.lastSync()?.let { it - LOOKBACK_SECONDS }
        val linked = repo.links().map { it.activityId }.toHashSet()
        val resolver = ExerciseResolver(exercises.exercises(), exercises.aliases())
        val syncedAt = isoNow()
        val newSessions = ArrayList<Session>()
        val newLinks = ArrayList<StravaLink>()
        val skipped = LinkedHashMap<String, Int>()
        var alreadyLinked = 0
        var fetched = 0
        var page = 1
        while (true) {
            val batch = api.listActivities(token, after, page, pageSize)
            fetched += batch.size
            for (activity in batch) {
                if (activity.id in linked) { alreadyLinked++; continue }
                val session = StravaMapper.toSession(activity, resolver)
                if (session == null) {
                    val key = activity.sport.ifBlank { "Unknown" }
                    skipped[key] = (skipped[key] ?: 0) + 1
                    continue
                }
                linked.add(activity.id)
                newSessions.add(session)
                newLinks.add(StravaLink(session.id, activity.id, SyncDirection.DOWNLOAD, syncedAt))
            }
            if (batch.size < pageSize) break
            page++
        }
        if (resolver.newExercises.isNotEmpty()) exercises.insertIfMissing(resolver.newExercises)
        if (newSessions.isNotEmpty()) {
            sessions.upsertAll(newSessions)
            repo.upsertLinks(newLinks)
        }
        repo.setLastSync(startedAt)
        return SyncReport(imported = newSessions.size, alreadyLinked = alreadyLinked, skipped = skipped, fetched = fetched)
    }

    // ---- Gains -> Strava ----

    /** Creates a manual activity for the session and records the link. Uploading twice returns the existing link. */
    suspend fun upload(sessionId: String): StravaLink {
        val session = sessions.observeRawSessions().first().firstOrNull { it.id == sessionId }
            ?: throw IllegalArgumentException("That workout no longer exists.")
        return upload(session)
    }

    private suspend fun upload(session: Session): StravaLink {
        if (session.isFromStrava) throw IllegalStateException("This workout came from Strava; it is already there.")
        repo.links().firstOrNull { it.sessionId == session.id }?.let { return it }
        val token = accessToken()
        val exercisesById = exercises.exercises().associateBy { it.id }
        val unit = settings.observeUnit().first()
        val name = session.program?.let { ref ->
            programs.observePrograms().first().firstOrNull { it.id == ref.programId }?.day(ref.dayId)?.name
        }
        val created = api.createActivity(token, StravaMapper.toActivity(session, exercisesById, unit, name))
        val link = StravaLink(session.id, created.id, SyncDirection.UPLOAD, isoNow())
        repo.upsertLinks(listOf(link))
        return link
    }

    /**
     * Uploads every uploadable session, oldest first, stopping at the first failure: Strava
     * allows about a hundred writes per 15 minutes, so a long history takes several rounds.
     */
    suspend fun uploadAll(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): UploadReport {
        val todo = observeUploadable().first()
        var done = 0
        for (session in todo) {
            try {
                upload(session)
            } catch (e: Exception) {
                return UploadReport(uploaded = done, remaining = todo.size - done, error = e.message ?: "Upload failed.")
            }
            done++
            onProgress(done, todo.size)
        }
        return UploadReport(uploaded = done, remaining = 0)
    }

    private fun isoNow(): String = Instant.fromEpochSeconds(now()).toString()

    companion object {
        /** How far before the last sync a download pass looks, to catch late uploads to Strava. */
        const val LOOKBACK_SECONDS: Long = 30L * 24 * 60 * 60

        /** Query parameters of a callback URL, tolerant of custom schemes such as `gains://`. */
        fun queryParameters(url: String): Map<String, String> {
            runCatching { Url(url).parameters }.getOrNull()?.let { p -> return p.names().associateWith { p[it] ?: "" } }
            val query = url.substringAfter('?', "").substringBefore('#')
            return query.split('&').filter { it.isNotEmpty() }.associate { part ->
                part.substringBefore('=') to part.substringAfter('=', "").replace("%3A", ":").replace("%2C", ",")
            }
        }
    }
}
