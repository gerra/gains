package app.gains.ui.nav

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.gains.domain.ProgramDayRef

sealed interface Screen {
    data object Home : Screen
    data object Exercises : Screen
    data object Volume : Screen
    data object Body : Screen
    data object History : Screen
    data object Settings : Screen
    data object Import : Screen
    data object Onboarding : Screen
    data object Programs : Screen
    data class ProgramDetail(val programId: String) : Screen
    /** null = create a new program. */
    data class ProgramEditor(val programId: String?) : Screen
    data class ExerciseDetail(val exerciseId: String) : Screen
    /** null = log a new workout; [programDay] pre-fills it from a program day. */
    data class EditSession(val sessionId: String?, val programDay: ProgramDayRef? = null) : Screen
}

enum class Tab(val label: String, val root: Screen) {
    HOME("Home", Screen.Home),
    HISTORY("History", Screen.History),
    EXERCISES("Lifts", Screen.Exercises),
    VOLUME("Volume", Screen.Volume),
    BODY("Body", Screen.Body),
}

class Navigator {
    val stack = mutableStateListOf<Screen>(Screen.Home)
    val current: Screen get() = stack.last()
    /** The screen a back navigation would reveal, or null at the root of a tab. */
    val previous: Screen? get() = stack.getOrNull(stack.size - 2)
    val canGoBack: Boolean get() = stack.size > 1

    /**
     * True when the most recent change to [current] should appear without a transition, because a
     * gesture already moved the old screen out of the way. Cleared by the next navigation.
     */
    var skipTransition: Boolean by mutableStateOf(false)
        private set

    fun push(screen: Screen) {
        skipTransition = false
        if (current != screen) stack.add(screen)
    }

    /** Removes the top screen. Pass [animated] = false when the caller has already animated it away. */
    fun pop(animated: Boolean = true): Boolean {
        if (stack.size <= 1) return false
        skipTransition = !animated
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun switchTab(tab: Tab) {
        skipTransition = false
        stack.clear()
        stack.add(tab.root)
    }

    val currentTab: Tab? get() = Tab.entries.firstOrNull { it.root == stack.first() }
}
