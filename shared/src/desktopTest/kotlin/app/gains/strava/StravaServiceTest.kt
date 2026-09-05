package app.gains.strava

import app.gains.data.DesktopDriverFactory
import app.gains.data.ExerciseRepository
import app.gains.data.ProgramRepository
import app.gains.data.SessionRepository
import app.gains.data.SettingsRepository
import app.gains.db.GainsDatabase
import app.gains.domain.ExerciseEntry
import app.gains.domain.Session
import app.gains.domain.SetEntry
import app.gains.domain.SetType
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The whole Strava flow against an in-memory database and a scripted Strava. */
class StravaServiceTest {
    private class World {
        val db: GainsDatabase = GainsDatabase(DesktopDriverFactory(file = null).createDriver())
        val sessions = SessionRepository(db, Dispatchers.Unconfined)
        val exercises = ExerciseRepository(db, Dispatchers.Unconfined)
        val settings = SettingsRepository(db, Dispatchers.Unconfined)
        val programs = ProgramRepository(db, settings, Dispatchers.Unconfined)
        val repo = StravaRepository(db, settings, Dispatchers.Unconfined)
        val requests = ArrayList<HttpRequestData>()
        var clock = 1_757_000_000L
        /** Activities Strava lists, newest first, as JSON objects. */
        val activities = ArrayList<String>()
        var createStatus = HttpStatusCode.Created
        var nextCreatedId = 9000L
        var tokenStatus = HttpStatusCode.OK

        val engine = MockEngine { request ->
            requests.add(request)
            val path = request.url.encodedPath
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            when {
                path == "/oauth/token" -> respond(
                    if (tokenStatus.value >= 400) """{"message":"Bad Request"}"""
                    else """{"access_token":"access-${requests.size}","refresh_token":"refresh-${requests.size}","expires_at":${clock + 21600},"athlete":{"id":7,"firstname":"Ada","lastname":"L"}}""",
                    tokenStatus, json,
                )
                path == "/oauth/deauthorize" -> respond("{}", HttpStatusCode.OK, json)
                path == "/api/v3/athlete/activities" -> {
                    val page = request.url.parameters["page"]!!.toInt()
                    val perPage = request.url.parameters["per_page"]!!.toInt()
                    val slice = activities.drop((page - 1) * perPage).take(perPage)
                    respond("[${slice.joinToString(",")}]", HttpStatusCode.OK, json)
                }
                path == "/api/v3/activities" -> respond(
                    if (createStatus.value >= 400) """{"message":"Rate Limit Exceeded"}"""
                    else """{"id":${nextCreatedId++},"name":"x","sport_type":"WeightTraining","start_date_local":"2026-09-04T18:30:00Z"}""",
                    createStatus, json,
                )
                else -> respond("""{"message":"Not Found"}""", HttpStatusCode.NotFound, json)
            }
        }
        val service = StravaService(
            StravaApi(engine), repo, sessions, exercises, settings, programs,
            config = StravaConfig("123", "secret"), now = { clock }, pageSize = 2, newState = { "state-1" },
        )

        suspend fun seed() = exercises.seedCatalogue()

        suspend fun connect(): StravaConnection {
            service.beginAuthorization("http://127.0.0.1:1234/strava", mobile = false)
            return service.completeAuthorization("http://127.0.0.1:1234/strava?state=state-1&code=abc&scope=read,activity:write,activity:read_all")
        }

        fun activity(id: Long, sport: String, start: String, distance: Double = 5000.0, moving: Int = 1500, elapsed: Int = 1600, name: String = "Act $id") =
            """{"id":$id,"name":"$name","sport_type":"$sport","start_date_local":"$start","elapsed_time":$elapsed,"moving_time":$moving,"distance":$distance,"total_elevation_gain":12.0}"""
    }

    private fun HttpRequestData.form(): Map<String, String> {
        val f = body as FormDataContent
        return f.formData.names().associateWith { f.formData[it]!! }
    }

    private fun lifting(id: String, day: Int) = Session(
        id = id, timestamp = LocalDateTime(2026, 9, day, 18, 30), durationMinutes = 50, source = Session.MANUAL,
        exercises = listOf(ExerciseEntry("bench_press", listOf(SetEntry(0, SetType.WEIGHTED, 60.0, 5), SetEntry(1, SetType.WEIGHTED, 60.0, 5)))),
    )

