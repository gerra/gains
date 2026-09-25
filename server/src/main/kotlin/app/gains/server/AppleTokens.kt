package app.gains.server

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.interfaces.ECPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * What Apple gives back for a sign-in's authorization code: the refresh token that revoking needs,
 * and the subject of the identity token that came with it. The route compares that subject with
 * the identity the person signed in as, so a code can only ever be stored on its own identity.
 */
data class AppleGrant(val refreshToken: String, val subject: String)

class AppleTokenException(message: String) : Exception(message)

/**
 * Apple's token endpoints, which the server needs for one thing: when someone deletes their
 * account, Apple asks apps that offer Sign in with Apple to revoke the person's tokens, which also
 * removes the app from their Apple ID. Revoking takes a refresh token, and the only way to get one
 * is to exchange the authorization code the app receives at sign-in, within five minutes and only
 * once (docs/sync.md, "Signing in"). An interface, like [IdentityVerifier], so tests never call Apple.
 */
interface AppleTokens {
    /** False when the server has no Sign in with Apple key: codes are then ignored and nothing is revoked. */
    val enabled: Boolean

    /** Trades a sign-in's [code] for a refresh token. [clientId] is the identity token's audience. Throws on any failure. */
    fun exchange(code: String, clientId: String): AppleGrant

    /** Revokes [refreshToken], issued to [clientId]. Throws on any failure. */
    fun revoke(refreshToken: String, clientId: String)
}

/** A server without the `APPLE_KEY_ID`, `APPLE_TEAM_ID` and `APPLE_PRIVATE_KEY` secrets. */
object NoAppleTokens : AppleTokens {
    override val enabled = false
    override fun exchange(code: String, clientId: String): AppleGrant = throw AppleTokenException("no Sign in with Apple key configured")
    override fun revoke(refreshToken: String, clientId: String) = throw AppleTokenException("no Sign in with Apple key configured")
}

/**
 * The real thing: `appleid.apple.com/auth/token` and `/auth/revoke`, over the JDK's HTTP client so
 * the server needs no client library. Each call authenticates with a client secret that is itself
 * a short-lived ES256 JWT signed with the Sign in with Apple key ([clientSecret]).
 * [privateKeyPem] is the `.p8` file's contents; a key that doesn't parse fails here, at start.
 */
class AppleTokenClient(
    private val keyId: String,
    private val teamId: String,
    privateKeyPem: String,
    private val baseUrl: String = APPLE_BASE_URL,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
    private val clock: () -> Instant = Instant::now,
) : AppleTokens {
    private val algorithm = Algorithm.ECDSA256(null, parsePrivateKey(privateKeyPem))

    override val enabled = true

    /**
     * The `client_secret` Apple wants on both endpoints: issued by our team, about [clientId] (the
     * bundle id for the native flow, the Services ID for the web one), for Apple, and good for a
     * few minutes only, since a new one costs nothing.
     */
    fun clientSecret(clientId: String): String {
        val now = clock()
        return JWT.create()
            .withKeyId(keyId)
            .withIssuer(teamId)
            .withSubject(clientId)
            .withAudience(APPLE_BASE_URL)
            .withIssuedAt(now)
            .withExpiresAt(now.plusSeconds(SECRET_TTL_SECONDS))
            .sign(algorithm)
    }

    override fun exchange(code: String, clientId: String): AppleGrant {
        val body = post(
            "/auth/token",
            "client_id" to clientId,
            "client_secret" to clientSecret(clientId),
            "code" to code,
            "grant_type" to "authorization_code",
        )
        val json = Json.parseToJsonElement(body).jsonObject
        val refreshToken = json["refresh_token"]?.jsonPrimitive?.contentOrNull
            ?: throw AppleTokenException("Apple's answer has no refresh_token")
        // Read, not verified: it came straight from Apple over TLS in answer to our signed request.
        val idToken = json["id_token"]?.jsonPrimitive?.contentOrNull
            ?: throw AppleTokenException("Apple's answer has no id_token")
        val subject = JWT.decode(idToken).subject ?: throw AppleTokenException("Apple's id_token has no subject")
        return AppleGrant(refreshToken, subject)
    }

    override fun revoke(refreshToken: String, clientId: String) {
        post(
            "/auth/revoke",
            "client_id" to clientId,
            "client_secret" to clientSecret(clientId),
            "token" to refreshToken,
            "token_type_hint" to "refresh_token",
        )
    }

    /** A form post; anything but a 2xx throws with Apple's error (`invalid_grant`, `invalid_client`), never the form. */
    private fun post(path: String, vararg form: Pair<String, String>): String {
        val body = form.joinToString("&") { (k, v) -> "${URLEncoder.encode(k, Charsets.UTF_8)}=${URLEncoder.encode(v, Charsets.UTF_8)}" }
        val request = HttpRequest.newBuilder(URI("$baseUrl$path"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw AppleTokenException("$path answered ${response.statusCode()}: ${response.body().take(200)}")
        }
        return response.body()
    }

    companion object {
        const val APPLE_BASE_URL = "https://appleid.apple.com"
        const val SECRET_TTL_SECONDS = 300L

        /** The EC key in a `.p8` file: PKCS #8, base64 between the BEGIN and END lines. */
        fun parsePrivateKey(pem: String): ECPrivateKey {
            val base64 = pem.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("-----") }
                .joinToString("")
            return KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64))) as ECPrivateKey
        }
    }
}
