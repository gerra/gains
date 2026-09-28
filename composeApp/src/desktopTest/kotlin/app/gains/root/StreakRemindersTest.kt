package app.gains.root

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import app.gains.data.AppLanguage
import app.gains.data.DesktopDriverFactory
import app.gains.data.ProgramRepository
import app.gains.data.SessionRepository
import app.gains.data.SettingsRepository
import app.gains.data.ThemeMode
import app.gains.db.GainsDatabase
import app.gains.domain.Session
import app.gains.platform.Nudge
import app.gains.platform.NudgeScheduler
import app.gains.platform.applyAppLanguage
import app.gains.resources.Res
import app.gains.resources.nudge_keep_body
import app.gains.resources.nudge_keep_rest_body
import app.gains.resources.nudge_keep_title
import app.gains.resources.nudge_last_body
import app.gains.resources.nudge_last_rest_body
import app.gains.resources.nudge_last_title
import app.gains.resources.weeks
import app.gains.ui.i18n.InLanguage
import app.gains.ui.i18n.Texts
import app.gains.ui.i18n.rememberTexts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The streak reminders, as the root plans them: against real (in-memory) repositories, a recording
 * scheduler, a fixed clock in UTC, and the words of a real composition, in English unless a test
 * says otherwise.
 *
 * "Now" is Wednesday 2 September 2026 at noon. The lifter trained at 18:00 in each of the two weeks
 * before, so a run of two weeks is at stake while this week is still empty.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
class StreakRemindersTest {
    @AfterTest
    fun giveTheDeviceLanguageBack() = applyAppLanguage(null)

    private class Rig {
        private val db = GainsDatabase(DesktopDriverFactory(file = null).createDriver())
        val sessions = SessionRepository(db, Dispatchers.Unconfined)
        val settings = SettingsRepository(db, Dispatchers.Unconfined)
        val programs = ProgramRepository(db, settings, Dispatchers.Unconfined)
        /** Every plan handed to the platform, in order. */
        val scheduled = mutableListOf<List<Nudge>>()
        private val clock = object : Clock {
            override fun now(): Instant = LocalDateTime(2026, 9, 2, 12, 0).toInstant(TimeZone.UTC)
        }
        val reminders = StreakReminders(sessions, programs, settings, NudgeScheduler { scheduled += it }, clock, { TimeZone.UTC })

        suspend fun trainedAt(vararg at: LocalDateTime) =
            sessions.upsertAll(at.map { Session("s-$it", it, 45, emptyList(), Session.MANUAL) })
    }

    private suspend fun Rig.withARunAtStake() = trainedAt(LocalDateTime(2026, 8, 18, 18, 0), LocalDateTime(2026, 8, 25, 18, 0))

    private fun TestScope.started(rig: Rig, texts: Texts) {
        backgroundScope.launch { rig.reminders.run(texts) }
        runCurrent()
    }

    /**
     * The texts a composition in [language] reads its words with, with the reminder's strings
     * already read once. Compose resources load a string in a scope of their own, on a real
     * dispatcher, since Compose Multiplatform 1.12, and only a cached one comes back without
     * suspending; so the first read would finish outside runTest's virtual time and after the
     * runCurrent() that expects the plan.
     */
    private fun textsIn(language: AppLanguage): Texts {
        var texts: Texts? = null
        runDesktopComposeUiTest { setContent { InLanguage(language) { texts = rememberTexts() } }; waitForIdle() }
        return checkNotNull(texts).also { loaded ->
            runBlocking {
                loaded.plural(Res.plurals.weeks, 2, 2)
                with(Res.string) {
                    listOf(nudge_keep_title, nudge_keep_body, nudge_keep_rest_body, nudge_last_title, nudge_last_body, nudge_last_rest_body)
                }.forEach { loaded.get(it, "") }
            }
        }
    }

    private val saturday = LocalDateTime(2026, 9, 5, 18, 0).toInstant(TimeZone.UTC).toEpochMilliseconds()
    private val sunday = LocalDateTime(2026, 9, 6, 18, 0).toInstant(TimeZone.UTC).toEpochMilliseconds()

    @Test
    fun withTheReminderOffThePlanIsEmptyWhichCancelsIt() {
        val english = textsIn(AppLanguage.ENGLISH)
        runTest {
            val rig = Rig()
            rig.withARunAtStake()
            started(rig, english)
            // Never asked for: the setting is not there at all.
            assertEquals(listOf(emptyList()), rig.scheduled)

            rig.settings.setStreakReminder(true)
            runCurrent()
            assertEquals(2, rig.scheduled.last().size)

            rig.settings.setStreakReminder(false)
            runCurrent()
            assertEquals(emptyList(), rig.scheduled.last())
        }
    }

    @Test
    fun withTheReminderOnThePlanIsTheStreaksWithItsWords() {
        val english = textsIn(AppLanguage.ENGLISH)
        runTest {
            val rig = Rig()
            rig.withARunAtStake()
            rig.settings.setStreakReminder(true)
            started(rig, english)

            val (keep, last) = rig.scheduled.single()
            assertEquals("streak-keep_alive-2026-09-05", keep.id)
            assertEquals(saturday, keep.atEpochMs)
            assertEquals("Two days to keep the streak", keep.title)
            assertTrue("2 weeks" in keep.body, keep.body)
            assertEquals("streak-last_day-2026-09-06", last.id)
            assertEquals(sunday, last.atEpochMs)
            assertEquals("Last day of the week", last.title)
        }
    }

    @Test
    fun aWeekAlreadyTrainedPlansNothing() {
        val english = textsIn(AppLanguage.ENGLISH)
        runTest {
            val rig = Rig()
            rig.withARunAtStake()
            rig.settings.setStreakReminder(true)
            started(rig, english)
            assertEquals(2, rig.scheduled.last().size)

            // Monday's session keeps the run: the reminders still to come are cancelled.
            rig.trainedAt(LocalDateTime(2026, 8, 31, 7, 0))
            runCurrent()
            assertEquals(emptyList(), rig.scheduled.last())
        }
    }

    @Test
    fun anUnchangedPlanIsNotHandedOverAgain() {
        val english = textsIn(AppLanguage.ENGLISH)
        runTest {
            val rig = Rig()
            rig.withARunAtStake()
            rig.settings.setStreakReminder(true)
            started(rig, english)
            assertEquals(1, rig.scheduled.size)

            // Any write to the settings table wakes the setting and the program state it is combined
            // with; a plan that comes out the same as before is not handed to the platform again.
            rig.settings.setThemeMode(ThemeMode.LIGHT)
            rig.programs.markOnboardingDone()
            runCurrent()
            assertEquals(1, rig.scheduled.size)
        }
    }

    @Test
    fun aChangeOfTextsRewordsThePlan() {
        val english = textsIn(AppLanguage.ENGLISH)
        val russian = textsIn(AppLanguage.RUSSIAN)
        runTest {
            val rig = Rig()
            rig.withARunAtStake()
            rig.settings.setStreakReminder(true)
            // As the root does it: the effect keyed on the texts is cancelled and started again.
            val first = backgroundScope.launch { rig.reminders.run(english) }
            runCurrent()
            val inEnglish = rig.scheduled.last()
            first.cancel()
            started(rig, russian)
            val inRussian = rig.scheduled.last()

            assertEquals(inEnglish.map { it.id to it.atEpochMs }, inRussian.map { it.id to it.atEpochMs })
            assertEquals("Два дня, чтобы сохранить серию", inRussian[0].title)
            assertEquals("Последний день недели", inRussian[1].title)
            assertNotEquals(inEnglish[0].body, inRussian[0].body)
        }
    }
}
