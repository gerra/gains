package app.gains.sync

import app.gains.data.BodyweightRepository
import app.gains.data.DesktopDriverFactory
import app.gains.data.ExerciseRepository
import app.gains.data.ProgramRepository
import app.gains.data.SessionRepository
import app.gains.data.SettingsRepository
import app.gains.data.ThemeMode
import app.gains.data.AppLanguage
import app.gains.db.GainsDatabase
import app.gains.domain.BodyweightEntry
import app.gains.domain.Exercise
import app.gains.domain.ExerciseEntry
import app.gains.domain.ExerciseSlot
import app.gains.domain.Experience
import app.gains.domain.Goal
import app.gains.domain.Modality
import app.gains.domain.MuscleContribution
import app.gains.domain.MuscleGroup
import app.gains.domain.Program
import app.gains.domain.ProgramDay
import app.gains.domain.ProgramDayRef
import app.gains.domain.RepTarget
import app.gains.domain.Session
import app.gains.domain.SetEntry
import app.gains.domain.SetType
import app.gains.domain.WeightUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The device's side of the sync: the triggers record what the repositories write, a document
 * built from the rows comes back identical when applied elsewhere, and a newer local change is
 * not overwritten by an older pulled one.
 */
class SyncStoreTest {
    private class Device {
        val db = GainsDatabase(DesktopDriverFactory(file = null).createDriver())
        val sessions = SessionRepository(db, Dispatchers.Unconfined)
        val exercises = ExerciseRepository(db, Dispatchers.Unconfined)
        val bodyweight = BodyweightRepository(db, Dispatchers.Unconfined)
        val settings = SettingsRepository(db, Dispatchers.Unconfined)
        val programs = ProgramRepository(db, settings, Dispatchers.Unconfined)
        val store = SyncStore(db, Dispatchers.Unconfined)

        suspend fun pending() = store.pendingChanges().map { Triple(it.kind, it.id, it.deleted) }.toSet()
    }

    private val session = Session(
        id = "2026-09-20T10:00",
        timestamp = LocalDateTime(2026, 9, 20, 10, 0),
        durationMinutes = 55,
        source = Session.MANUAL,
        program = ProgramDayRef("custom_1", "custom_1/d0_x"),
        caption = "Felt strong",
        exercises = listOf(
            ExerciseEntry("bench_press", listOf(
                SetEntry(0, SetType.WEIGHTED, 60.0, 5, isWarmup = true),
                SetEntry(1, SetType.WEIGHTED, 100.0, 5, rpe = 8.0),
            ), note = "paused"),
            ExerciseEntry("plank", listOf(SetEntry(0, SetType.ISOMETRIC, seconds = 60))),
        ),
    )

    @Test
    fun theTriggersRecordWhatTheRepositoriesWrite() = runTest {
        val d = Device()
        d.exercises.seedCatalogue()
        assertEquals(emptySet(), d.pending(), "the built-in catalogue is not synced")

        d.sessions.upsert(session)
        d.sessions.setPhoto(session.id, byteArrayOf(1, 2, 3))
        d.exercises.upsertAll(listOf(Exercise("my_row", "My Row", "My Row", listOf(MuscleContribution(MuscleGroup.LATS, 1.0)), Modality.WEIGHTED)))
        d.exercises.setAlias("Weird Row", "my_row")
        d.exercises.setWorkingSetRatio("bench_press", 0.5)
        d.bodyweight.upsert(BodyweightEntry(LocalDate(2026, 9, 20), 82.4))
        d.settings.setUnit(WeightUnit.LBS)
        d.settings.setThemeMode(ThemeMode.LIGHT)
        d.settings.setLanguage(AppLanguage.RUSSIAN)
        d.programs.upsert(program())

        assertEquals(
            setOf(
                Triple(SyncKinds.SESSION, session.id, false),
                Triple(SyncKinds.SESSION_PHOTO, session.id, false),
                Triple(SyncKinds.EXERCISE, "my_row", false),
                Triple(SyncKinds.ALIAS, "weird row", false),
                Triple(SyncKinds.OVERRIDE, "bench_press", false),
                Triple(SyncKinds.BODYWEIGHT, "2026-09-20", false),
                Triple(SyncKinds.SETTING, SettingsRepository.KEY_UNIT, false),
                Triple(SyncKinds.PROGRAM, "custom_1", false),
            ),
            d.pending(),
            "theme and language stay on the device",
        )

        // Editing one set marks the session again; deleting turns the marks into tombstones.
        for (change in d.store.pendingChanges()) d.store.clearPushed(change)
        assertEquals(emptySet(), d.pending())
        d.sessions.upsert(session.copy(exercises = session.exercises.take(1)))
        assertEquals(setOf(Triple(SyncKinds.SESSION, session.id, false)), d.pending())
        d.sessions.deleteSession(session.id)
        assertEquals(setOf(Triple(SyncKinds.SESSION, session.id, true), Triple(SyncKinds.SESSION_PHOTO, session.id, true)), d.pending())
        for (change in d.store.pendingChanges()) d.store.clearPushed(change)
        d.exercises.merge("my_row", "seated_cable_row", "My Row")
        // A merge deletes the custom exercise and records an alias for it in one statement each.
        assertEquals(setOf(Triple(SyncKinds.EXERCISE, "my_row", true), Triple(SyncKinds.ALIAS, "my row", false)), d.pending())
    }

