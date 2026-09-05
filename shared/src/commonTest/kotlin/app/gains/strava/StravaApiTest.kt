package app.gains.strava

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StravaApiTest {
    private val credentials = StravaCredentials("123", "s3cret")
    private val requests = ArrayList<HttpRequestData>()

    private fun api(status: HttpStatusCode = HttpStatusCode.OK, body: (HttpRequestData) -> String) = StravaApi(
        MockEngine { request ->
            requests.add(request)
            respond(body(request), status, headersOf(HttpHeaders.ContentType, "application/json; charset=utf-8"))
        },
    )

    private fun HttpRequestData.form(): Map<String, String> {
        val form = body as FormDataContent
        return form.formData.names().associateWith { form.formData[it]!! }
    }

    @Test
    fun authorizeUrlCarriesEveryOAuthParameter() {
        val url = Url(api { "" }.authorizeUrl(credentials, "gains://localhost/strava", "xyz", mobile = true))
        assertEquals("/oauth/mobile/authorize", url.encodedPath)
        assertEquals("123", url.parameters["client_id"])
        assertEquals("code", url.parameters["response_type"])
        assertEquals("gains://localhost/strava", url.parameters["redirect_uri"])
        assertEquals(StravaApi.SCOPES, url.parameters["scope"])
        assertEquals("xyz", url.parameters["state"])
        assertEquals("/oauth/authorize", Url(api { "" }.authorizeUrl(credentials, "http://127.0.0.1:1/x", "s", mobile = false)).encodedPath)
    }

    @Test
    fun codeExchangeAndRefreshPostTheRightForm() = runTest {
        val api = api {
            """{"token_type":"Bearer","expires_at":1757100000,"expires_in":21600,"refresh_token":"r1","access_token":"a1",
               "athlete":{"id":9,"firstname":"Ada","lastname":"Lovelace","city":"London"}}"""
        }
        val token = api.exchangeCode(credentials, "the-code")
        assertEquals("a1", token.accessToken)
        assertEquals("r1", token.refreshToken)
        assertEquals(1757100000L, token.expiresAt)
        assertEquals("Ada Lovelace", token.athlete?.displayName)
        val exchange = requests.single()
        assertEquals(HttpMethod.Post, exchange.method)
        assertEquals("/oauth/token", exchange.url.encodedPath)
        assertEquals(mapOf("client_id" to "123", "client_secret" to "s3cret", "grant_type" to "authorization_code", "code" to "the-code"), exchange.form())

        api.refresh(credentials, "r1")
        assertEquals(mapOf("client_id" to "123", "client_secret" to "s3cret", "grant_type" to "refresh_token", "refresh_token" to "r1"), requests[1].form())
    }

    @Test
    fun activitiesAreListedWithBearerTokenAndPaging() = runTest {
        val api = api {
            """[{"id":1001,"name":"Evening Ride","sport_type":"Ride","type":"Ride","start_date_local":"2026-09-01T18:00:00Z","start_date":"2026-09-01T16:00:00Z",
                 "elapsed_time":3700,"moving_time":3500,"distance":25000.5,"total_elevation_gain":210.0,"average_heartrate":140.2,"trainer":false,"manual":false,"kudos_count":3,"map":{"id":"a1"}},
                {"id":1002,"name":"Lunch Run","sport_type":"Run","start_date_local":"2026-09-02T12:00:00Z","elapsed_time":1800,"moving_time":1750,"distance":5000.0}]"""
        }
        val activities = api.listActivities("tok", after = 1_700_000_000L, page = 2, perPage = 50)
        assertEquals(listOf(1001L, 1002L), activities.map { it.id })
        assertEquals(25000.5, activities[0].distance)
        assertEquals(140.2, activities[0].averageHeartrate)
        assertEquals(null, activities[1].averageHeartrate)
        val request = requests.single()
        assertEquals("Bearer tok", request.headers[HttpHeaders.Authorization])
        assertEquals("/api/v3/athlete/activities", request.url.encodedPath)
        assertEquals("1700000000", request.url.parameters["after"])
        assertEquals("2", request.url.parameters["page"])
        assertEquals("50", request.url.parameters["per_page"])
    }

    @Test
    fun creatingAnActivityPostsTheManualActivityForm() = runTest {
        val api = api { """{"id":555,"name":"A1","sport_type":"WeightTraining","start_date_local":"2026-09-04T18:30:00Z","elapsed_time":3300}""" }
        val created = api.createActivity("tok", NewStravaActivity("A1", "WeightTraining", "2026-09-04T18:30:00Z", 3300, "Bench Press: 60 kg × 5", distanceMetres = null, trainer = false))
        assertEquals(555L, created.id)
        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("/api/v3/activities", request.url.encodedPath)
        assertEquals("Bearer tok", request.headers[HttpHeaders.Authorization])
        assertEquals(
            mapOf("name" to "A1", "sport_type" to "WeightTraining", "start_date_local" to "2026-09-04T18:30:00Z", "elapsed_time" to "3300", "description" to "Bench Press: 60 kg × 5"),
            request.form(),
        )
        api.createActivity("tok", NewStravaActivity("Erg", "Rowing", "2026-09-04T18:30:00Z", 1200, "", distanceMetres = 5000.0, trainer = true))
        assertEquals("5000.0", requests[1].form()["distance"])
        assertEquals("1", requests[1].form()["trainer"])
    }

    @Test
    fun errorsBecomeReadableExceptions() = runTest {
        val limited = assertFailsWith<StravaException> { api(HttpStatusCode.TooManyRequests) { """{"message":"Rate Limit Exceeded","errors":[]}""" }.listActivities("tok") }
        assertTrue(limited.isRateLimited)
        assertTrue("15 minutes" in limited.message!!)
        val unauthorized = assertFailsWith<StravaException> { api(HttpStatusCode.Unauthorized) { """{"message":"Authorization Error"}""" }.listActivities("tok") }
        assertTrue(unauthorized.isUnauthorized)
        val other = assertFailsWith<StravaException> { api(HttpStatusCode.BadRequest) { """{"message":"Bad Request","errors":[{"field":"code"}]}""" }.exchangeCode(credentials, "x") }
        assertEquals("Strava returned 400: Bad Request", other.message)
        val garbage = assertFailsWith<StravaException> { api { "<html>oops</html>" }.listActivities("tok") }
        assertTrue("could not read" in garbage.message!!)
    }
}
