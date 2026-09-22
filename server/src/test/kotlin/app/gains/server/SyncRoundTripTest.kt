package app.gains.server

import app.gains.auth.Account
import app.gains.auth.AccountKind
import app.gains.auth.AccountRepository
import app.gains.auth.AuthConfig
import app.gains.auth.IdentityAssertion
import app.gains.auth.IdentityProvider
import app.gains.data.BodyweightRepository
import app.gains.data.DesktopDriverFactory
import app.gains.data.ExerciseRepository
import app.gains.data.ProgramRepository
import app.gains.data.SessionRepository
import app.gains.data.SettingsRepository
import app.gains.db.GainsDatabase
import app.gains.domain.BodyweightEntry
import app.gains.domain.ExerciseEntry
import app.gains.domain.Session
import app.gains.domain.SetEntry
import app.gains.domain.SetType
import app.gains.domain.WeightUnit
import app.gains.sync.SyncApi
import app.gains.sync.SyncEngine
import app.gains.sync.SyncStore
import io.ktor.client.HttpClient
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Two real client databases syncing through the real routes, the way two phones would: what one
 * logs the other gets, an edit replaces, a delete removes, a photo travels, and a device that
 * signs in with data of its own merges rather than wipes.
 */
class SyncRoundTripTest {
    private val google = FakeProvider(JwksIdentityVerifier.GOOGLE_ISSUERS.first())
    private val apple = FakeProvider(JwksIdentityVerifier.APPLE_ISSUERS.first())

    /** A device: its database, its repositories and its sync engine, signed in as [subject]. */
    private class Device(client: HttpClient, provider: FakeProvider, subject: String) {
        val db = GainsDatabase(DesktopDriverFactory(file = null).createDriver())
        val sessions = SessionRepository(db, Dispatchers.Unconfined)
        val exercises = ExerciseRepository(db, Dispatchers.Unconfined)
        val bodyweight = BodyweightRepository(db, Dispatchers.Unconfined)
        val settings = SettingsRepository(db, Dispatchers.Unconfined)
        val programs = ProgramRepository(db, settings, Dispatchers.Unconfined)
        val store = SyncStore(db, Dispatchers.Unconfined)
        val api = SyncApi(client, baseUrl = "", token = { store.token() })
        val engine = SyncEngine(store, api)
        val identityToken = provider.token(subject, GOOGLE_AUDIENCE, email = "$subject@x.y")

        suspend fun signIn() {
            val response = api.signIn(AccountKind.GOOGLE, identityToken, null)
            store.setToken(response.token)
            store.startFeed(response.user.id)
        }
    }

    private fun session(id: String, weight: Double, caption: String? = null) = Session(
        id = id, timestamp = LocalDateTime.parse(id), durationMinutes = 45, source = Session.MANUAL, caption = caption,
        exercises = listOf(ExerciseEntry("bench_press", listOf(SetEntry(0, SetType.WEIGHTED, weight, 5)))),
    )

    @Test
    fun twoDevicesConverge() = testApplication {
        application { gainsServer(testServices(google, apple)) }
        val phone = Device(client, google, "me")
        val laptop = Device(client, google, "me")
        phone.exercises.seedCatalogue(); laptop.exercises.seedCatalogue()
        phone.signIn(); laptop.signIn()

        // The phone logs a workout with a photo and a body weight; the laptop gets them.
        phone.sessions.upsert(session("2026-09-20T10:00", 100.0, caption = "pr"))
        phone.sessions.setPhoto("2026-09-20T10:00", byteArrayOf(1, 2, 3, 4))
        phone.bodyweight.upsert(BodyweightEntry(LocalDate(2026, 9, 20), 82.4))
        phone.settings.setUnit(WeightUnit.LBS)
        val up = phone.engine.sync()
        assertEquals(4, up.pushed)
        assertEquals(0, up.rejected)
        val down = laptop.engine.sync()
        assertEquals(4, down.pulled)
        val onLaptop = laptop.sessions.observeRawSessions().first().single()
        assertEquals("pr", onLaptop.caption)
        assertEquals(100.0, onLaptop.exercises.single().sets.single().weightKg)
        assertTrue(onLaptop.hasPhoto)
        assertEquals(listOf<Byte>(1, 2, 3, 4), laptop.sessions.photo(onLaptop.id)!!.toList())
        assertEquals(82.4, laptop.bodyweight.observe().first().single().weightKg)
        assertEquals(WeightUnit.LBS, laptop.settings.observeUnit().first())
        assertEquals(emptyList(), laptop.store.pendingChanges(), "what came down is not pushed back")

        // The laptop edits the set and removes the photo; the phone sees the row edited, not duplicated.
        laptop.sessions.upsert(onLaptop.copy(exercises = listOf(ExerciseEntry("bench_press", listOf(SetEntry(0, SetType.WEIGHTED, 102.5, 5))))))
        laptop.sessions.setPhoto(onLaptop.id, null)
        laptop.engine.sync()
        phone.engine.sync()
        val onPhone = phone.sessions.observeRawSessions().first().single()
        assertEquals(102.5, onPhone.exercises.single().sets.single().weightKg)
        assertEquals("pr", onPhone.caption, "the whole session came back, caption included")
        assertNull(phone.sessions.photo(onPhone.id))

        // Both edit the same session before syncing: the later edit wins on both.
        phone.sessions.updateSummary(onPhone.id, 50, "phone's caption")
        Thread.sleep(5)
        laptop.sessions.updateSummary(onPhone.id, 60, "laptop's caption")
        phone.engine.sync()
        laptop.engine.sync()
        phone.engine.sync()
        assertEquals("laptop's caption", phone.sessions.observeRawSessions().first().single().caption)
        assertEquals("laptop's caption", laptop.sessions.observeRawSessions().first().single().caption)

        // A delete on the laptop removes the session on the phone.
        laptop.sessions.deleteSession(onPhone.id)
        laptop.engine.sync()
        phone.engine.sync()
        assertEquals(emptyList(), phone.sessions.observeRawSessions().first())

        // Nothing left to say: a further sync moves nothing.
        val quiet = phone.engine.sync()
        assertEquals(0, quiet.pushed)
        assertEquals(0, quiet.pulled)
    }