    @Test
    fun authorisationStoresTheConnectionAndChecksTheState() = runTest {
        val w = World()
        val url = w.service.beginAuthorization("http://127.0.0.1:1234/strava", mobile = false)
        assertTrue(url.startsWith("https://www.strava.com/oauth/authorize?"), url)
        assertTrue(w.service.hasPendingAuthorization)
        val wrong = assertFailsWith<StravaException> { w.service.completeAuthorization("http://127.0.0.1:1234/strava?state=other&code=abc") }
        assertTrue("did not belong" in wrong.message!!)
        val declined = assertFailsWith<StravaException> { w.service.completeAuthorization("http://127.0.0.1:1234/strava?error=access_denied") }
        assertEquals("Access to Strava was declined.", declined.message)
        // Declining ends the attempt.
        assertTrue(!w.service.hasPendingAuthorization)

        val connection = w.connect()
        assertEquals("Ada L", connection.athleteName)
        assertEquals(7L, connection.athleteId)
        assertTrue(connection.canRead && connection.canWrite)
        assertEquals(connection, w.repo.connection())
        assertEquals("abc", w.requests.last().form()["code"])
        assertTrue(!w.service.hasPendingAuthorization)
    }

    @Test
    fun customSchemeCallbacksAreParsed() {
        val params = StravaService.queryParameters("gains://localhost/strava?state=s1&code=c1&scope=read,activity:write")
        assertEquals("s1", params["state"])
        assertEquals("c1", params["code"])
        assertEquals("read,activity:write", params["scope"])
    }

    @Test
    fun syncImportsCardioSkipsGymSessionsAndNeverImportsTheSameActivityTwice() = runTest {
        val w = World()
        w.seed()
        w.connect()
        w.activities += w.activity(3, "WeightTraining", "2026-09-03T18:00:00Z", distance = 0.0)
        w.activities += w.activity(2, "Ride", "2026-09-02T17:00:00Z", distance = 30250.0, moving = 3600, elapsed = 3900, name = "Evening Ride")
        w.activities += w.activity(1, "Run", "2026-09-01T07:00:00Z")
        val first = w.service.syncActivities()
        assertEquals(2, first.imported)
        assertEquals(mapOf("WeightTraining" to 1), first.skipped)
        assertEquals(3, first.fetched)
        // Two pages of two: the second page was fetched to find the end.
        assertEquals(2, w.requests.count { it.url.encodedPath == "/api/v3/athlete/activities" })
        assertNull(w.requests.first { it.url.encodedPath == "/api/v3/athlete/activities" }.url.parameters["after"])

        val stored = w.sessions.observeRawSessions().first()
        assertEquals(listOf("strava-1", "strava-2"), stored.map { it.id })
        val ride = stored.first { it.id == "strava-2" }
        assertEquals("cycling", ride.exercises.single().exerciseId)
        assertEquals(30.25, ride.exercises.single().sets.single().distanceKm)
        assertEquals(65, ride.durationMinutes)
        assertEquals(Session.STRAVA, ride.source)
        assertEquals(setOf("strava-1" to SyncDirection.DOWNLOAD, "strava-2" to SyncDirection.DOWNLOAD), w.repo.links().map { it.sessionId to it.direction }.toSet())
        assertEquals(w.clock, w.repo.lastSync())

        // Second pass: nothing new, and the request window starts a month before the last sync.
        w.clock += 3600
        w.activities.add(0, w.activity(4, "Run", "2026-09-04T07:00:00Z"))
        val second = w.service.syncActivities()
        assertEquals(1, second.imported)
        assertEquals(2, second.alreadyLinked)
        val listing = w.requests.last { it.url.encodedPath == "/api/v3/athlete/activities" }
        assertEquals((w.clock - 3600 - StravaService.LOOKBACK_SECONDS).toString(), listing.url.parameters["after"])
        assertEquals(3, w.sessions.observeRawSessions().first().size)

        // A downloaded session the user deleted stays deleted.
        w.sessions.deleteSession("strava-1")
        w.service.syncActivities()
        assertEquals(listOf("strava-2", "strava-4"), w.sessions.observeRawSessions().first().map { it.id })
    }

