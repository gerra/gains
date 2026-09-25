package app.gains.server

import app.gains.sync.BlobResponse
import app.gains.sync.ErrorResponse
import app.gains.sync.GuestListRequest
import app.gains.sync.PhotoDoc
import app.gains.sync.PullResponse
import app.gains.sync.PushRequest
import app.gains.sync.PushResponse
import app.gains.sync.SignInRequest
import app.gains.sync.SignInResponse
import app.gains.sync.SyncApi
import app.gains.sync.SyncJson
import app.gains.sync.SyncKinds
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.compression.minimumSize
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.utils.io.toByteArray
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory
import java.security.MessageDigest

/** A request that cannot be served, with the status it gets. Turned into an [ErrorResponse] by the status pages. */
class HttpError(val status: HttpStatusCode, message: String) : Exception(message)

/** Everything the server needs to answer a request. Built once in main, or by a test with fakes. */
class Services(
    val store: Store,
    val tokens: SessionTokens,
    val verifier: IdentityVerifier,
    /** The largest blob accepted, in bytes. A workout photo is a few hundred kilobytes. */
    val maxBlobBytes: Int = 8 * 1024 * 1024,
    /** The most addresses the guest list holds; past it, joining answers 503. */
    val maxGuestList: Long = 10_000,
)

/** What the guest list accepts as an email address: something@domain.tld, at most 254 characters, no spaces. */
private val EMAIL = Regex("[^@\\s]+@[^@\\s.]+(\\.[^@\\s.]+)+")

fun isEmailAddress(text: String): Boolean = text.length <= 254 && EMAIL.matches(text)

private val log = LoggerFactory.getLogger("app.gains.server")

