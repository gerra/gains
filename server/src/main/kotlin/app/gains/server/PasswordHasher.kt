package app.gains.server

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Argon2id for the passwords of email accounts (docs/launch-plan.md, item 18), through Bouncy
 * Castle's pure-Java implementation since the JDK has none. A hash is kept in the PHC string form
 * (`$argon2id$v=19$m=…,t=…,p=…$<salt>$<hash>`), which carries its own parameters, so raising
 * them later costs nothing: an old hash still verifies with the values it was made with, and the
 * next password change gets the new ones. The defaults are OWASP's first recommendation: 19 MiB,
 * two passes, one lane, which keeps a sign-in under a tenth of a second on the box while making
 * a leaked table expensive to run through.
 */
class PasswordHasher(
    private val memoryKb: Int = 19_456,
    private val iterations: Int = 2,
    private val parallelism: Int = 1,
    private val random: SecureRandom = SecureRandom(),
) {
    fun hash(password: String): String {
        val salt = ByteArray(16).also(random::nextBytes)
        val hash = derive(password, salt, memoryKb, iterations, parallelism)
        val b64 = Base64.getEncoder().withoutPadding()
        return "\$argon2id\$v=19\$m=$memoryKb,t=$iterations,p=$parallelism\$${b64.encodeToString(salt)}\$${b64.encodeToString(hash)}"
    }

    /** Whether [password] is the one [encoded] was made from. A string that isn't one of ours is simply false. */
    fun verify(password: String, encoded: String): Boolean {
        val parts = encoded.split('$')
        if (parts.size != 6 || parts[1] != "argon2id" || parts[2] != "v=19") return false
        val params = parts[3].split(',').associate { it.substringBefore('=') to it.substringAfter('=', "").toIntOrNull() }
        val m = params["m"] ?: return false
        val t = params["t"] ?: return false
        val p = params["p"] ?: return false
        val decoder = Base64.getDecoder()
        val salt = try { decoder.decode(parts[4]) } catch (e: IllegalArgumentException) { return false }
        val expected = try { decoder.decode(parts[5]) } catch (e: IllegalArgumentException) { return false }
        val actual = derive(password, salt, m, t, p, expected.size)
        return MessageDigest.isEqual(expected, actual)
    }

    private fun derive(password: String, salt: ByteArray, memoryKb: Int, iterations: Int, parallelism: Int, length: Int = HASH_BYTES): ByteArray {
        val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(memoryKb)
            .withIterations(iterations)
            .withParallelism(parallelism)
            .withSalt(salt)
            .build()
        val out = ByteArray(length)
        Argon2BytesGenerator().apply { init(parameters) }.generateBytes(password.toByteArray(Charsets.UTF_8), out)
        return out
    }

    companion object {
        const val HASH_BYTES = 32
    }
}
