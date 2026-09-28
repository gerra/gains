package app.gains.server

import com.auth0.jwk.Jwk
import com.auth0.jwk.JwkProvider
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Base64

/**
 * A provider of our own: an RSA key pair whose public half is served as a JWKS the way Google's
 * and Apple's are, and a way to sign identity tokens with the private half. The verifier under
 * test is the real one; only the key set is ours.
 */
class FakeProvider(val issuer: String, val keyId: String = "test-key") {
    private val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val public = pair.public as RSAPublicKey
    private val private = pair.private as RSAPrivateKey

    val jwks: JwkProvider = JwkProvider { id ->
        require(id == keyId) { "no key $id" }
        val b64 = Base64.getUrlEncoder().withoutPadding()
        Jwk(keyId, "RSA", "RS256", "sig", emptyList(), null, emptyList(), null, mapOf(
            "n" to b64.encodeToString(public.modulus.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()),
            "e" to b64.encodeToString(public.publicExponent.toByteArray()),
        ))
    }

    /** [emailVerified] is sent as given: Google's boolean, or Apple's string `"true"`; null leaves the claim out. */
    fun token(
        subject: String,
        audience: String,
        email: String? = null,
        name: String? = null,
        expired: Boolean = false,
        keyId: String = this.keyId,
        emailVerified: Any? = null,
        nonce: String? = null,
    ): String {
        val now = Instant.now()
        return JWT.create()
            .withKeyId(keyId)
            .withIssuer(issuer)
            .withSubject(subject)
            .withAudience(audience)
            .withIssuedAt(now)
            .withExpiresAt(if (expired) now.minusSeconds(3600) else now.plusSeconds(600))
            .apply {
                email?.let { withClaim("email", it) }
                name?.let { withClaim("name", it) }
                nonce?.let { withClaim("nonce", it) }
                when (emailVerified) {
                    null -> Unit
                    is Boolean -> withClaim("email_verified", emailVerified)
                    else -> withClaim("email_verified", emailVerified.toString())
                }
            }
            .sign(Algorithm.RSA256(public, private))
    }
}

const val GOOGLE_AUDIENCE = "123.apps.googleusercontent.com"
const val APPLE_AUDIENCE = "app.gains.Gains"
const val APPLE_SERVICES_ID = "app.gains.Gains.web"

/**
 * Apple's token endpoints as the tests want them: each code in [codes] is exchanged for
 * `refresh-<code>` issued to the subject it maps to, and every call is recorded. [onRevoke] runs
 * inside each revoke, before it succeeds or fails, so a test can look at the database then.
 */
class FakeAppleTokens(
    val codes: Map<String, String> = emptyMap(),
    var revokeFails: Boolean = false,
    var onRevoke: () -> Unit = {},
) : AppleTokens {
    val exchanged = mutableListOf<Pair<String, String>>()
    val revoked = mutableListOf<Pair<String, String>>()

    override val enabled = true

    override fun exchange(code: String, clientId: String): AppleGrant {
        exchanged += code to clientId
        val subject = codes[code] ?: throw AppleTokenException("/auth/token answered 400: {\"error\":\"invalid_grant\"}")
        return AppleGrant("refresh-$code", subject)
    }

    override fun revoke(refreshToken: String, clientId: String) {
        onRevoke()
        if (revokeFails) throw AppleTokenException("appleid.apple.com unreachable")
        revoked += refreshToken to clientId
    }
}

/** The mails the server sent, with the token each one's link carries, instead of an inbox. */
class FakeMailer(var failing: Boolean = false) : Mailer {
    val sent = mutableListOf<Mail>()
    override val enabled = true

    override fun send(mail: Mail) {
        if (failing) throw MailException("smtp.example refused the connection")
        sent += mail
    }

    /** The token in the last mail's link, and the page it points at (`verify` or `reset`). */
    fun lastLink(): Pair<String, String> {
        val match = Regex("https://site\\.example/(verify|reset)\\?token=([A-Za-z0-9_-]+)").find(sent.last().text)
            ?: error("no link in: ${sent.last().text}")
        return match.groupValues[1] to match.groupValues[2]
    }
}

/** Argon2id at the cheapest settings, so a test that signs in many times stays quick; the format is the same. */
val cheapHasher = PasswordHasher(memoryKb = 256, iterations = 1)

/** Email accounts over [mailer], with links to `https://site.example`, and the limits [perEmail] tries per address. */
fun testPasswords(store: Store, mailer: FakeMailer, perEmail: Int = 100, clock: () -> java.time.Instant = java.time.Instant::now) = PasswordSignIn(
    store, mailer, siteUrl = "https://site.example", hasher = cheapHasher, clock = clock,
    perEmail = RateLimit(perEmail, java.time.Duration.ofMinutes(15), clock),
    perIp = RateLimit(perEmail * 4, java.time.Duration.ofMinutes(15), clock),
)

/** A server with an in-memory database and both providers backed by [google] and [apple]; [apple] accepts the bundle id and the Services ID. */
fun testServices(
    google: FakeProvider,
    apple: FakeProvider,
    appleEnabled: Boolean = true,
    appleTokens: AppleTokens = NoAppleTokens,
    appleWeb: AppleWebSignIn? = null,
    passwords: ((Store) -> PasswordSignIn)? = null,
    passkeys: ((Store) -> Passkeys)? = null,
) = Store.open(null).let { store -> Services(
    store = store,
    tokens = SessionTokens("a-test-secret-that-is-long-enough-for-hmac-256"),
    verifier = JwksIdentityVerifier(
        googleClientIds = listOf(GOOGLE_AUDIENCE),
        appleClientIds = if (appleEnabled) listOf(APPLE_AUDIENCE, APPLE_SERVICES_ID) else emptyList(),
        googleKeys = google.jwks,
        appleKeys = apple.jwks,
    ),
    appleTokens = appleTokens,
    appleWeb = appleWeb,
    passwords = passwords?.invoke(store),
    passkeys = passkeys?.invoke(store),
    maxBlobBytes = 1024,
) }
