package app.gains.auth

import app.gains.sync.SyncJson
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * The iOS side of WebAuthn's JSON: the server's options read into what AuthenticationServices
 * takes, and its answer written the way the server parses it (the round trip against the real
 * server is `PasskeysTest` in :server).
 */
class WebAuthnJsonTest {
    @Test
    fun readsWhatThePlatformNeedsFromTheOptions() {
        val creation = WebAuthnJson.creation(
            """{"rp":{"name":"Gains","id":"gains.gerra.sh"},"user":{"name":"ada@example.com","displayName":"Ada","id":"AQID"},""" +
                """"challenge":"3q2-7w","pubKeyCredParams":[{"alg":-7,"type":"public-key"}],"timeout":300000}"""
        )
        assertEquals("gains.gerra.sh", creation.rpId)
        assertEquals("ada@example.com", creation.userName)
        assertContentEquals(byteArrayOf(1, 2, 3), creation.userId)
        assertContentEquals(byteArrayOf(0xde.toByte(), 0xad.toByte(), 0xbe.toByte(), 0xef.toByte()), creation.challenge)

        val request = WebAuthnJson.request("""{"challenge":"3q2-7w==","rpId":"gains.gerra.sh","userVerification":"required"}""")
        assertEquals("gains.gerra.sh", request.rpId)
        assertContentEquals(byteArrayOf(0xde.toByte(), 0xad.toByte(), 0xbe.toByte(), 0xef.toByte()), request.challenge, "padding is accepted too")
    }

    @Test
    fun writesTheAnswerAsAPublicKeyCredential() {
        val json = SyncJson.parseToJsonElement(
            WebAuthnJson.assertion(byteArrayOf(-1, -2), byteArrayOf(1), byteArrayOf(2), byteArrayOf(3), byteArrayOf(4, 5))
        ).jsonObject
        assertEquals("__4", json.getValue("id").jsonPrimitive.content, "base64url, no padding")
        assertEquals("__4", json.getValue("rawId").jsonPrimitive.content)
        assertEquals("public-key", json.getValue("type").jsonPrimitive.content)
        assertEquals(emptyMap(), json.getValue("clientExtensionResults").jsonObject)
        val response = json.getValue("response").jsonObject
        assertEquals(listOf("clientDataJSON", "authenticatorData", "signature", "userHandle"), response.keys.toList())
        assertEquals("BAU", response.getValue("userHandle").jsonPrimitive.content)

        val made = SyncJson.parseToJsonElement(WebAuthnJson.registration(byteArrayOf(9), byteArrayOf(1), byteArrayOf(2))).jsonObject
        assertEquals(setOf("clientDataJSON", "attestationObject"), made.getValue("response").jsonObject.keys)
    }
}
