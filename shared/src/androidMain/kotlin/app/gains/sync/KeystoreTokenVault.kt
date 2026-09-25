package app.gains.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The bearer token encrypted with an AES-GCM key that lives in the Android Keystore, so the key
 * never leaves it and the ciphertext alone is useless. The ciphertext is kept in a private
 * `SharedPreferences` file of its own rather than in the database. `security-crypto` would do the
 * same but is deprecated, hence the few lines here. The key needs no screen lock, so a sync
 * started in the background still works.
 *
 * The preferences file is left out of backups and device transfers
 * (`composeApp/src/androidMain/res/xml/backup_rules.xml` and `data_extraction_rules.xml`): the
 * Keystore key never moves to another phone, so its ciphertext would be dead weight there. A
 * value the key can't open (it is gone, or the file came from elsewhere) is dropped and read as
 * no token; the account then sees a 401 and signs in again. See docs/sync.md.
 */
class KeystoreTokenVault(
    context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : TokenVault {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override suspend fun get(): String? = withContext(io) {
        val stored = prefs.getString(PREF_TOKEN, null) ?: return@withContext null
        val token = decrypt(stored)
        if (token == null) prefs.edit().remove(PREF_TOKEN).commit()
        token?.ifBlank { null }
    }

    override suspend fun set(token: String) {
        withContext(io) {
            check(prefs.edit().putString(PREF_TOKEN, encrypt(token)).commit()) { "Token write failed" }
        }
    }

    override suspend fun clear() {
        withContext(io) {
            check(prefs.edit().remove(PREF_TOKEN).commit()) { "Token delete failed" }
        }
    }

    /** The IV the Keystore picked, then the ciphertext and its tag, as one Base64 string. */
    private fun encrypt(token: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyForWriting())
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "Unexpected IV size: ${iv.size}" }
        return Base64.encodeToString(iv + cipher.doFinal(token.encodeToByteArray()), Base64.NO_WRAP)
    }

    /**
     * The token, or null when [stored] can't be the key's work: no key (a reinstall or a wiped
     * Keystore), a tag that doesn't match, or a value that isn't ours. Anything else, such as the
     * Keystore failing to answer, is thrown, so a passing fault never signs anyone out.
     */
    private fun decrypt(stored: String): String? {
        val key = keyStore().getKey(KEY_ALIAS, null) as? SecretKey ?: return null
        val bytes = try {
            Base64.decode(stored, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (bytes.size <= IV_BYTES) return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
        return try {
            cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES).decodeToString()
        } catch (e: AEADBadTagException) {
            null
        }
    }

    /**
     * The key, made the first time a token is stored. Synchronized so two first writes can't each
     * make one, which would leave the first write's ciphertext unreadable.
     */
    @Synchronized
    private fun keyForWriting(): SecretKey {
        (keyStore().getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "app.gains.sync.token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128

        /** The preferences file (`app.gains.sync.xml`) the backup rules name; keep them in step. */
        const val PREFS = "app.gains.sync"
        const val PREF_TOKEN = "token"
    }
}
