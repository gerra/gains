package app.gains.root

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import app.gains.data.AppLanguage
import app.gains.domain.Experience
import app.gains.domain.Program
import app.gains.domain.ProgramDay
import app.gains.domain.ProgramDayRef
import app.gains.domain.ProgramLink
import app.gains.domain.ProgramState
import app.gains.platform.applyAppLanguage
import app.gains.ui.i18n.InLanguage
import app.gains.ui.i18n.Texts
import app.gains.ui.i18n.rememberTexts
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The "+" menu's next program day: which day, and in which words. A custom program whose days kept
 * the built-in names "Workout A" and "Workout B", so the name is translated, beside another program
 * that is not active.
 */
@OptIn(ExperimentalTestApi::class)
class UpNextTest {
    @AfterTest
    fun giveTheDeviceLanguageBack() = applyAppLanguage(null)

    /** The texts a composition in [language] reads its words with. */
    private fun textsIn(language: AppLanguage): Texts {
        var texts: Texts? = null
        runDesktopComposeUiTest { setContent { InLanguage(language) { texts = rememberTexts() } }; waitForIdle() }
        return checkNotNull(texts)
    }

    private fun program(id: String, vararg days: String) = Program(
        id, id, "", emptySet(), Experience.BEGINNER, days.size,
        days.map { ProgramDay("$id/$it", it, emptyList()) }, isBuiltIn = false,
    )

    private val ab = program("ab", "Workout A", "Workout B")
    private val other = program("other", "Legs")

    private fun done(program: Program, day: Int, on: Int) =
        ProgramLink("s-${program.id}-$on", LocalDateTime(2026, 9, on, 18, 0), ProgramDayRef(program.id, program.days[day].id))

    @Test
    fun noActiveProgramOffersNothing() {
        val english = textsIn(AppLanguage.ENGLISH)
        runTest {
            assertNull(findUpNext(ProgramState(programs = listOf(ab)), emptyList(), english))
            assertNull(findUpNext(ProgramState(programs = listOf(program("empty")), activeProgramId = "empty"), emptyList(), english))
        }
    }

    @Test
    fun theFirstDayUntilOneIsDoneThenTheOneAfterTheLast() {
        val english = textsIn(AppLanguage.ENGLISH)
        runTest {
            val state = ProgramState(programs = listOf(ab, other), activeProgramId = "ab")
            assertEquals(UpNext(ProgramDayRef("ab", "ab/Workout A"), "Workout A"), findUpNext(state, emptyList(), english))

            // Another program's session doesn't move this one on.
            val links = listOf(done(ab, day = 0, on = 1), done(other, day = 0, on = 2))
            assertEquals(UpNext(ProgramDayRef("ab", "ab/Workout B"), "Workout B"), findUpNext(state, links, english))

            // The rotation comes round again.
            assertEquals("ab/Workout A", findUpNext(state, links + done(ab, day = 1, on = 3), english)?.ref?.dayId)
        }
    }

    @Test
    fun theDayIsNamedInTheTextsLanguage() {
        val russian = textsIn(AppLanguage.RUSSIAN)
        runTest {
            val state = ProgramState(programs = listOf(ab), activeProgramId = "ab")
            assertEquals("Тренировка A", findUpNext(state, emptyList(), russian)?.dayName)
            // A name of its own stays as it is.
            val legs = ProgramState(programs = listOf(other), activeProgramId = "other")
            assertEquals("Legs", findUpNext(legs, emptyList(), russian)?.dayName)
        }
    }
}
