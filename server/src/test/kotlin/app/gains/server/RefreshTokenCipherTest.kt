package app.gains.server

import app.gains.sync.SignInRequest
import app.gains.sync.SignInResponse
import app.gains.sync.SyncJson
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.io.File
import java.security.GeneralSecurityException
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Apple refresh tokens at rest (docs/launch-plan.md, item 24): sealed with `REFRESH_TOKEN_KEY`
 * when it is set, a row from before still revoked and sealed later, plain text without the key.
 */
class RefreshTokenCipherTest {
    private val google = FakeProvider(JwksIdentityVerifier.GOOGLE_ISSUERS.first())
    private val apple = FakeProvider(JwksIdentityVerifier.APPLE_ISSUERS.first())

    private fun keyBase64(fill: Int) = Base64.getEncoder().encodeToString(ByteArray(32) { (it + fill).toByte() })
    private val cipher = RefreshTokenCipher(RefreshTokenCipher.key(keyBase64(1)))

    private suspend fun HttpClient.signIn(token: String, code: String): SignInResponse {
        val response = post("/auth/apple") {
            contentType(ContentType.Application.Json)
            setBody(SyncJson.encodeToString(SignInRequest.serializer(), SignInRequest(token, authorizationCode = code)))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return SyncJson.decodeFromString(SignInResponse.serializer(), response.bodyAsText())
    }

    private suspend fun HttpClient.deleteAccount(token: String) =
        assertEquals(HttpStatusCode.NoContent, delete("/auth/account") { header("Authorization", "Bearer $token") }.status)

    @Test
    fun aTokenRoundTripsAndEachSealIsDifferent() {
        val first = cipher.seal("refresh-token")
        val second = cipher.seal("refresh-token")
        assertTrue(first.startsWith("v1:"), first)
        assertTrue("refresh-token" !in first)
        assertNotEquals(first, second, "a fresh nonce each time")
        assertEquals("refresh-token", cipher.open(first))
        assertEquals("refresh-token", cipher.open(second))
    }

    @Test
    fun plainTextFromBeforePassesThroughAndIsUpgradedOnce() {
        assertEquals("rt-old", cipher.open("rt-old"))
        val upgraded = cipher.upgrade("rt-old")!!
        assertEquals("rt-old", cipher.open(upgraded))
        assertNull(cipher.upgrade(upgraded), "already sealed")
        assertNull(RefreshTokenCipher(null).upgrade("rt-old"), "no key, nothing to do")
    }

    @Test
    fun aSealedTokenOpensOnlyWithItsKeyAndUnchanged() {
        val sealed = cipher.seal("refresh-token")
        assertFailsWith<GeneralSecurityException> { RefreshTokenCipher(RefreshTokenCipher.key(keyBase64(2))).open(sealed) }
        assertFailsWith<GeneralSecurityException> { RefreshTokenCipher(null).open(sealed) }
        val bytes = Base64.getDecoder().decode(sealed.removePrefix("v1:"))
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        assertFailsWith<GeneralSecurityException> { cipher.open("v1:" + Base64.getEncoder().encodeToString(bytes)) }
        assertFailsWith<GeneralSecurityException> { cipher.open("v1:not base64!") }
        assertFailsWith<GeneralSecurityException> { cipher.open("v1:") }
    }

    @Test
    fun theKeyIsReadFromTheEnvironmentAndMustBe32Bytes() {
        val env = mapOf("JWT_SECRET" to "x".repeat(32))
        val nowhere = File("/nonexistent")
        assertNull(Config.fromEnvironment(env, nowhere).refreshTokenKey)
        assertNull(Config.fromEnvironment(env + ("REFRESH_TOKEN_KEY" to " "), nowhere).refreshTokenKey)
        val key = Config.fromEnvironment(env + ("REFRESH_TOKEN_KEY" to keyBase64(1)), nowhere).refreshTokenKey!!
        assertEquals(RefreshTokenCipher.key(keyBase64(1)), key)
        for (bad in listOf(Base64.getEncoder().encodeToString(ByteArray(16)), "not base64!")) {
            assertFailsWith<IllegalArgumentException>(bad) { Config.fromEnvironment(env + ("REFRESH_TOKEN_KEY" to bad), nowhere) }
        }
    }

    @Test
    fun withTheKeyTheStoredTokenIsCiphertextAndDeletingRevokesTheRealOne() = testApplication {
        val appleTokens = FakeAppleTokens(codes = mapOf("code-1" to "a-1"))
        val services = testServices(google, apple, appleTokens = appleTokens, refreshTokenCipher = cipher)
        application { gainsServer(services) }

        val signedIn = client.signIn(apple.token("a-1", APPLE_AUDIENCE), code = "code-1")
        val (stored, clientId) = services.store.refreshTokens(signedIn.user.id, Providers.APPLE).single()
        assertTrue(stored.startsWith("v1:"), stored)
        assertEquals(APPLE_AUDIENCE, clientId)
        assertEquals("refresh-code-1", cipher.open(stored))

        client.deleteAccount(signedIn.token)
        assertEquals(listOf("refresh-code-1" to APPLE_AUDIENCE), appleTokens.revoked)
    }

    @Test
    fun aPlainTextRowFromBeforeIsRevokedAndIsCiphertextAfterTheNextSignIn() = testApplication {
        val appleTokens = FakeAppleTokens(codes = mapOf("code-2" to "a-1"))
        val store = Store.open(null)
        val services = testServices(google, apple, appleTokens = appleTokens, refreshTokenCipher = cipher, store = store)
        application { gainsServer(services) }

        // A row as the server wrote it before the key was set.
        val first = client.signIn(apple.token("a-1", APPLE_AUDIENCE), code = "expired")
        store.setRefreshToken(Providers.APPLE, "a-1", "refresh-from-before", APPLE_AUDIENCE)

        client.signIn(apple.token("a-1", APPLE_AUDIENCE), code = "code-2")
        val stored = store.refreshTokens(first.user.id, Providers.APPLE).single().first
        assertTrue(stored.startsWith("v1:"), stored)

        // And one never signed in again is still revoked as it is.
        val other = client.signIn(apple.token("a-9", APPLE_AUDIENCE), code = "expired")
        store.setRefreshToken(Providers.APPLE, "a-9", "refresh-never-again", APPLE_AUDIENCE)
        client.deleteAccount(other.token)
        assertEquals(listOf("refresh-never-again" to APPLE_AUDIENCE), appleTokens.revoked)
    }

    @Test
    fun thePassAtStartSealsEveryPlainTextRowOnce() {
        val store = Store.open(null)
        for (subject in listOf("a-1", "a-2")) {
            store.signIn(Providers.APPLE, subject, null, emailVerified = false, name = null)
            store.setRefreshToken(Providers.APPLE, subject, "refresh-$subject", APPLE_AUDIENCE)
        }
        val withoutKey = testServices(google, apple, store = store)
        assertEquals(0, sealStoredRefreshTokens(withoutKey))
        assertEquals(listOf("refresh-a-1" to APPLE_AUDIENCE), store.refreshTokens(1, Providers.APPLE), "no key: plain text stays")

        val withKey = testServices(google, apple, refreshTokenCipher = cipher, store = store)
        assertEquals(2, sealStoredRefreshTokens(withKey))
        assertEquals(0, sealStoredRefreshTokens(withKey), "once")
        for (userId in listOf(1L, 2L)) {
            val stored = store.refreshTokens(userId, Providers.APPLE).single().first
            assertTrue(stored.startsWith("v1:"), stored)
            assertEquals("refresh-a-$userId", cipher.open(stored))
        }
    }

    @Test
    fun withoutTheKeyTheRowStaysPlainTextAndTheStartLineSaysSo() = testApplication {
        val appleTokens = FakeAppleTokens(codes = mapOf("code-1" to "a-1"))
        val services = testServices(google, apple, appleTokens = appleTokens)
        application { gainsServer(services) }

        val signedIn = client.signIn(apple.token("a-1", APPLE_AUDIENCE), code = "code-1")
        assertEquals(listOf("refresh-code-1" to APPLE_AUDIENCE), services.store.refreshTokens(signedIn.user.id, Providers.APPLE))

        val config = Config.fromEnvironment(mapOf("JWT_SECRET" to "x".repeat(32)), File("/nonexistent"))
        assertTrue("refresh token encryption off" in startLine(config, services), startLine(config, services))
        val sealing = testServices(google, apple, refreshTokenCipher = cipher)
        assertTrue("refresh token encryption on" in startLine(config, sealing), startLine(config, sealing))
    }
}
