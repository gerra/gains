package app.gains.strava

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Client id and secret of a Strava API application (https://www.strava.com/settings/api). */
@Serializable
data class StravaCredentials(val clientId: String, val clientSecret: String) {
    val isComplete: Boolean get() = clientId.isNotBlank() && clientSecret.isNotBlank()
}

/**
 * Compile-time defaults for the Strava application. Leave them blank and the app asks for the
 * credentials on the Strava screen instead; a value entered there always wins over these.
 */
data class StravaConfig(val clientId: String = "", val clientSecret: String = "") {
    val credentials: StravaCredentials? get() = StravaCredentials(clientId, clientSecret).takeIf { it.isComplete }
}

/** The authorised athlete and the tokens Strava issued. Persisted as JSON in the settings table. */
@Serializable
data class StravaConnection(
    val accessToken: String,
    val refreshToken: String,
    /** Unix seconds at which [accessToken] stops working. */
    val expiresAt: Long,
    val athleteId: Long,
    val athleteName: String,
    /** Scopes the athlete granted, e.g. `read`, `activity:read_all`, `activity:write`. */
    val scopes: List<String> = emptyList(),
) {
    fun isExpired(nowSeconds: Long, leewaySeconds: Long = 60): Boolean = expiresAt - leewaySeconds <= nowSeconds
    val canRead: Boolean get() = scopes.any { it == "activity:read" || it == "activity:read_all" }
    val canWrite: Boolean get() = "activity:write" in scopes
}

enum class SyncDirection { DOWNLOAD, UPLOAD }

/** A Gains session tied to a Strava activity, in either direction. */
data class StravaLink(
    val sessionId: String,
    val activityId: Long,
    val direction: SyncDirection,
    /** ISO-8601 instant of the sync that created the link. */
    val syncedAt: String,
)

// ---- Wire types (Strava API v3). Unknown fields are ignored; absent ones default. ----

@Serializable
data class StravaAthlete(val id: Long, val firstname: String? = null, val lastname: String? = null) {
    val displayName: String get() = listOfNotNull(firstname, lastname).joinToString(" ").trim().ifBlank { "Strava athlete" }
}

@Serializable
data class StravaTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_at") val expiresAt: Long,
    val athlete: StravaAthlete? = null,
)

/** A summary activity as returned by `GET /athlete/activities` or `POST /activities`. */
@Serializable
data class StravaActivity(
    val id: Long,
    val name: String = "",
    @SerialName("sport_type") val sportType: String = "",
    /** Legacy field, still filled for old activities; used when [sportType] is missing. */
    val type: String? = null,
    /** "2026-09-05T07:12:34Z": the athlete's local wall-clock time, despite the Z. */
    @SerialName("start_date_local") val startDateLocal: String,
    @SerialName("start_date") val startDate: String? = null,
    @SerialName("elapsed_time") val elapsedTime: Int = 0,
    @SerialName("moving_time") val movingTime: Int = 0,
    /** Metres. */
    val distance: Double = 0.0,
    @SerialName("total_elevation_gain") val elevationGain: Double = 0.0,
    @SerialName("average_heartrate") val averageHeartrate: Double? = null,
    @SerialName("max_heartrate") val maxHeartrate: Double? = null,
    val trainer: Boolean = false,
    val manual: Boolean = false,
    val description: String? = null,
) {
    val sport: String get() = sportType.ifBlank { type ?: "" }
}

/** What Gains sends to `POST /activities` to create a manual activity. */
data class NewStravaActivity(
    val name: String,
    val sportType: String,
    /** "2026-09-05T18:30:00Z", read by Strava as local time. */
    val startDateLocal: String,
    val elapsedSeconds: Int,
    val description: String,
    val distanceMetres: Double? = null,
    val trainer: Boolean = false,
)

/** Outcome of one download pass. */
data class SyncReport(
    val imported: Int,
    /** Activities already tied to a session (downloaded before, or uploaded by Gains). */
    val alreadyLinked: Int,
    /** Sport type -> count of activities left out (weight training, or unreadable dates). */
    val skipped: Map<String, Int>,
    val fetched: Int,
) {
    val skippedCount: Int get() = skipped.values.sum()
}

/** Outcome of a bulk upload; [error] is set when it stopped early, usually at Strava's rate limit. */
data class UploadReport(val uploaded: Int, val remaining: Int, val error: String? = null)
