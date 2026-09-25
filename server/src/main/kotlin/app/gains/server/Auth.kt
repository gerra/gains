package app.gains.server

import com.auth0.jwk.JwkProvider
import com.auth0.jwk.JwkProviderBuilder
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import java.net.URL
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Who a provider says the person is: its stable subject, plus the email when it told us.
 * [emailVerified] is the provider's `email_verified` claim; only a verified email may join this
 * identity to another one ([Store.signIn]). [audience] is which of our client ids the token was
 * issued to, which is the client id Apple wants when the sign-in's code is exchanged ([AppleTokens]).
 */
data class VerifiedIdentity(
    val provider: String,
    val subject: String,
    val email: String?,
    val name: String?,
    val emailVerified: Boolean = false,
    val audience: String? = null,
)

class InvalidTokenException(message: String) : Exception(message)

/**
 * Checks a provider's identity token (docs/sync.md, "Signing in"): signature against the
 * provider's published keys, issuer, expiry, and that the audience is one of our client ids.
 * An interface so the tests can hand the server a key pair of their own.
 */
interface IdentityVerifier {
    fun verify(provider: String, token: String): VerifiedIdentity
    fun enabled(provider: String): Boolean
}

/** The real thing: Google's and Apple's JWKS, cached and rate limited by jwks-rsa. */
class JwksIdentityVerifier(
    private val googleClientIds: List<String>,
    private val appleClientIds: List<String>,
    googleKeys: JwkProvider = JwkProviderBuilder(URL(GOOGLE_JWKS)).cached(10, 24, TimeUnit.HOURS).rateLimited(10, 1, TimeUnit.MINUTES).build(),
    appleKeys: JwkProvider = JwkProviderBuilder(URL(APPLE_JWKS)).cached(10, 24, TimeUnit.HOURS).rateLimited(10, 1, TimeUnit.MINUTES).build(),
) : IdentityVerifier {
    private val providers = mapOf(
        Providers.GOOGLE to ProviderKeys(googleKeys, GOOGLE_ISSUERS, googleClientIds),
        Providers.APPLE to ProviderKeys(appleKeys, APPLE_ISSUERS, appleClientIds),
    )

    private class ProviderKeys(val keys: JwkProvider, val issuers: List<String>, val audiences: List<String>)

    override fun enabled(provider: String): Boolean = providers[provider]?.audiences?.isNotEmpty() == true

    override fun verify(provider: String, token: String): VerifiedIdentity {
        val keys = providers[provider] ?: throw InvalidTokenException("unknown provider")
        if (keys.audiences.isEmpty()) throw InvalidTokenException("$provider sign-in is not configured")
        val decoded = try {
            val header = JWT.decode(token)
            val key = keys.keys.get(header.keyId).publicKey as RSAPublicKey
            JWT.require(Algorithm.RSA256(key, null))
                .withIssuer(*keys.issuers.toTypedArray())
                .withAnyOfAudience(*keys.audiences.toTypedArray())
                .acceptLeeway(60)
                .build()
                .verify(token)
        } catch (e: JWTVerificationException) {
            throw InvalidTokenException(e.message ?: "invalid token")
        } catch (e: Exception) {
            throw InvalidTokenException("could not verify token: ${e.message}")
        }
        val email = decoded.getClaim("email").asString()?.takeIf { it.isNotBlank() }
        // Google carries the name in the ID token; Apple hands it to the app, which passes it along separately.
        val name = decoded.getClaim("name").asString()?.takeIf { it.isNotBlank() }
        // Google sends a boolean; Apple has sent both a boolean and the string "true". Anything
        // else, including no claim at all, counts as unverified.
        val verifiedClaim = decoded.getClaim("email_verified")
        val emailVerified = email != null && (verifiedClaim.asBoolean() ?: verifiedClaim.asString()?.toBooleanStrictOrNull() ?: false)
        val audience = decoded.audience?.firstOrNull { it in keys.audiences }
        return VerifiedIdentity(provider, decoded.subject ?: throw InvalidTokenException("no subject"), email, name, emailVerified, audience)
    }

    companion object {
        const val GOOGLE_JWKS = "https://www.googleapis.com/oauth2/v3/certs"
        const val APPLE_JWKS = "https://appleid.apple.com/auth/keys"
        val GOOGLE_ISSUERS = listOf("https://accounts.google.com", "accounts.google.com")
        val APPLE_ISSUERS = listOf("https://appleid.apple.com")
    }
}

object Providers {
    const val GOOGLE = "google"
    const val APPLE = "apple"
}

/** The tokens this server issues: HS256 with [secret], the user id as subject, [TTL_DAYS] to live. */
class SessionTokens(secret: String, private val clock: () -> Instant = Instant::now) {
    private val algorithm = Algorithm.HMAC256(secret)
    private val verifier = JWT.require(algorithm).withIssuer(ISSUER).build()

    fun issue(userId: Long): String = JWT.create()
        .withIssuer(ISSUER)
        .withSubject(userId.toString())
        .withIssuedAt(clock())
        .withExpiresAt(clock().plusSeconds(TTL_DAYS * 24 * 3600))
        .sign(algorithm)

    /** The user id a token stands for, or null when it is not one of ours or has expired. */
    fun userId(token: String): Long? = try {
        verifier.verify(token).subject?.toLongOrNull()
    } catch (e: JWTVerificationException) {
        null
    }

    companion object {
        const val ISSUER = "gains"
        const val TTL_DAYS = 30L
    }
}
