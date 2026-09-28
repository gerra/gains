package app.gains.server

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The hash carries its parameters, so verifying never needs to know which settings made it. */
class PasswordHasherTest {
    @Test
    fun aHashVerifiesItsPasswordAndNothingElse() {
        val hasher = cheapHasher
        val hash = hasher.hash("correct horse battery staple")
        assertTrue(hash.startsWith("\$argon2id\$v=19\$m=256,t=1,p=1\$"), hash)
        assertTrue(hasher.verify("correct horse battery staple", hash))
        assertFalse(hasher.verify("correct horse battery stapl", hash))
        assertFalse(hasher.verify("", hash))
        assertNotEquals(hash, hasher.hash("correct horse battery staple"), "a fresh salt every time")
    }

    @Test
    fun oldParametersStillVerifyAndGarbageNeverDoes() {
        val hash = PasswordHasher(memoryKb = 512, iterations = 2, parallelism = 2).hash("pw")
        assertTrue(cheapHasher.verify("pw", hash), "verified with the hash's own parameters")
        for (bad in listOf("", "pw", "\$argon2id\$v=19\$m=256,t=1,p=1\$salt", "\$argon2id\$v=19\$m=x,t=1,p=1\$c2FsdA\$aGFzaA", "\$bcrypt\$x\$y\$z\$w", "\$argon2id\$v=19\$m=256,t=1,p=1\$!!\$??")) {
            assertFalse(cheapHasher.verify("pw", bad), bad)
        }
    }
}
