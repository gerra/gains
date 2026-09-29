package app.gains.server

import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Seals the Apple refresh tokens kept in `identity.refresh_token` (docs/launch-plan.md, item 24).
 * Such a token lets whoever holds it act on the person's Sign in with Apple grant, so a copy of
 * the database file (a backup, a stray `scp`) must not be enough to use it: the key lives in
 * `secrets/.env` (`REFRESH_TOKEN_KEY`), never next to the data.
 *
 * AES-256-GCM, a random 12-byte nonce per value, stored as `v1:` + base64(nonce ‖ ciphertext ‖
 * tag). The prefix is how a row written before the key was set is told apart: anything without
 * it is plain text, which [open] passes through and [upgrade] seals. A key of its own rather than
 * `JWT_SECRET`, so rotating that (which signs everyone out) doesn't make the stored tokens
 * unreadable.
 *
 * With a null [key], [seal] stores plain text, as before this item; the start-up line says
 * `refresh token encryption off`.
 */
class RefreshTokenCipher(private val key: SecretKey?, private val random: SecureRandom = SecureRandom()) {
    val enabled: Boolean get() = key != null

    /** What to store for [token]: sealed with the key, or [token] itself without one. */
    fun seal(token: String): String {
        val key = key ?: return token
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce)) }
        return PREFIX + Base64.getEncoder().encodeToString(nonce + cipher.doFinal(token.toByteArray()))
    }

    /**
     * The token behind a stored value. Plain text from before the key comes back as it is. Throws
     * [GeneralSecurityException] for a sealed value this server can't open: no key, another key,
     * or a value that was changed.
     */
    fun open(stored: String): String {
        if (!isSealed(stored)) return stored
        val key = key ?: throw GeneralSecurityException("refresh token is sealed but REFRESH_TOKEN_KEY is not set")
        val bytes = try {
            Base64.getDecoder().decode(stored.removePrefix(PREFIX))
        } catch (e: IllegalArgumentException) {
            throw GeneralSecurityException("refresh token is not base64", e)
        }
        if (bytes.size <= NONCE_BYTES) throw GeneralSecurityException("refresh token too short")
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, bytes, 0, NONCE_BYTES))
        }
        return String(cipher.doFinal(bytes, NONCE_BYTES, bytes.size - NONCE_BYTES))
    }

    /** The sealed form of a plain-text [stored] value, or null when there is nothing to do (sealed already, or no key). */
    fun upgrade(stored: String): String? = if (enabled && !isSealed(stored)) seal(stored) else null

    companion object {
        const val PREFIX = "v1:"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
        const val KEY_BYTES = 32

        fun isSealed(stored: String) = stored.startsWith(PREFIX)

        /** The key from `REFRESH_TOKEN_KEY`'s base64 (`openssl rand -base64 32`); anything but 32 bytes is refused. */
        fun key(base64: String): SecretKey {
            val bytes = try {
                Base64.getDecoder().decode(base64.trim())
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("REFRESH_TOKEN_KEY is not base64 (openssl rand -base64 32; see secrets/README.md)")
            }
            require(bytes.size == KEY_BYTES) { "REFRESH_TOKEN_KEY must be $KEY_BYTES bytes, base64 (openssl rand -base64 32), not ${bytes.size}" }
            return SecretKeySpec(bytes, "AES")
        }
    }
}
