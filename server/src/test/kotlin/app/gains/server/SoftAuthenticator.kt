package app.gains.server

import app.gains.auth.WebAuthnJson
import app.gains.sync.SyncJson
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * A passkey authenticator in software, answering the server's options the way a phone's does:
 * an EC P-256 key per passkey, "none" attestation, user presence and verification flagged, and
 * the answer written as WebAuthn's JSON with [WebAuthnJson], as the iOS app writes it. [origin]
 * is what the client data claims the ceremony came from.
 */
class SoftAuthenticator(var origin: String = "https://gains.example") {
    class Passkey(val id: ByteArray, val rpId: String, val userHandle: ByteArray, val keys: KeyPair, var counter: Int = 0)

    val passkeys = mutableListOf<Passkey>()
    private val random = SecureRandom()

    /** Makes a passkey for the creation [options] and returns the new credential's JSON. */
    fun create(options: String): String {
        val creation = WebAuthnJson.creation(options)
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val passkey = Passkey(ByteArray(16).also(random::nextBytes), creation.rpId, creation.userId, keys)
        passkeys += passkey
        val clientData = clientData("webauthn.create", creation.challenge)
        val authData = sha256(creation.rpId.toByteArray()) + byteArrayOf(FLAGS_CREATE) + counter(0) +
            ByteArray(16) + byteArrayOf(0, passkey.id.size.toByte()) + passkey.id + coseKey(keys.public as ECPublicKey)
        val attestation = Cbor.map(
            Cbor.text("fmt") to Cbor.text("none"),
            Cbor.text("attStmt") to Cbor.map(),
            Cbor.text("authData") to Cbor.bytes(authData),
        )
        return WebAuthnJson.registration(passkey.id, clientData, attestation)
    }

    /** Signs the request [options] with [passkey] (the newest by default) and returns the assertion's JSON. */
    fun get(options: String, passkey: Passkey = passkeys.last()): String {
        val request = WebAuthnJson.request(options)
        passkey.counter++
        val clientData = clientData("webauthn.get", request.challenge)
        val authData = sha256(request.rpId.toByteArray()) + byteArrayOf(FLAGS_GET) + counter(passkey.counter)
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(passkey.keys.private)
            update(authData + sha256(clientData))
            sign()
        }
        return WebAuthnJson.assertion(passkey.id, clientData, authData, signature, passkey.userHandle)
    }

    /** The challenge the [options] carry, base64url, to answer another ceremony's with. */
    fun challengeOf(options: String): String = SyncJson.parseToJsonElement(options).jsonObject.getValue("challenge").jsonPrimitive.content

    private fun clientData(type: String, challenge: ByteArray): ByteArray =
        """{"type":"$type","challenge":"${WebAuthnJson.encode(challenge)}","origin":"$origin","crossOrigin":false}""".toByteArray()

    private fun counter(value: Int) = byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())

    /** The public key as COSE: EC2 (kty 2), ES256 (alg -7), P-256 (crv 1), then x and y. */
    private fun coseKey(key: ECPublicKey): ByteArray = Cbor.map(
        Cbor.int(1) to Cbor.int(2),
        Cbor.int(3) to Cbor.int(-7),
        Cbor.int(-1) to Cbor.int(1),
        Cbor.int(-2) to Cbor.bytes(fixed32(key.w.affineX)),
        Cbor.int(-3) to Cbor.bytes(fixed32(key.w.affineY)),
    )

    private fun fixed32(n: BigInteger): ByteArray = n.toByteArray().takeLast(32).toByteArray().let { ByteArray(32 - it.size) + it }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private companion object {
        /** User present, user verified, attested credential data included. */
        const val FLAGS_CREATE: Byte = 0x45

        /** User present, user verified. */
        const val FLAGS_GET: Byte = 0x05
    }
}

/** Just enough CBOR for an attestation object and a COSE key: small ints, byte and text strings, maps. */
private object Cbor {
    fun int(value: Int): ByteArray = if (value >= 0) head(0, value.toLong()) else head(1, (-1L - value))
    fun bytes(value: ByteArray): ByteArray = head(2, value.size.toLong()) + value
    fun text(value: String): ByteArray = value.toByteArray().let { head(3, it.size.toLong()) + it }
    fun map(vararg entries: Pair<ByteArray, ByteArray>): ByteArray = ByteArrayOutputStream().apply {
        write(head(5, entries.size.toLong()))
        for ((key, value) in entries) { write(key); write(value) }
    }.toByteArray()

    private fun head(major: Int, length: Long): ByteArray = when {
        length < 24 -> byteArrayOf(((major shl 5) or length.toInt()).toByte())
        length < 256 -> byteArrayOf(((major shl 5) or 24).toByte(), length.toByte())
        else -> byteArrayOf(((major shl 5) or 25).toByte(), (length ushr 8).toByte(), length.toByte())
    }
}