    @Test
    fun aDocumentAppliedElsewhereReadsBackTheSame() = runTest {
        val a = Device()
        val b = Device()
        a.exercises.seedCatalogue(); b.exercises.seedCatalogue()
        a.sessions.upsert(session)
        a.sessions.setPhoto(session.id, byteArrayOf(9, 8, 7))
        a.programs.upsert(program())
        a.bodyweight.upsert(BodyweightEntry(LocalDate(2026, 9, 20), 82.4))
        a.settings.setUnit(WeightUnit.LBS)
        a.exercises.upsertAll(listOf(Exercise("my_row", "My Row", "My Row", listOf(MuscleContribution(MuscleGroup.LATS, 1.0)), Modality.WEIGHTED, isDumbbell = true)))

        val documents = a.store.pendingChanges().map { a.store.load(it) }
        val photos = mapOf(session.id to a.store.photo(session.id)!!)
        b.store.applyPage(documents.mapIndexed { i, doc -> doc.copy(seq = i + 1L) }, photos, cursor = documents.size.toLong())

        val stored = b.sessions.observeRawSessions().first().single()
        assertEquals(session.copy(hasPhoto = true), stored)
        assertEquals(listOf(9.toByte(), 8, 7), b.sessions.photo(session.id)!!.toList())
        assertEquals(program(), b.programs.observeCustomPrograms().first().single())
        assertEquals(82.4, b.bodyweight.observe().first().single().weightKg)
        assertEquals(WeightUnit.LBS, b.settings.observeUnit().first())
        assertTrue(b.exercises.exercises().any { it.id == "my_row" && it.isDumbbell && !it.isBuiltIn })
        assertEquals(documents.size.toLong(), b.store.cursor())
        // The apply is not an edit: nothing waits to be pushed back.
        assertEquals(emptySet(), b.pending())

        // A tombstone removes the rows.
        b.store.applyPage(listOf(SyncDocument(SyncKinds.SESSION, session.id, SyncStore.now(), deleted = true, seq = 50)), emptyMap(), 50)
        assertEquals(emptyList(), b.sessions.observeRawSessions().first())
        assertNull(b.sessions.photo(session.id))
        assertEquals(emptySet(), b.pending())
    }

    @Test
    fun aNewerLocalChangeIsNotOverwrittenByAnOlderPulledOne() = runTest {
        val d = Device()
        d.exercises.seedCatalogue()
        d.sessions.upsert(session.copy(caption = "mine, newer"))
        val local = d.store.pendingChanges().single()

        val older = SyncDocument(
            SyncKinds.SESSION, session.id, updatedAt = "2020-01-01T00:00:00.000Z",
            payload = SyncJson.encodeToString(SessionDoc.serializer(), SessionDoc.of(session.copy(caption = "theirs, older"))), seq = 1,
        )
        d.store.applyPage(listOf(older), emptyMap(), 1)
        assertEquals("mine, newer", d.sessions.observeRawSessions().first().single().caption)
        assertEquals(local, d.store.pendingChanges().single(), "the local change still waits to be pushed")

        val newer = older.copy(updatedAt = "2999-01-01T00:00:00.000Z", payload = SyncJson.encodeToString(SessionDoc.serializer(), SessionDoc.of(session.copy(caption = "theirs, newer"))), seq = 2)
        d.store.applyPage(listOf(newer), emptyMap(), 2)
        assertEquals("theirs, newer", d.sessions.observeRawSessions().first().single().caption)
        assertEquals(emptyList(), d.store.pendingChanges())
    }

