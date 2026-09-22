package app.gains.sync

import app.gains.auth.AccountKind
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.KSerializer

/** The server said no: [status] is the HTTP status, [message] what its body said, if anything. */
class SyncException(val status: Int, message: String) : Exception("$status: $message") {
    val unauthorized: Boolean get() = status == 401
}

/**
 * The sync server's routes as calls, over a Ktor [HttpClient]. [baseUrl] has no trailing slash;
 * the empty string means the client's own default host (what a test application gives).
 * [token] is asked for on every request, so a refreshed token is used from the next call on.
 */
class SyncApi(
    private val client: HttpClient,
    private val baseUrl: String,
    private val token: suspend () -> String?,
) {
    suspend fun signIn(provider: AccountKind, idToken: String, name: String?): SignInResponse {
        val route = when (provider) {
            AccountKind.GOOGLE -> "google"
            AccountKind.APPLE -> "apple"
            AccountKind.GUEST -> throw IllegalArgumentException("A guest does not sign in")
        }
        val response = client.post("$baseUrl/auth/$route") {
            contentType(ContentType.Application.Json)
            setBody(SyncJson.encodeToString(SignInRequest.serializer(), SignInRequest(idToken, name)))
        }
        return response.read(SignInResponse.serializer())
    }

    suspend fun refresh(): SignInResponse = client.post("$baseUrl/auth/refresh") { bearer() }.read(SignInResponse.serializer())

    suspend fun me(): UserInfo = client.get("$baseUrl/auth/me") { bearer() }.read(UserInfo.serializer())

    suspend fun deleteAccount() {
        client.delete("$baseUrl/auth/account") { bearer() }.check()
    }

    suspend fun push(documents: List<SyncDocument>): PushResponse = client.post("$baseUrl/sync/push") {
        bearer()
        contentType(ContentType.Application.Json)
        setBody(SyncJson.encodeToString(PushRequest.serializer(), PushRequest(documents)))
    }.read(PushResponse.serializer())

    suspend fun pull(since: Long, limit: Int = PAGE): PullResponse = client.get("$baseUrl/sync/pull") {
        bearer()
        parameter("since", since)
        parameter("limit", limit)
    }.read(PullResponse.serializer())

    suspend fun putBlob(kind: String, id: String, updatedAt: String, bytes: ByteArray): BlobResponse = client.put("$baseUrl/sync/blobs/$kind/$id") {
        bearer()
        header(HEADER_UPDATED_AT, updatedAt)
        contentType(ContentType.Application.OctetStream)
        setBody(bytes)
    }.read(BlobResponse.serializer())

    /** The bytes, or null when the server has none (deleted since the feed said so). */
    suspend fun getBlob(kind: String, id: String): ByteArray? {
        val response = client.get("$baseUrl/sync/blobs/$kind/$id") { bearer() }
        if (response.status == HttpStatusCode.NotFound) return null
        response.check()
        return response.bodyAsBytes()
    }

    private suspend fun io.ktor.client.request.HttpRequestBuilder.bearer() {
        token()?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    }

    private suspend fun HttpResponse.check() {
        if (status.value !in 200..299) {
            val text = bodyAsText()
            val message = runCatching { SyncJson.decodeFromString(ErrorResponse.serializer(), text).error }.getOrDefault(text)
            throw SyncException(status.value, message)
        }
    }

    private suspend fun <T> HttpResponse.read(serializer: KSerializer<T>): T {
        check()
        return SyncJson.decodeFromString(serializer, bodyAsText())
    }

    companion object {
        const val PAGE = 500
        const val HEADER_UPDATED_AT = "X-Updated-At"
    }
}