/** The routes in docs/sync.md, "The protocol". */
fun Application.gainsServer(services: Services) {
    install(ContentNegotiation) { json(SyncJson) }
    install(Compression) { gzip { minimumSize(1024) } }
    install(CallLogging)
    install(StatusPages) {
        exception<HttpError> { call, e -> call.respond(e.status, ErrorResponse(e.message ?: e.status.description)) }
        exception<InvalidTokenException> { call, e -> call.respond(HttpStatusCode.Unauthorized, ErrorResponse(e.message ?: "invalid token")) }
        exception<SerializationException> { call, e -> call.respond(HttpStatusCode.BadRequest, ErrorResponse("malformed body: ${e.message}")) }
        exception<BadRequestException> { call, e -> call.respond(HttpStatusCode.BadRequest, ErrorResponse("malformed body: ${e.cause?.message ?: e.message}")) }
        exception<IllegalArgumentException> { call, e -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "bad request")) }
        exception<Throwable> { call, e ->
            log.error("unhandled", e)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("internal error"))
        }
    }

    val store = services.store
    val tokens = services.tokens

    /** The user a bearer token stands for, or a 401. */
    fun ApplicationCall.userId(): Long {
        val header = request.headers["Authorization"] ?: throw HttpError(HttpStatusCode.Unauthorized, "no token")
        val token = header.removePrefix("Bearer ").trim()
        return tokens.userId(token) ?: throw HttpError(HttpStatusCode.Unauthorized, "token expired or invalid")
    }

    routing {
        get("/health") { call.respond(mapOf("status" to "ok")) }

        for (provider in listOf(Providers.GOOGLE, Providers.APPLE)) {
            post("/auth/$provider") {
                if (!services.verifier.enabled(provider)) throw HttpError(HttpStatusCode.ServiceUnavailable, "$provider sign-in is not configured")
                val body = call.receive<SignInRequest>()
                val identity = services.verifier.verify(provider, body.token)
                val user = store.signIn(provider, identity.subject, identity.email, identity.emailVerified, identity.name ?: body.name?.takeIf { it.isNotBlank() })
                log.info("sign-in: user {} via {}", user.id, provider)
                call.respond(SignInResponse(tokens.issue(user.id), user))
            }
        }

        post("/auth/refresh") {
            val userId = call.userId()
            val user = store.user(userId) ?: throw HttpError(HttpStatusCode.Unauthorized, "no such user")
            call.respond(SignInResponse(tokens.issue(userId), user))
        }

        get("/auth/me") {
            val user = store.user(call.userId()) ?: throw HttpError(HttpStatusCode.Unauthorized, "no such user")
            call.respond(user)
        }

        delete("/auth/account") {
            val userId = call.userId()
            store.deleteUser(userId)
            log.info("account deleted: user {}", userId)
            call.respond(HttpStatusCode.NoContent)
        }

        // The launch guest list: the form on gains.gerra.sh posts here (its vhost proxies the
        // path). Answers 204 whether the address is new or already listed, so it tells no one
        // who else is on it.
        post("/guest-list") {
            val email = call.receive<GuestListRequest>().email.trim()
            if (!isEmailAddress(email)) throw HttpError(HttpStatusCode.BadRequest, "not an email address")
            if (!store.joinGuestList(email, services.maxGuestList)) throw HttpError(HttpStatusCode.ServiceUnavailable, "the guest list is full")
            call.respond(HttpStatusCode.NoContent)
        }

        post("/sync/push") {
            val userId = call.userId()
            val body = call.receive<PushRequest>()
            for (doc in body.documents) {
                if (doc.kind !in SyncKinds.all) throw HttpError(HttpStatusCode.BadRequest, "unknown kind ${doc.kind}")
                if (doc.id.isBlank() || doc.updatedAt.isBlank()) throw HttpError(HttpStatusCode.BadRequest, "document without id or updatedAt")
                // The bytes of a photo come through the blob route; the feed never carries them.
                if (doc.kind == SyncKinds.SESSION_PHOTO && !doc.deleted) throw HttpError(HttpStatusCode.BadRequest, "a photo is uploaded with PUT /sync/blobs")
            }
            call.respond(PushResponse(store.push(userId, body.documents)))
        }

        get("/sync/pull") {
            val userId = call.userId()
            val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
            val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: SyncApi.PAGE).coerceIn(1, SyncApi.PAGE)
            val (documents, more) = store.pull(userId, since, limit)
            call.respond(PullResponse(documents, cursor = documents.lastOrNull()?.seq ?: since, more = more))
        }

        put("/sync/blobs/{kind}/{id}") {
            val userId = call.userId()
            val kind = call.parameters["kind"]!!
            val id = call.parameters["id"]!!
            if (kind != SyncKinds.SESSION_PHOTO) throw HttpError(HttpStatusCode.BadRequest, "no blobs of kind $kind")
            val updatedAt = call.request.headers[SyncApi.HEADER_UPDATED_AT] ?: throw HttpError(HttpStatusCode.BadRequest, "${SyncApi.HEADER_UPDATED_AT} header missing")
            val bytes = call.receiveChannel().toByteArray()
            if (bytes.isEmpty()) throw HttpError(HttpStatusCode.BadRequest, "empty blob")
            if (bytes.size > services.maxBlobBytes) throw HttpError(HttpStatusCode.PayloadTooLarge, "blob over ${services.maxBlobBytes} bytes")
            val payload = SyncJson.encodeToString(PhotoDoc.serializer(), PhotoDoc(sha256(bytes), bytes.size))
            val seq = store.putBlob(userId, kind, id, updatedAt, bytes, payload)
                ?: throw HttpError(HttpStatusCode.Conflict, "a newer version is stored")
            call.respond(BlobResponse(seq))
        }

        get("/sync/blobs/{kind}/{id}") {
            val userId = call.userId()
            val bytes = store.blob(userId, call.parameters["kind"]!!, call.parameters["id"]!!)
                ?: throw HttpError(HttpStatusCode.NotFound, "no such blob")
            call.respondBytes(bytes, ContentType.Application.OctetStream)
        }
    }
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