    @Test
    fun uploadCreatesAnActivityLinksItAndIsNeverImportedBack() = runTest {
        val w = World()
        w.seed()
        w.connect()
        w.sessions.upsertAll(listOf(lifting("a", 4), lifting("b", 5)))
        assertEquals(listOf("a", "b"), w.service.observeUploadable().first().map { it.id })

        val link = w.service.upload("a")
        assertEquals(9000L, link.activityId)
        assertEquals(SyncDirection.UPLOAD, link.direction)
        val create = w.requests.last()
        assertEquals("/api/v3/activities", create.url.encodedPath)
        assertEquals("WeightTraining", create.form()["sport_type"])
        assertEquals("2026-09-04T18:30:00Z", create.form()["start_date_local"])
        assertEquals("3000", create.form()["elapsed_time"])
        assertTrue(create.form()["description"]!!.startsWith("Bench Press: 60 kg × 5, 5"))
        assertEquals(listOf("b"), w.service.observeUploadable().first().map { it.id })

        // Uploading again is a no-op that returns the existing link.
        val before = w.requests.size
        assertEquals(link, w.service.upload("a"))
        assertEquals(before, w.requests.size)

        // The activity Gains created shows up on Strava; a sync must not turn it into a session.
        w.activities += w.activity(9000, "WeightTraining", "2026-09-04T18:30:00Z", distance = 0.0)
        val report = w.service.syncActivities()
        assertEquals(0, report.imported)
        assertEquals(1, report.alreadyLinked)
        assertEquals(2, w.sessions.observeRawSessions().first().size)

        // Strava sessions themselves are never uploaded.
        w.activities += w.activity(1, "Run", "2026-09-01T07:00:00Z")
        w.service.syncActivities()
        assertFailsWith<IllegalStateException> { w.service.upload("strava-1") }
    }

    @Test
    fun bulkUploadStopsAtTheRateLimitAndReportsProgress() = runTest {
        val w = World()
        w.seed()
        w.connect()
        w.sessions.upsertAll(listOf(lifting("a", 1), lifting("b", 2), lifting("c", 3)))
        val progress = ArrayList<Pair<Int, Int>>()
        val ok = w.service.uploadAll { done, total -> progress += done to total }
        assertEquals(UploadReport(3, 0), ok)
        assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), progress)

        w.sessions.upsertAll(listOf(lifting("d", 4), lifting("e", 5)))
        w.createStatus = HttpStatusCode.TooManyRequests
        val limited = w.service.uploadAll()
        assertEquals(0, limited.uploaded)
        assertEquals(2, limited.remaining)
        assertTrue("rate limit" in limited.error!!)
        assertEquals(listOf("d", "e"), w.service.observeUploadable().first().map { it.id })
    }

    @Test
    fun expiredTokensAreRefreshedAndARejectedRefreshDropsTheConnection() = runTest {
        val w = World()
        w.seed()
        val connection = w.connect()
        w.clock = connection.expiresAt + 10
        w.service.syncActivities()
        val refresh = w.requests.first { it.url.encodedPath == "/oauth/token" && it.form()["grant_type"] == "refresh_token" }
        assertEquals(connection.refreshToken, refresh.form()["refresh_token"])
        val updated = w.repo.connection()!!
        assertTrue(updated.accessToken != connection.accessToken)
        assertEquals(w.clock + 21600, updated.expiresAt)
        assertEquals("Bearer ${updated.accessToken}", w.requests.last().headers[HttpHeaders.Authorization])

        w.clock = updated.expiresAt + 10
        w.tokenStatus = HttpStatusCode.BadRequest
        assertFailsWith<StravaException> { w.service.syncActivities() }
        assertNull(w.repo.connection())
        assertFailsWith<StravaNotConnectedException> { w.service.syncActivities() }
    }

    @Test
    fun disconnectRevokesAndForgetsWhileSessionsStay() = runTest {
        val w = World()
        w.seed()
        w.connect()
        w.activities += w.activity(1, "Run", "2026-09-01T07:00:00Z")
        w.service.syncActivities()
        w.service.disconnect()
        assertEquals("/oauth/deauthorize", w.requests.last().url.encodedPath)
        assertNull(w.repo.connection())
        assertNull(w.repo.lastSync())
        assertEquals(1, w.sessions.observeRawSessions().first().size)
        assertEquals(1, w.repo.links().size)
        w.service.forgetLinks()
        assertEquals(0, w.repo.links().size)
    }

    @Test
    fun credentialsEnteredInTheAppWinOverTheCompiledDefaults() = runTest {
        val w = World()
        assertEquals(StravaCredentials("123", "secret"), w.service.credentials())
        w.service.saveCredentials(" 999 ", "other ")
        assertEquals(StravaCredentials("999", "other"), w.service.credentials())
        assertNotNull(w.service.observeCredentials().first())
        w.service.saveCredentials("", "")
        assertEquals(StravaCredentials("123", "secret"), w.service.credentials())
        val bare = StravaService(StravaApi(w.engine), w.repo, w.sessions, w.exercises, w.settings, w.programs)
        assertFailsWith<StravaNotConfiguredException> { bare.beginAuthorization("http://127.0.0.1/x", false) }
    }
}
