package app.gains.ui.nav

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import app.gains.domain.ProgramDayRef
import app.gains.ui.ScreenModel
import kotlin.reflect.KClass

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
    /**
     * null = log a new workout; [programDay] pre-fills it from a program day. [live] opens a timed
     * workout instead of logging a past one: it waits, ready, until Start is pressed (or resumes the
     * one already running), then the clock runs, sets tick off, and it is stored when the session is ended.
     */
    data class EditSession(val sessionId: String?, val programDay: ProgramDayRef? = null, val live: Boolean = false) : Screen
}

enum class Tab(val label: String, val root: Screen) {
    HOME("Home", Screen.Home),
    HISTORY("History", Screen.History),
    EXERCISES("Lifts", Screen.Exercises),
    VOLUME("Volume", Screen.Volume),
    BODY("Body", Screen.Body),
}

/**
 * One screen on the back stack, together with the state that belongs to it for as long as it is
 * there rather than only while it is drawn: its [ScreenModel]s, and (through [id]) the UI state
 * Compose saves for it, such as scroll positions. A screen covered by another one, or drawn
 * underneath it during a swipe back, therefore keeps everything it had and comes back without
 * reloading. The state goes when the entry has both left the stack and left the screen.
 */
class NavEntry internal constructor(val screen: Screen, val id: Int, private val onReleased: (NavEntry) -> Unit) {
    private class Held(val keys: List<Any?>, val model: ScreenModel)

    private val models = mutableMapOf<KClass<*>, Held>()
    private var retained = true
    private var hosts = 0

    /** The model of class [type] for this entry, made by [factory] the first time and again whenever [keys] change. */
    fun <T : ScreenModel> model(type: KClass<T>, keys: List<Any?>, factory: () -> T): T {
        val held = models[type]
        @Suppress("UNCHECKED_CAST")
        if (held != null && held.keys == keys) return held.model as T
        held?.model?.onCleared()
        return factory().also { models[type] = Held(keys, it) }
    }

    /** The screen is being drawn; it may be, more than once at a time, during a swipe back or a transition. */
    internal fun attach() { hosts++ }

    internal fun detach() {
        hosts--
        releaseIfDone()
    }

    /** The entry is off the stack for good. */
    internal fun retire() {
        retained = false
        releaseIfDone()
    }

    private fun releaseIfDone() {
        if (retained || hosts > 0) return
        models.values.forEach { it.model.onCleared() }
        models.clear()
        onReleased(this)
    }
}

/** The back-stack entry whose screen is being composed, or null outside the navigator (sign-in, onboarding at launch). */
val LocalNavEntry = staticCompositionLocalOf<NavEntry?> { null }

/**
 * The back stack. [onReleased] is told when an entry is gone for good, so that whatever else was
 * kept under its id can be dropped.
 */
class Navigator(private val onReleased: (NavEntry) -> Unit = {}) {
    private var nextId = 0
    private fun entry(screen: Screen) = NavEntry(screen, nextId++, onReleased)

    val stack = mutableStateListOf(entry(Screen.Home))
    /** Each tab's root, kept from the first visit on so that switching tabs brings it back as it was. */
    private val roots = mutableMapOf(Tab.HOME to stack.first())

    val currentEntry: NavEntry get() = stack.last()
    /** The entry a back navigation would reveal, or null at the root of a tab. */
    val previousEntry: NavEntry? get() = stack.getOrNull(stack.size - 2)
    val current: Screen get() = currentEntry.screen
    val previous: Screen? get() = previousEntry?.screen
    val canGoBack: Boolean get() = stack.size > 1

    /**
     * True when the most recent change to [current] should appear without a transition, because a
     * gesture already moved the old screen out of the way. Cleared by the next navigation.
     */
    var skipTransition: Boolean by mutableStateOf(false)
        private set

    fun push(screen: Screen) {
        skipTransition = false
        if (current != screen) stack.add(entry(screen))
    }

    /** Removes the top screen. Pass [animated] = false when the caller has already animated it away. */
    fun pop(animated: Boolean = true): Boolean {
        if (stack.size <= 1) return false
        skipTransition = !animated
        stack.removeAt(stack.lastIndex).retire()
        return true
    }

    fun switchTab(tab: Tab) {
        skipTransition = false
        val root = roots.getOrPut(tab) { entry(tab.root) }
        // Only the tab roots outlive this; anything pushed on top of the old one is gone.
        val leaving = stack.filter { it !in roots.values }
        stack.clear()
        stack.add(root)
        leaving.forEach { it.retire() }
    }

    val currentTab: Tab? get() = Tab.entries.firstOrNull { it.root == stack.first().screen }
}
