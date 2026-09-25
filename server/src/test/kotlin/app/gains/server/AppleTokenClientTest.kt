package app.gains.server

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [AppleTokenClient] against a stand-in for `appleid.apple.com` on localhost: the client secret it
 * signs, the forms it posts, and what it makes of Apple's answers. Also how the key reaches it
 * from `secrets/.env`.
 */
class AppleTokenClientTest {
    private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    /** The key as a `.p8` file holds it. */
    private val pem = "-----BEGIN PRIVATE KEY-----\n" +
        Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pair.private.encoded) +
        "\n-----END PRIVATE KEY-----\n"

    private val requests = mutableListOf<Pair<String, Map<String, String>>>()
    private var answer: (String) -> Pair<Int, String> = { 200 to "{}" }
    private val apple = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val form = exchange.requestBody.readBytes().decodeToString().split('&').associate {
                val (k, v) = it.split('=', limit = 2)
                URLDecoder.decode(k, Charsets.UTF_8) to URLDecoder.decode(v, Charsets.UTF_8)
            }
            requests += exchange.requestURI.path to form
            val (status, body) = answer(exchange.requestURI.path)
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }

    private val now = Instant.now().truncatedTo(ChronoUnit.SECONDS)
    private val client = AppleTokenClient("KEY123", "V5Y8M5GKZ6", pem, baseUrl = "http://127.0.0.1:${apple.address.port}", clock = { now })

    @AfterTest
    fun stop() = apple.stop(0)

    @Test
    fun theClientSecretIsAnEs256TokenFromOurTeamAboutTheClientId() {
        val secret = client.clientSecret("app.gains.Gains")
        val decoded = JWT.require(Algorithm.ECDSA256(pair.public as ECPublicKey, null))
            .withIssuer("V5Y8M5GKZ6")
            .withSubject("app.gains.Gains")
            .withAudience("https://appleid.apple.com")
            .build()
            .verify(secret)
        assertEquals("ES256", decoded.algorithm)
        assertEquals("KEY123", decoded.keyId)
        assertEquals(now, decoded.issuedAtAsInstant)
        assertEquals(now.plusSeconds(300), decoded.expiresAtAsInstant)
    }

    @Test
    fun exchangePostsTheCodeAndReadsTheRefreshTokenAndSubject() {
        val idToken = JWT.create().withSubject("a-1").sign(Algorithm.none())
        answer = { 200 to """{"access_token":"at","token_type":"Bearer","expires_in":3600,"refresh_token":"rt-1","id_token":"$idToken"}""" }

        assertEquals(AppleGrant("rt-1", "a-1"), client.exchange("code-1", "app.gains.Gains"))
        val (path, form) = requests.single()
        assertEquals("/auth/token", path)
        assertEquals("app.gains.Gains", form["client_id"])
        assertEquals("code-1", form["code"])
        assertEquals("authorization_code", form["grant_type"])
        assertEquals("app.gains.Gains", JWT.decode(form.getValue("client_secret")).subject)
    }

    @Test
    fun revokePostsTheRefreshToken() {
        client.revoke("rt-1", "app.gains.Gains")
        val (path, form) = requests.single()
        assertEquals("/auth/revoke", path)
        assertEquals("rt-1", form["token"])
        assertEquals("refresh_token", form["token_type_hint"])
        assertEquals("app.gains.Gains", form["client_id"])
    }

    @Test
    fun applesErrorsThrow() {
        answer = { 400 to """{"error":"invalid_grant"}""" }
        val e = assertFailsWith<AppleTokenException> { client.exchange("used-twice", "app.gains.Gains") }
        assertTrue("invalid_grant" in e.message.orEmpty(), e.message)
        assertFailsWith<AppleTokenException> { client.revoke("rt-1", "app.gains.Gains") }

        answer = { 200 to """{"access_token":"at"}""" }
        assertFailsWith<AppleTokenException> { client.exchange("code-1", "app.gains.Gains") }
    }

    @Test
    fun theKeyComesFromTheEnvironmentWithEscapedNewlinesAndAllThreeOrNone() {
        val base = mapOf("JWT_SECRET" to "x".repeat(32))
        val nowhere = File("/nonexistent")
        assertNull(Config.fromEnvironment(base, nowhere).appleKey)

        val oneLine = pem.trimEnd().replace("\n", "\\n")
        val key = Config.fromEnvironment(
            base + mapOf("APPLE_KEY_ID" to "KEY123", "APPLE_TEAM_ID" to "V5Y8M5GKZ6", "APPLE_PRIVATE_KEY" to oneLine),
            nowhere,
        ).appleKey!!
        assertEquals(pem.trimEnd(), key.privateKey)
        assertTrue("PRIVATE" !in key.toString(), "the key stays out of logs")
        AppleTokenClient(key.keyId, key.teamId, key.privateKey).clientSecret("app.gains.Gains")

        assertFailsWith<IllegalArgumentException> {
            Config.fromEnvironment(base + mapOf("APPLE_KEY_ID" to "KEY123"), nowhere)
        }
    }
}
