package app.gains.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLEncoder
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * Sign in with Apple for devices without Apple's native sheet: Android and the desktop
 * (docs/sync.md, "Signing in"). Apple's web flow only redirects to an HTTPS URL registered with it,
 * so the server receives the redirect and hands the app a one-time code, never our bearer token in
 * a URL:
 *
 * 1. The app opens `GET /auth/apple/start?redirect=<app callback>&state=<app state>`. [start]
 *    remembers the app's callback and state under a server state and a nonce of its own, and the
 *    route redirects to Apple.
 * 2. Apple posts the result to [callbackUrl]. [finish] takes the server state back (once), the
 *    route checks the identity token, whose nonce must be the one [start] made, signs the person
 *    in and redirects to the app's callback with [issueCode]'s code and the app's state.
 * 3. The app posts the code to `POST /auth/exchange`; [redeem] gives the user id once.
 *
 * Both maps live in memory: one server process, and a restart only costs a sign-in in flight.
 * Each is capped, so hitting `start` in a loop can't grow them without bound.
 */
class AppleWebSignIn(
    /** The Services ID: the `client_id` of the web flow and the audience of its identity tokens. */
    val servicesId: String,
    /** Where Apple posts back, as registered on the Services ID: `https://api.gains.gerra.sh/auth/apple/callback`. */
    val callbackUrl: String,
    private val clock: () -> Instant = Instant::now,
    private val maxPending: Int = 10_000,
) {
    /** A sign-in between `start` and Apple's post back: where to send the person and what to check. */
    class Pending(val appRedirect: String, val appState: String, val nonce: String, val expiresAt: Instant)

    private class Grant(val userId: Long, val expiresAt: Instant)

    private val random = SecureRandom()
    private val pending = HashMap<String, Pending>()
    private val grants = HashMap<String, Grant>()

    /** Apple's authorization URL for a new sign-in that ends at [appRedirect]; null when too many are in flight. */
    @Synchronized
    fun start(appRedirect: String, appState: String): String? {
        val now = clock()
        pending.values.removeIf { it.expiresAt <= now }
        if (pending.size >= maxPending) return null
        val state = randomToken()
        val nonce = randomToken()
        pending[state] = Pending(appRedirect, appState, nonce, now + STATE_TTL)
        return withQuery(
            AUTHORIZE_URL,
            // Both: the code for item 6's refresh token, the identity token to sign in with.
            "response_type" to "code id_token",
            // Apple insists on a form post when the scope asks for the name or the email.
            "response_mode" to "form_post",
            "client_id" to servicesId,
            "redirect_uri" to callbackUrl,
            "scope" to "name email",
            "state" to state,
            "nonce" to nonce,
        )
    }

    /** The sign-in [state] was started for, removed so it can't be finished twice; null when unknown or expired. */
    @Synchronized
    fun finish(state: String): Pending? = pending.remove(state)?.takeIf { it.expiresAt > clock() }

    /** A one-time code that [redeem] trades for [userId] within [CODE_TTL]. */
    @Synchronized
    fun issueCode(userId: Long): String {
        val now = clock()
        grants.values.removeIf { it.expiresAt <= now }
        val code = randomToken()
        grants[code] = Grant(userId, now + CODE_TTL)
        return code
    }

    /** The user [code] was issued for, once; null when unknown, already used or expired. */
    @Synchronized
    fun redeem(code: String): Long? = grants.remove(code)?.takeIf { it.expiresAt > clock() }?.userId

    private fun randomToken(): String = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    companion object {
        const val AUTHORIZE_URL = "https://appleid.apple.com/auth/authorize"

        /** Long enough to type a password and pass two-factor on Apple's page. */
        val STATE_TTL: Duration = Duration.ofMinutes(10)

        /** The app redeems the code the moment its callback opens. */
        val CODE_TTL: Duration = Duration.ofMinutes(1)

        /**
         * The app callbacks a sign-in may end at, besides the desktop's loopback: Android's App Link
         * (launch plan item 11), which only the app signed with our key can open.
         */
        val APP_CALLBACKS = listOf("https://gains.gerra.sh/auth/done")

        /**
         * Whether [url] may receive a one-time code: one of [APP_CALLBACKS] exactly, or
         * `http://127.0.0.1:<port>/<any path>` for the desktop's loopback listener (item 16).
         * Anything else could hand the code to a site of someone else's choosing.
         */
        fun isAllowedAppCallback(url: String): Boolean {
            if (url in APP_CALLBACKS) return true
            val uri = try {
                URI(url)
            } catch (e: Exception) {
                return false
            }
            return uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port in 1..65535 &&
                uri.rawUserInfo == null && uri.rawFragment == null
        }

        /**
         * The name in the `user` field Apple posts on the first authorization only:
         * `{"name":{"firstName":"…","lastName":"…"},"email":"…"}`. Null when it is absent or unreadable.
         */
        fun nameFromUserJson(json: String?): String? {
            if (json.isNullOrBlank()) return null
            val name = try {
                (Json.parseToJsonElement(json) as? JsonObject)?.get("name") as? JsonObject
            } catch (e: Exception) {
                null
            } ?: return null
            return listOf("firstName", "lastName")
                .mapNotNull { name[it]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
                .joinToString(" ")
                .ifEmpty { null }
        }

        /** [url] with [params] appended to its query, each percent-encoded (spaces as `%20`, which Apple wants). */
        fun withQuery(url: String, vararg params: Pair<String, String>): String {
            fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
            val query = params.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
            return url + (if ('?' in url) "&" else "?") + query
        }
    }
}