    @Test
    fun aDeviceWithGuestDataMergesOnSignIn() = testApplication {
        application { gainsServer(testServices(google, apple)) }
        val phone = Device(client, google, "me")
        phone.exercises.seedCatalogue()
        phone.signIn()
        phone.sessions.upsert(session("2026-09-01T10:00", 90.0))
        phone.engine.sync()

        // The laptop was used as a guest for a while, then signs in to the same account.
        val laptop = Device(client, google, "me")
        laptop.exercises.seedCatalogue()
        laptop.sessions.upsert(session("2026-09-02T10:00", 95.0))
        for (change in laptop.store.pendingChanges()) laptop.store.clearPushed(change)
        laptop.signIn()
        laptop.engine.sync()
        assertEquals(listOf("2026-09-01T10:00", "2026-09-02T10:00"), laptop.sessions.observeRawSessions().first().map { it.id })
        phone.engine.sync()
        assertEquals(listOf("2026-09-01T10:00", "2026-09-02T10:00"), phone.sessions.observeRawSessions().first().map { it.id })
    }

    @Test
    fun aGuestLinksFromSettingsWithoutLosingAnything() = testApplication {
        application { gainsServer(testServices(google, apple)) }
        val phone = Device(client, google, "me")
        phone.exercises.seedCatalogue()
        val config = AuthConfig(appleServiceId = APPLE_AUDIENCE, serverBaseUrl = "https://api.example")
        val seen = mutableListOf<Account?>()
        val sheet = object : IdentityProvider {
            override suspend fun signIn(kind: AccountKind): IdentityAssertion {
                seen += AccountRepository.decode(phone.settings.observe(AccountRepository.KEY_ACCOUNT).first())
                return IdentityAssertion(apple.token("apple-me", APPLE_AUDIENCE, email = "me@x.y"), "Me")
            }
        }
        val accounts = AccountRepository(phone.settings, config, phone.api, phone.store, sheet)

        // A guest logs a workout; as far as the change log knows, it has been dealt with.
        accounts.continueAsGuest()
        phone.sessions.upsert(session("2026-09-03T10:00", 70.0))
        for (change in phone.store.pendingChanges()) phone.store.clearPushed(change)

        // Linking from Settings: the sheet opens on the guest, not on a signed-out app.
        accounts.signInWithApple()
        assertEquals(listOf<Account?>(Account(AccountKind.GUEST)), seen)
        assertEquals(AccountKind.APPLE, accounts.observeAccount().first()?.kind)
        assertEquals(listOf("2026-09-03T10:00"), phone.sessions.observeRawSessions().first().map { it.id })
        assertTrue(phone.store.observePendingCount().first() > 0, "everything on the device waits to go up")

        val upload = phone.engine.sync()
        assertTrue(upload.pushed > 0)
        assertEquals(0, upload.rejected)
        assertEquals(0L, phone.store.observePendingCount().first())
    }

    @Test
    fun aDifferentAccountSeesNoneOfIt() = testApplication {
        application { gainsServer(testServices(google, apple)) }
        val mine = Device(client, google, "me")
        val theirs = Device(client, google, "someone-else")
        mine.exercises.seedCatalogue(); theirs.exercises.seedCatalogue()
        mine.signIn(); theirs.signIn()
        mine.sessions.upsert(session("2026-09-20T10:00", 100.0))
        mine.engine.sync()
        theirs.engine.sync()
        assertEquals(emptyList(), theirs.sessions.observeRawSessions().first())
    }
}