    @Test
    fun signingInMarksEverythingAndAChangeOfUserStartsOver() = runTest {
        val d = Device()
        d.exercises.seedCatalogue()
        d.sessions.upsert(session)
        d.settings.setThemeMode(ThemeMode.LIGHT)
        for (change in d.store.pendingChanges()) d.store.clearPushed(change)
        d.store.applyPage(emptyList(), emptyMap(), cursor = 42)

        d.store.startFeed(userId = 7)
        assertEquals(0L, d.store.cursor())
        assertEquals(setOf(Triple(SyncKinds.SESSION, session.id, false)), d.pending())

        for (change in d.store.pendingChanges()) d.store.clearPushed(change)
        d.store.applyPage(emptyList(), emptyMap(), cursor = 42)
        d.store.startFeed(userId = 7)
        assertEquals(42L, d.store.cursor(), "the same user signing in again resumes")
        assertEquals(emptySet(), d.pending())
    }

    @Test
    fun forgettingTheFeedMakesTheNextSignInUploadEverything() = runTest {
        val d = Device()
        d.exercises.seedCatalogue()
        d.sessions.upsert(session)
        d.store.setToken("t")
        d.store.startFeed(userId = 7)
        for (change in d.store.pendingChanges()) d.store.clearPushed(change)
        d.store.applyPage(emptyList(), emptyMap(), cursor = 42)

        d.store.forgetFeed()
        assertNull(d.store.userId())
        assertEquals(0L, d.store.cursor())
        assertEquals("t", d.store.token(), "the token is signOut's to clear")
        assertEquals(emptySet(), d.pending(), "nothing is marked until someone signs in")

        // Even the same user id starts over, so the upload doesn't depend on the server's id scheme.
        d.store.startFeed(userId = 7)
        assertEquals(setOf(Triple(SyncKinds.SESSION, session.id, false)), d.pending())
    }

    @Test
    fun theLastSyncIsKeptUntilTheFeedChangesHands() = runTest {
        val d = Device()
        assertNull(d.store.observeLastSyncedAt().first())
        d.store.startFeed(userId = 7)
        d.store.setLastSyncedAt("2026-09-22T10:00:00.000Z")
        assertEquals("2026-09-22T10:00:00.000Z", d.store.observeLastSyncedAt().first())

        // Signing back in to the same account resumes the feed, and its last sync with it.
        d.store.startFeed(userId = 7)
        assertEquals("2026-09-22T10:00:00.000Z", d.store.observeLastSyncedAt().first())

        // Another account has not synced on this device yet.
        d.store.startFeed(userId = 8)
        assertNull(d.store.observeLastSyncedAt().first())

        d.store.setLastSyncedAt("2026-09-22T11:00:00.000Z")
        d.store.forgetFeed()
        assertNull(d.store.observeLastSyncedAt().first())
    }

    @Test
    fun theClockReadsLikeTheTriggersWrite() = runTest {
        val d = Device()
        d.bodyweight.upsert(BodyweightEntry(LocalDate(2026, 9, 20), 82.4))
        val stamped = d.store.pendingChanges().single().changedAt
        val now = SyncStore.now()
        assertTrue(Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z""").matches(stamped), stamped)
        assertTrue(Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z""").matches(now), now)
        assertTrue(stamped <= now)
    }

    private fun program() = Program(
        id = "custom_1", name = "Mine", description = "d", goals = setOf(Goal.GET_STRONGER), level = Experience.INTERMEDIATE,
        daysPerWeek = 3, isBuiltIn = false,
        days = listOf(ProgramDay("custom_1/d0_x", "A", listOf(ExerciseSlot("bench_press", 5, RepTarget.Amrap(3), lastSetAmrap = true, note = "n")))),
    )
}
