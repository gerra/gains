package app.gains.server

import app.gains.sync.BlobResponse
import app.gains.sync.EmailRequest
import app.gains.sync.EmailTokenRequest
import app.gains.sync.ErrorResponse
import app.gains.sync.ExchangeRequest
import app.gains.sync.GuestListRequest
import app.gains.sync.PasswordResetRequest
import app.gains.sync.PasswordSignInRequest
import app.gains.sync.PasswordSignUpRequest
import app.gains.sync.PhotoDoc
import app.gains.sync.PullResponse
import app.gains.sync.PushRequest
import app.gains.sync.PushResponse
import app.gains.sync.SignInRequest
import app.gains.sync.SignInResponse
import app.gains.sync.SyncJson
import app.gains.sync.SyncKinds
import app.gains.sync.SyncProtocol
import app.gains.sync.UserInfo
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
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
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.utils.io.toByteArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    /** Trades Apple sign-in codes for refresh tokens and revokes them when an account is deleted. */
    val appleTokens: AppleTokens = NoAppleTokens,
    /** Sign in with Apple through Apple's web page, for Android and the desktop; null when no Services ID is set. */
    val appleWeb: AppleWebSignIn? = null,
    /** Email and password accounts; null when the server has no mail account to send their links from. */
    val passwords: PasswordSignIn? = null,
    /** The largest blob accepted, in bytes. A workout photo is a few hundred kilobytes. */
    val maxBlobBytes: Int = 8 * 1024 * 1024,
    /** The most addresses the guest list holds; past it, joining answers 503. */
    val maxGuestList: Long = 10_000,
    /**
     * The most feed rows one account may hold, tombstones and photos included; a push past it
     * answers 413. Nobody logging workouts gets near it (a workout a day with a photo and a weigh-in
     * is about a thousand rows a year); it bounds what a stolen token or a runaway client can make one account cost.
     */
    val maxDocumentsPerUser: Long = 200_000,
    /** The most photo bytes one account may store; an upload past it answers 507. About 7,000 photos. */
    val maxBlobBytesPerUser: Long = 2L * 1024 * 1024 * 1024,
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
        exception<TooManyDocumentsException> { call, e -> call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse(e.message!!)) }
        exception<StorageFullException> { call, e -> call.respond(HttpStatusCode.InsufficientStorage, ErrorResponse(e.message!!)) }
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

    /**
     * The user a bearer token stands for, or a 401. The only way a protected route learns who is
     * calling: the token is stateless and lives 30 days, so its signature alone would let a copy
     * of it keep writing under an account that was deleted. Loading the row on every request
     * (one primary-key lookup) makes a deleted account's tokens worthless at once, on every
     * device. Deliberately not cached: a cache would reopen that window.
     */
    fun ApplicationCall.caller(): UserInfo {
        val header = request.headers["Authorization"] ?: throw HttpError(HttpStatusCode.Unauthorized, "no token")
        val token = header.removePrefix("Bearer ").trim()
        val userId = tokens.userId(token) ?: throw HttpError(HttpStatusCode.Unauthorized, "token expired or invalid")
        return store.user(userId) ?: throw HttpError(HttpStatusCode.Unauthorized, "no such user")
    }

    /**
     * Trades the code an Apple sign-in came with for a refresh token and keeps it on the identity,
     * so that deleting the account can revoke it. Anything going wrong is logged and the sign-in
     * goes ahead: the person is signed in either way, and only the revocation is lost.
     */
    suspend fun keepAppleRefreshToken(identity: VerifiedIdentity, code: String) {
        val appleTokens = services.appleTokens
        val clientId = identity.audience
        if (!appleTokens.enabled || clientId == null || code.isBlank()) return
        try {
            val grant = withContext(Dispatchers.IO) { appleTokens.exchange(code, clientId) }
            // A code from someone else's sign-in would let deleting this account revoke theirs.
            if (grant.subject != identity.subject) {
                log.warn("apple code exchange: the code belongs to another subject, not stored")
                return
            }
            store.setRefreshToken(Providers.APPLE, identity.subject, grant.refreshToken, clientId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("apple code exchange failed: {}", e.message)
        }
    }

    /**
     * Revokes the user's Apple refresh tokens, which removes Gains from their Apple ID. Called
     * before the rows go, and never stops them going: Apple being unreachable must not keep
     * anyone's data on our server.
     */
    suspend fun revokeAppleTokens(userId: Long) {
        val stored = store.refreshTokens(userId, Providers.APPLE)
        if (stored.isNotEmpty() && !services.appleTokens.enabled) {
            log.warn("apple revoke skipped for user {}: no Sign in with Apple key configured", userId)
            return
        }
        for ((refreshToken, clientId) in stored) {
            try {
                withContext(Dispatchers.IO) { services.appleTokens.revoke(refreshToken, clientId) }
                log.info("apple token revoked: user {}", userId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("apple revoke failed for user {}: {}", userId, e.message)
            }
        }
    }

    /** Who is calling, for the rate limits: nginx's header in front of the server, the socket's peer otherwise. */
    fun ApplicationCall.callerIp(): String = request.headers["X-Real-IP"]?.trim()?.ifEmpty { null } ?: request.local.remoteHost

    /** Sends the browser on to [url] with a 303, so the post Apple made becomes a plain GET. */
    suspend fun ApplicationCall.seeOther(url: String) {
        response.headers.append(HttpHeaders.Location, url)
        respond(HttpStatusCode.SeeOther)
    }

    routing {
        get("/health") { call.respond(mapOf("status" to "ok")) }

        for (provider in listOf(Providers.GOOGLE, Providers.APPLE)) {
            post("/auth/$provider") {
                if (!services.verifier.enabled(provider)) throw HttpError(HttpStatusCode.ServiceUnavailable, "$provider sign-in is not configured")
                val body = call.receive<SignInRequest>()
                val identity = services.verifier.verify(provider, body.token)
                val user = store.signIn(provider, identity.subject, identity.email, identity.emailVerified, identity.name ?: body.name?.takeIf { it.isNotBlank() })
                if (provider == Providers.APPLE) body.authorizationCode?.let { keepAppleRefreshToken(identity, it) }
                log.info("sign-in: user {} via {}", user.id, provider)
                call.respond(SignInResponse(tokens.issue(user.id), user))
            }
        }

        // Sign in with Apple for Android and the desktop (AppleWebSignIn): start, Apple's post back,
        // then the app trades the one-time code for our token.
        get("/auth/apple/start") {
            val web = services.appleWeb ?: throw HttpError(HttpStatusCode.ServiceUnavailable, "apple web sign-in is not configured")
            val redirect = call.request.queryParameters["redirect"].orEmpty()
            val state = call.request.queryParameters["state"].orEmpty()
            if (redirect.length > 2048 || !AppleWebSignIn.isAllowedAppCallback(redirect)) throw HttpError(HttpStatusCode.BadRequest, "redirect is not an app callback")
            if (state.isBlank() || state.length > 512) throw HttpError(HttpStatusCode.BadRequest, "state missing or too long")
            val authorizeUrl = web.start(redirect, state) ?: throw HttpError(HttpStatusCode.ServiceUnavailable, "too many sign-ins in progress")
            call.respondRedirect(authorizeUrl)
        }

        post("/auth/apple/callback") {
            val web = services.appleWeb ?: throw HttpError(HttpStatusCode.ServiceUnavailable, "apple web sign-in is not configured")
            val form = call.receiveParameters()
            // Without a state we know of there is no app to send the person back to, so this one
            // answers in the browser; everything after it goes back to the app.
            val pending = web.finish(form["state"].orEmpty())
            if (pending == null) {
                call.respondText("This sign-in has expired. Go back to Gains and start again.", status = HttpStatusCode.BadRequest)
                return@post
            }
            suspend fun backToApp(vararg params: Pair<String, String>) =
                call.seeOther(AppleWebSignIn.withQuery(pending.appRedirect, *params, "state" to pending.appState))

            form["error"]?.let { error ->
                // Closing Apple's page is a cancel, not a failure: the app shows no error for it.
                log.info("apple web sign-in: {}", error)
                return@post backToApp("error" to if (error == "user_cancelled_authorize") "cancelled" else "failed")
            }
            val identity = try {
                services.verifier.verify(Providers.APPLE, form["id_token"].orEmpty())
            } catch (e: InvalidTokenException) {
                log.warn("apple web sign-in: {}", e.message)
                return@post backToApp("error" to "failed")
            }
            // The token must come from this sign-in: issued to the Services ID, with the nonce
            // start made. Otherwise a token lifted from elsewhere could be replayed here.
            if (identity.audience != web.servicesId || identity.nonce != pending.nonce) {
                log.warn("apple web sign-in: token for another client or sign-in")
                return@post backToApp("error" to "failed")
            }
            val name = AppleWebSignIn.nameFromUserJson(form["user"])
            val user = store.signIn(Providers.APPLE, identity.subject, identity.email, identity.emailVerified, identity.name ?: name)
            form["code"]?.let { keepAppleRefreshToken(identity, it) }
            log.info("sign-in: user {} via apple web", user.id)
            backToApp("code" to web.issueCode(user.id))
        }

        post("/auth/exchange") {
            val web = services.appleWeb ?: throw HttpError(HttpStatusCode.ServiceUnavailable, "apple web sign-in is not configured")
            val code = call.receive<ExchangeRequest>().code
            val userId = web.redeem(code) ?: throw HttpError(HttpStatusCode.Unauthorized, "code expired or already used")
            val user = store.user(userId) ?: throw HttpError(HttpStatusCode.Unauthorized, "no such user")
            call.respond(SignInResponse(tokens.issue(userId), user))
        }

        // Email and password accounts (PasswordSignIn). The site's /verify and /reset pages post
        // the mailed tokens to the two token routes; the app calls the other three.
        fun passwords() = services.passwords ?: throw HttpError(HttpStatusCode.ServiceUnavailable, "email sign-in is not configured")

        post("/auth/password/signup") {
            val body = call.receive<PasswordSignUpRequest>()
            withContext(Dispatchers.IO) { passwords().signUp(body.email, body.password, call.callerIp()) }
            call.respond(HttpStatusCode.NoContent)
        }

        post("/auth/password/verify") {
            val body = call.receive<EmailTokenRequest>()
            passwords().verify(body.token)
            call.respond(HttpStatusCode.NoContent)
        }

        post("/auth/password/signin") {
            val body = call.receive<PasswordSignInRequest>()
            val user = withContext(Dispatchers.IO) { passwords().signIn(body.email, body.password, call.callerIp()) }
            log.info("sign-in: user {} via password", user.id)
            call.respond(SignInResponse(tokens.issue(user.id), user))
        }

        post("/auth/password/reset-request") {
            val body = call.receive<EmailRequest>()
            withContext(Dispatchers.IO) { passwords().requestReset(body.email, call.callerIp()) }
            call.respond(HttpStatusCode.NoContent)
        }

        post("/auth/password/reset") {
            val body = call.receive<PasswordResetRequest>()
            withContext(Dispatchers.IO) { passwords().reset(body.token, body.password) }
            call.respond(HttpStatusCode.NoContent)
        }

        post("/auth/refresh") {
            val user = call.caller()
            call.respond(SignInResponse(tokens.issue(user.id), user))
        }

        get("/auth/me") {
            call.respond(call.caller())
        }

        delete("/auth/account") {
            val userId = call.caller().id
            revokeAppleTokens(userId)
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
            val userId = call.caller().id
            val body = call.receive<PushRequest>()
            for (doc in body.documents) {
                if (doc.kind !in SyncKinds.all) throw HttpError(HttpStatusCode.BadRequest, "unknown kind ${doc.kind}")
                if (doc.id.isBlank() || doc.updatedAt.isBlank()) throw HttpError(HttpStatusCode.BadRequest, "document without id or updatedAt")
                // The bytes of a photo come through the blob route; the feed never carries them.
                if (doc.kind == SyncKinds.SESSION_PHOTO && !doc.deleted) throw HttpError(HttpStatusCode.BadRequest, "a photo is uploaded with PUT /sync/blobs")
            }
            call.respond(PushResponse(store.push(userId, body.documents, services.maxDocumentsPerUser)))
        }

        get("/sync/pull") {
            val userId = call.caller().id
            val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
            val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: SyncProtocol.PAGE).coerceIn(1, SyncProtocol.PAGE)
            val (documents, more) = store.pull(userId, since, limit)
            call.respond(PullResponse(documents, cursor = documents.lastOrNull()?.seq ?: since, more = more))
        }

        put("/sync/blobs/{kind}/{id}") {
            val userId = call.caller().id
            val kind = call.parameters["kind"]!!
            val id = call.parameters["id"]!!
            if (kind != SyncKinds.SESSION_PHOTO) throw HttpError(HttpStatusCode.BadRequest, "no blobs of kind $kind")
            val updatedAt = call.request.headers[SyncProtocol.HEADER_UPDATED_AT] ?: throw HttpError(HttpStatusCode.BadRequest, "${SyncProtocol.HEADER_UPDATED_AT} header missing")
            val bytes = call.receiveChannel().toByteArray()
            if (bytes.isEmpty()) throw HttpError(HttpStatusCode.BadRequest, "empty blob")
            if (bytes.size > services.maxBlobBytes) throw HttpError(HttpStatusCode.PayloadTooLarge, "blob over ${services.maxBlobBytes} bytes")
            val payload = SyncJson.encodeToString(PhotoDoc.serializer(), PhotoDoc(sha256(bytes), bytes.size))
            val seq = store.putBlob(userId, kind, id, updatedAt, bytes, payload, services.maxBlobBytesPerUser, services.maxDocumentsPerUser)
                ?: throw HttpError(HttpStatusCode.Conflict, "a newer version is stored")
            call.respond(BlobResponse(seq))
        }

        get("/sync/blobs/{kind}/{id}") {
            val userId = call.caller().id
            val bytes = store.blob(userId, call.parameters["kind"]!!, call.parameters["id"]!!)
                ?: throw HttpError(HttpStatusCode.NotFound, "no such blob")
            call.respondBytes(bytes, ContentType.Application.OctetStream)
        }
    }
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
