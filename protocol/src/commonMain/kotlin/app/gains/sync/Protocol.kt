package app.gains.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * The wire format between the app and the sync server, compiled into both. Every route's body
 * is one of these classes as JSON; docs/sync.md has the routes. Fields are added with defaults so
 * that an older client and a newer server (or the other way round) keep understanding each other.
 */

/** The JSON both ends use: unknown fields are skipped so either side can be ahead of the other. */
val SyncJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

/** What the routes agree on besides the bodies. */
object SyncProtocol {
    /** The most documents one pull returns: the server's cap on `limit`, and the client's default. */
    const val PAGE = 500

    /** A blob upload's `updatedAt`, in a header because the body is the bytes. */
    const val HEADER_UPDATED_AT = "X-Updated-At"
}

/**
 * One synced thing: a session, a program, a body weight, a setting. [payload] is JSON of the
 * class for [kind] in Documents.kt; empty when [deleted]. [updatedAt] is the client's UTC clock
 * when it changed (last writer wins by it); [seq] is set by the server and is the pull cursor.
 */
@Serializable
data class SyncDocument(
    val kind: String,
    val id: String,
    val updatedAt: String,
    val deleted: Boolean = false,
    val payload: String = "",
    val seq: Long = 0,
)

@Serializable
data class PushRequest(val documents: List<SyncDocument>)

/** [accepted] is false when the server held a newer version; the next pull brings it. */
@Serializable
data class PushResult(val kind: String, val id: String, val seq: Long, val accepted: Boolean)

@Serializable
data class PushResponse(val results: List<PushResult>)

/** Documents with `seq > since` in `seq` order; [cursor] is the last one's, [more] whether to ask again. */
@Serializable
data class PullResponse(val documents: List<SyncDocument>, val cursor: Long, val more: Boolean)

/** The answer to a blob upload: the seq of the feed document it wrote. */
@Serializable
data class BlobResponse(val seq: Long)

/**
 * A sign-in: the provider's identity token (a Google ID token or an Apple identity token) and, for
 * Apple, the name it handed the app alongside, since Apple sends it only the first time, and the
 * authorization code, which the server exchanges for a refresh token so that deleting the account
 * can revoke it. A server from before the code skips the field.
 */
@Serializable
data class SignInRequest(val token: String, val name: String? = null, val authorizationCode: String? = null)

@Serializable
data class SignInResponse(val token: String, val user: UserInfo)

/**
 * An email and password sign-up (`POST /auth/password/signup`). The server stores the address and
 * a hash of the password, mails a confirmation link and answers 204 whether or not the address is
 * already known; the account exists once the link is used.
 */
@Serializable
data class PasswordSignUpRequest(val email: String, val password: String)

/** An email and password sign-in (`POST /auth/password/signin`) → [SignInResponse]; 401 when wrong, 403 while the address is unconfirmed. */
@Serializable
data class PasswordSignInRequest(val email: String, val password: String)

/** An address on its own: a password reset request (`POST /auth/password/reset-request`), answered 204 either way. */
@Serializable
data class EmailRequest(val email: String)

/** The token from a mailed link, posted by the site's `/verify` page (`POST /auth/password/verify`). */
@Serializable
data class EmailTokenRequest(val token: String)

/** The token from a reset link and the new password, posted by the site's `/reset` page (`POST /auth/password/reset`). */
@Serializable
data class PasswordResetRequest(val token: String, val password: String)

/**
 * The end of a web sign-in (Sign in with Apple on Android and the desktop): the one-time code the
 * server put on the app's callback URL, traded for the same [SignInResponse] as a native sign-in.
 * The callback carries a code rather than the token itself, because URLs end up in browser history.
 */
@Serializable
data class ExchangeRequest(val code: String)

@Serializable
data class UserInfo(
    val id: Long,
    val email: String? = null,
    val name: String? = null,
    /** "google", "apple": which identities are linked to this user. */
    val providers: List<String> = emptyList(),
)

@Serializable
data class ErrorResponse(val error: String)

/** A request to join the launch guest list, sent by the form on gains.gerra.sh, not by the app. */
@Serializable
data class GuestListRequest(val email: String)
