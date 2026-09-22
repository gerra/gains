package app.gains

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import app.gains.analysis.Streak
import app.gains.analysis.StreakNudge
import app.gains.analysis.StreakStatus
import app.gains.ui.i18n.Texts
import app.gains.ui.i18n.nudgeWords
import app.gains.ui.i18n.rememberTexts
import app.gains.ui.i18n.streakLine
import app.gains.ui.theme.GainsTheme
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The words the streak nudge uses. The promise is that it never overstates what is at stake: it
 * does not talk about days left in a week that has already been trained, and it does not say the
 * run is about to end while a rest week would still carry it.
 *
 * The sentences are read inside a composition, where the string resources know their language, and
 * asserted outside it. Nothing here looks up a node, so the at-risk card's animation is never waited on.
 */
@OptIn(ExperimentalTestApi::class)
class StreakWordsTest {
    private fun streak(
        weeks: Int = 6,
        status: StreakStatus,
        daysLeft: Int = 7,
        sessionsThisWeek: Int = 0,
        goal: Int = 3,
        rest: Int = 0,
    ) = Streak(
        weeks = weeks,
        best = weeks,
        restWeeksInHand = rest,
        sessionsThisWeek = sessionsThisWeek,
        goalPerWeek = goal,
        daysLeftInWeek = daysLeft,
        status = status,
    )

    /** Reads sentences in the composition and keeps them for the assertions. */
    private class Lines {
        private val lines = mutableMapOf<String, String>()
        @Composable fun read(key: String, streak: Streak) { lines[key] = streakLine(streak) }
        operator fun get(key: String): String = lines.getValue(key)
    }

    @Test
    fun theSentenceMatchesWhatIsActuallyAtStake() = runDesktopComposeUiTest(400, 800) {
        val lines = Lines()
        setContent {
            GainsTheme {
                lines.read("none", streak(weeks = 0, status = StreakStatus.NONE))
                lines.read("full", streak(status = StreakStatus.SAFE, sessionsThisWeek = 3))
                lines.read("safe", streak(status = StreakStatus.SAFE, sessionsThisWeek = 2))
                lines.read("open", streak(status = StreakStatus.OPEN, daysLeft = 5))
                lines.read("risk", streak(status = StreakStatus.AT_RISK, daysLeft = 2))
                lines.read("last", streak(status = StreakStatus.LAST_CHANCE, daysLeft = 1))
                lines.read("risk-rested", streak(status = StreakStatus.AT_RISK, daysLeft = 2, rest = 1))
                lines.read("last-rested", streak(status = StreakStatus.LAST_CHANCE, daysLeft = 1, rest = 2))
            }
        }
        assertEquals("Train this week to start a streak.", lines["none"])
        assertEquals("Full week done. The streak is safe.", lines["full"])
        assertEquals("The streak is safe. 1 session to a full week.", lines["safe"])
        assertEquals("5 days left to keep the streak.", lines["open"])
        assertEquals("2 days left to keep the streak.", lines["risk"])
        assertEquals("Last day to keep the streak.", lines["last"])
        // With a rest week banked the run is not about to end, and the sentence does not pretend it is.
        assertTrue("rest week" in lines["risk-rested"], lines["risk-rested"])
        assertTrue("rest week" in lines["last-rested"], lines["last-rested"])
    }

    @Test
    fun theReminderSaysHowLongTheRunIsAndWhatAMissWouldCost() = runDesktopComposeUiTest(400, 800) {
        var texts: Texts? = null
        setContent { GainsTheme { texts = rememberTexts() } }
        val resolved = checkNotNull(texts)
        val saturday = LocalDateTime(2026, 9, 5, 18, 0)
        val sunday = LocalDateTime(2026, 9, 6, 18, 0)

        val (keepTitle, keepBody) = runBlocking {
            nudgeWords(resolved, StreakNudge(StreakNudge.Kind.KEEP_ALIVE, saturday, streak(weeks = 12, status = StreakStatus.AT_RISK, daysLeft = 2)))
        }
        assertEquals("Two days to keep the streak", keepTitle)
        assertTrue("12 weeks" in keepBody, keepBody)
        assertTrue("rest week" !in keepBody, keepBody)

        val (lastTitle, lastBody) = runBlocking {
            nudgeWords(resolved, StreakNudge(StreakNudge.Kind.LAST_DAY, sunday, streak(weeks = 1, status = StreakStatus.LAST_CHANCE, daysLeft = 1)))
        }
        assertEquals("Last day of the week", lastTitle)
        assertTrue("1 week" in lastBody, lastBody)

        // A rest week in hand changes what the reminder can honestly claim.
        val (_, restedBody) = runBlocking {
            nudgeWords(resolved, StreakNudge(StreakNudge.Kind.LAST_DAY, sunday, streak(weeks = 12, status = StreakStatus.LAST_CHANCE, daysLeft = 1, rest = 1)))
        }
        assertTrue("rest week" in restedBody, restedBody)
    }
}
