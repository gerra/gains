package app.gains.sync

import app.gains.data.DesktopDriverFactory
import app.gains.db.GainsDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The vault in front of a keyring: one read from the OS, then the copy here, and the old row's move. */
class KeyringTokenVaultTest {
    private class FakeKeyring(var token: String? = null) : Keyring {
        var reads = 0
        var failing = false
        override fun read(): String? { reads++; if (failing) error("no keyring"); return token }
        override fun write(token: String) { if (failing) error("no keyring"); this.token = token }
        override fun delete() { if (failing) error("no keyring"); token = null }
    }

    @Test
    fun theTokenIsReadFromTheOsOnce() = runTest {
        val keyring = FakeKeyring("t")
        val vault = KeyringTokenVault(keyring, Dispatchers.Unconfined)
        assertEquals("t", vault.get())
        assertEquals("t", vault.get())
        assertEquals(1, keyring.reads)

        vault.set("u")
        assertEquals("u", vault.get())
        assertEquals("u", keyring.token)
        vault.clear()
        assertNull(vault.get())
        assertNull(keyring.token)
        assertEquals(1, keyring.reads, "writes keep the copy current without asking the OS again")
    }

    @Test
    fun aKeyringThatWontAnswerThrowsRatherThanReadingAsSignedOut() = runTest {
        val keyring = FakeKeyring("t").apply { failing = true }
        val vault = KeyringTokenVault(keyring, Dispatchers.Unconfined)
        assertFailsWith<IllegalStateException> { vault.get() }
        keyring.failing = false
        assertEquals("t", vault.get(), "the next read tries again")
    }

    @Test
    fun aTokenFromBeforeTheVaultMovesIntoTheKeyring() = runTest {
        val db = GainsDatabase(DesktopDriverFactory(file = null).createDriver())
        db.syncQueries.upsertState(SyncStore.KEY_TOKEN, "old")
        val keyring = FakeKeyring()
        val store = SyncStore(db, Dispatchers.Unconfined, KeyringTokenVault(keyring, Dispatchers.Unconfined))

        assertEquals("old", store.token(), "an update keeps the person signed in")
        assertEquals("old", keyring.token)
        assertNull(db.syncQueries.selectState(SyncStore.KEY_TOKEN).executeAsOneOrNull(), "the database file no longer holds it")
    }
}
