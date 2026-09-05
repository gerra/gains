package app.gains.strava

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A non-2xx reply from Strava, with a message fit for the screen. */
class StravaException(val status: Int, message: String) : Exception(message) {
    val isUnauthorized: Boolean get() = status == 401
    val isRateLimited: Boolean get() = status == 429
}

/**
 * Thin client over the parts of Strava's v3 API that Gains uses: OAuth, listing the
 * athlete's activities and creating manual activities. Every call takes the access token
 * explicitly; refreshing it is [StravaService]'s job.
 */
class StravaApi(engine: HttpClientEngine, private val baseUrl: String = "https://www.strava.com") {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }
    private val client = HttpClient(engine) { expectSuccess = false }

    /** Where to send the athlete to approve Gains. The mobile endpoint hands off to the Strava app when installed. */
    fun authorizeUrl(credentials: StravaCredentials, redirectUri: String, state: String, mobile: Boolean): String =
        URLBuilder(if (mobile) "$baseUrl/oauth/mobile/authorize" else "$baseUrl/oauth/authorize").apply {
            parameters.append("client_id", credentials.clientId)
            parameters.append("response_type", "code")
            parameters.append("redirect_uri", redirectUri)
            parameters.append("approval_prompt", "auto")
            parameters.append("scope", SCOPES)
            parameters.append("state", state)
        }.buildString()

    suspend fun exchangeCode(credentials: StravaCredentials, code: String): StravaTokenResponse =
        token(credentials, "authorization_code", "code", code)

    suspend fun refresh(credentials: StravaCredentials, refreshToken: String): StravaTokenResponse =
        token(credentials, "refresh_token", "refresh_token", refreshToken)

    private suspend fun token(credentials: StravaCredentials, grantType: String, key: String, value: String): StravaTokenResponse {
        val response = client.submitForm("$baseUrl/oauth/token", Parameters.build {
            append("client_id", credentials.clientId)
            append("client_secret", credentials.clientSecret)
            append("grant_type", grantType)
            append(key, value)
        })
        return decode(response)
    }

    /** Revokes the token on Strava's side. */
    suspend fun deauthorize(accessToken: String) {
        val response = client.submitForm("$baseUrl/oauth/deauthorize", Parameters.build { append("access_token", accessToken) })
        if (!response.status.isSuccess()) throw error(response)
    }

    /**
     * One page of the athlete's activities, newest first. [after] is a Unix timestamp; pages
     * are 1-based and [perPage] is capped at 200 by Strava.
     */
    suspend fun listActivities(accessToken: String, after: Long? = null, page: Int = 1, perPage: Int = 200): List<StravaActivity> {
        val response = client.get("$baseUrl/api/v3/athlete/activities") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            if (after != null) parameter("after", after)
            parameter("page", page)
            parameter("per_page", perPage)
        }
        return decode(response)
    }

    /** Creates a manual activity; needs the `activity:write` scope. */
    suspend fun createActivity(accessToken: String, activity: NewStravaActivity): StravaActivity {
        val response = client.submitForm("$baseUrl/api/v3/activities", Parameters.build {
            append("name", activity.name)
            append("sport_type", activity.sportType)
            append("start_date_local", activity.startDateLocal)
            append("elapsed_time", activity.elapsedSeconds.toString())
            append("description", activity.description)
            activity.distanceMetres?.let { append("distance", it.toString()) }
            if (activity.trainer) append("trainer", "1")
        }) {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
        }
        return decode(response)
    }

    private suspend inline fun <reified T> decode(response: HttpResponse): T {
        if (!response.status.isSuccess()) throw error(response)
        val text = response.bodyAsText()
        return try {
            json.decodeFromString<T>(text)
        } catch (e: Exception) {
            throw StravaException(response.status.value, "Strava sent a reply Gains could not read: ${e.message}")
        }
    }

    private suspend fun error(response: HttpResponse): StravaException {
        val status = response.status.value
        val text = runCatching { response.bodyAsText() }.getOrDefault("")
        val detail = runCatching { json.parseToJsonElement(text).jsonObject["message"]?.jsonPrimitive?.content }.getOrNull()
        val message = when (status) {
            401 -> "Strava no longer accepts the connection. Connect again."
            403 -> "Strava refused: ${detail ?: "missing permission"}. Reconnect and allow reading and uploading activities."
            429 -> "Strava's rate limit was reached. Wait 15 minutes and try again."
            else -> "Strava returned $status" + (detail?.let { ": $it" } ?: "")
        }
        return StravaException(status, message)
    }

    companion object {
        /** Read the profile, every activity including private ones, and create activities. */
        const val SCOPES = "read,activity:read_all,activity:write"
    }
}
