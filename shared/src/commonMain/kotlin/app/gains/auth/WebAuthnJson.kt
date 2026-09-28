package app.gains.auth

import app.gains.sync.SyncJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.io.encoding.Base64

/**
 * WebAuthn's JSON on the app's side, for a platform whose passkey API takes the pieces rather
 * than the JSON (iOS's AuthenticationServices): the fields it needs out of the server's options,
 * and its answer put back into the `PublicKeyCredential` JSON the server parses. Android's
 * Credential Manager reads and writes that JSON itself. Binary fields are base64url, as WebAuthn
 * writes them.
 */
object WebAuthnJson {
    /** What a platform needs from `PublicKeyCredentialCreationOptions` to make a passkey. */
    class Creation(val rpId: String, val challenge: ByteArray, val userId: ByteArray, val userName: String)

    /** What a platform needs from `PublicKeyCredentialRequestOptions` to sign in with one. */
    class Request(val rpId: String, val challenge: ByteArray)

    fun creation(options: String): Creation {
        val json = SyncJson.parseToJsonElement(options).jsonObject
        val user = json.getValue("user").jsonObject
        return Creation(
            rpId = json.getValue("rp").jsonObject.string("id"),
            challenge = decode(json.string("challenge")),
            userId = decode(user.string("id")),
            userName = user.string("name"),
        )
    }

    fun request(options: String): Request {
        val json = SyncJson.parseToJsonElement(options).jsonObject
        return Request(rpId = json.string("rpId"), challenge = decode(json.string("challenge")))
    }

    /** A new passkey, as `navigator.credentials.create` would return it. */
    fun registration(credentialId: ByteArray, clientDataJson: ByteArray, attestationObject: ByteArray): String = credential(credentialId) {
        put("clientDataJSON", encode(clientDataJson))
        put("attestationObject", encode(attestationObject))
    }

    /** A sign-in with a passkey, as `navigator.credentials.get` would return it. */
    fun assertion(credentialId: ByteArray, clientDataJson: ByteArray, authenticatorData: ByteArray, signature: ByteArray, userHandle: ByteArray): String =
        credential(credentialId) {
            put("clientDataJSON", encode(clientDataJson))
            put("authenticatorData", encode(authenticatorData))
            put("signature", encode(signature))
            put("userHandle", encode(userHandle))
        }

    private fun credential(id: ByteArray, response: JsonObjectBuilder.() -> Unit): String = buildJsonObject {
        put("id", encode(id))
        put("rawId", encode(id))
        put("type", "public-key")
        putJsonObject("response", response)
        putJsonObject("clientExtensionResults") {}
    }.toString()

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    /** Base64url, reading with or without padding and writing without, as WebAuthn does. */
    private val base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

    fun encode(bytes: ByteArray): String = base64.encode(bytes)

    fun decode(text: String): ByteArray = base64.decode(text)
}
