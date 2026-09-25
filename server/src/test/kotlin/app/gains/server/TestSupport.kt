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

/** A server with an in-memory database and both providers backed by [google] and [apple]. */
fun testServices(google: FakeProvider, apple: FakeProvider, appleEnabled: Boolean = true) = Services(
    store = Store.open(null),
    tokens = SessionTokens("a-test-secret-that-is-long-enough-for-hmac-256"),
    verifier = JwksIdentityVerifier(
        googleClientIds = listOf(GOOGLE_AUDIENCE),
        appleClientIds = if (appleEnabled) listOf(APPLE_AUDIENCE) else emptyList(),
        googleKeys = google.jwks,
        appleKeys = apple.jwks,
    ),
    maxBlobBytes = 1024,
)
