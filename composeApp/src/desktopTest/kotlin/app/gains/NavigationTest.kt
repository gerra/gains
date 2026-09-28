package app.gains

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import app.gains.data.AppLanguage
import app.gains.ui.ScreenModel
import app.gains.ui.i18n.LocalAppLanguage
import app.gains.ui.nav.LocalNavEntry
import app.gains.ui.nav.NavEntry
import app.gains.ui.nav.Navigator
import app.gains.ui.nav.Screen
import app.gains.ui.nav.Tab
import app.gains.ui.rememberScreenModel
import kotlinx.coroutines.isActive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A screen's state survives being covered by another screen, and goes once the screen is gone for good. */
@OptIn(ExperimentalTestApi::class)
class NavigationTest {
    private class Model : ScreenModel()
    private class Other : ScreenModel()

    private fun NavEntry.model(vararg keys: Any?) = model(Model::class, keys.toList()) { Model() }

    @Test
    fun screenUnderneathKeepsItsModelWhileCovered() {
        val navigator = Navigator()
        val home = navigator.currentEntry
        val model = home.model()
        navigator.push(Screen.Settings)
        navigator.pop()
        assertSame(home, navigator.currentEntry)
        assertSame(model, home.model())
        assertTrue(model.scope.isActive)
    }

    @Test
    fun peekFindsAModelWithoutMakingOne() {
        val navigator = Navigator()
        val home = navigator.currentEntry
        assertEquals(null, home.peek(Model::class))
        val model = home.model()
        assertSame(model, home.peek(Model::class))
        assertEquals(null, home.peek(Other::class))
    }

    @Test
    fun poppedScreenIsReleasedOnceItIsOffTheScreenToo() {
        val released = mutableListOf<NavEntry>()
        val navigator = Navigator(onReleased = { released += it })
        navigator.push(Screen.Settings)
        val settings = navigator.currentEntry
        val model = settings.model()
        settings.attach()
        navigator.pop()
        // Still sliding out: nothing is torn down under it.
        assertTrue(model.scope.isActive)
        assertTrue(released.isEmpty())
        settings.detach()
        assertFalse(model.scope.isActive)
        assertEquals(listOf(settings), released)
    }

    @Test
    fun poppedScreenThatIsNotOnScreenIsReleasedAtOnce() {
        val released = mutableListOf<NavEntry>()
        val navigator = Navigator(onReleased = { released += it })
        navigator.push(Screen.Settings)
        val settings = navigator.currentEntry
        val model = settings.model()
        navigator.pop()
        assertFalse(model.scope.isActive)
        assertEquals(listOf(settings), released)
    }

    /** An ended workout's editor hands over to its summary: Back from there leaves for what came before. */
    @Test
    fun replaceTakesTheTopScreensPlace() {
        val released = mutableListOf<NavEntry>()
        val navigator = Navigator(onReleased = { released += it })
        val home = navigator.currentEntry
        navigator.push(Screen.EditSession(null, live = true))
        val editor = navigator.currentEntry
        val editorModel = editor.model()
        navigator.replace(Screen.SessionSummary("2026-03-01T10:00"))

        assertEquals(Screen.SessionSummary("2026-03-01T10:00"), navigator.current)
        assertEquals(2, navigator.stack.size)
        // The editor is gone for good rather than waiting underneath.
        assertEquals(listOf(editor), released)
        assertFalse(editorModel.scope.isActive)
        navigator.pop()
        assertSame(home, navigator.currentEntry)
    }

    @Test
    fun tabRootsAreKeptAcrossTabSwitches() {
        val released = mutableListOf<NavEntry>()
        val navigator = Navigator(onReleased = { released += it })
        val home = navigator.currentEntry
        val homeModel = home.model()
        navigator.push(Screen.Settings)
        val settings = navigator.currentEntry
        navigator.switchTab(Tab.HISTORY)
        val history = navigator.currentEntry
        assertEquals(Screen.History, history.screen)
        assertEquals(listOf(settings), released)
        assertTrue(homeModel.scope.isActive)
        navigator.switchTab(Tab.HOME)
        assertSame(home, navigator.currentEntry)
        assertSame(homeModel, home.model())
        navigator.switchTab(Tab.HISTORY)
        assertSame(history, navigator.currentEntry)
        assertEquals(listOf(settings), released)
    }

    @Test
    fun modelIsRemadeWhenItsKeysChange() {
        val entry = Navigator().currentEntry
        val first = entry.model("a")
        assertSame(first, entry.model("a"))
        val second = entry.model("b")
        assertNotSame(first, second)
        assertFalse(first.scope.isActive)
        assertTrue(second.scope.isActive)
    }

    @Test
    fun oneModelPerClass() {
        val entry = Navigator().currentEntry
        val model = entry.model()
        val other = entry.model(Other::class, emptyList()) { Other() }
        assertSame(model, entry.model())
        assertSame(other, entry.model(Other::class, emptyList()) { Other() })
    }

    @Test
    fun rememberedModelComesFromTheEntryAndIsSharedByEveryPlaceTheScreenIsDrawn() = runDesktopComposeUiTest {
        val entry = Navigator().currentEntry
        val seen = mutableSetOf<Model>()
        var covered by mutableStateOf(false)
        setContent {
            CompositionLocalProvider(LocalNavEntry provides entry) {
                if (!covered) {
                    seen += rememberScreenModel { Model() }
                    seen += rememberScreenModel { Model() }
                    Text("screen")
                } else {
                    Text("covering screen")
                }
            }
        }
        waitForIdle()
        covered = true
        waitForIdle()
        covered = false
        waitForIdle()
        assertEquals(1, seen.size, "the same model before and after being covered, and in both places it is drawn")
        assertTrue(seen.single().scope.isActive)
    }

    @Test
    fun outsideTheNavigatorTheModelLivesWithTheComposable() = runDesktopComposeUiTest {
        val seen = mutableListOf<Model>()
        var shown by mutableStateOf(true)
        setContent {
            if (shown) {
                seen += rememberScreenModel { Model() }
                Text("screen")
            }
        }
        waitForIdle()
        shown = false
        waitForIdle()
        assertFalse(seen.single().scope.isActive)
        shown = true
        waitForIdle()
        assertEquals(2, seen.distinct().size)
    }

    /**
     * The leak check. Whatever order the lifter moves in, with screens still sliding out or drawn
     * under a swipe back, an entry is released once, and only once it has left both the stack and
     * the screen; a tab root never is. The moves are random but seeded, so a failure fails the same
     * way on every run and says at which seed and step.
     */
    @Test
    fun everyEntryIsReleasedExactlyOnceAndOnlyWhenGone() {
        for (seed in 1..5) replayRandomNavigation(seed, steps = 300)
    }

    private fun replayRandomNavigation(seed: Int, steps: Int) {
        val random = Random(seed)
        val releases = mutableMapOf<NavEntry, Int>()
        val navigator = Navigator(onReleased = { releases[it] = (releases[it] ?: 0) + 1 })
        // Every entry gets a model as it comes on top, so every entry ever made is a key here.
        val models = mutableMapOf<NavEntry, Model>()
        val roots = mutableMapOf(Tab.HOME to navigator.currentEntry)
        // One element per attach not yet detached: the transition and the swipe back can host an entry twice.
        val hosted = mutableListOf<NavEntry>()
        // A tab root's screen among them, pushed on another tab, is an ordinary entry and not that tab's root.
        val screens = listOf(
            Screen.Settings, Screen.Import, Screen.Programs, Screen.Trophies, Screen.History,
            Screen.ExerciseDetail("a"), Screen.ExerciseDetail("b"),
        )

        fun assertInvariants(at: String) {
            val kept = navigator.stack.toSet() + roots.values + hosted
            releases.forEach { (entry, count) -> assertEquals(1, count, "$at: ${entry.screen} released $count times") }
            assertEquals(models.keys - kept, releases.keys, "$at: released must be exactly the entries off the stack and the screen")
            models.forEach { (entry, model) ->
                assertEquals(entry in kept, model.scope.isActive, "$at: ${entry.screen}'s model active while it is kept, and only then")
            }
            assertEquals(models.size, models.keys.map { it.id }.toSet().size, "$at: entry ids are unique")
        }

        repeat(steps) { step ->
            when (random.nextInt(8)) {
                0, 1 -> navigator.push(screens.random(random))
                2 -> navigator.pop(animated = random.nextBoolean())
                3 -> navigator.replace(screens.random(random))
                4 -> Tab.entries.random(random).let { navigator.switchTab(it); roots[it] = navigator.currentEntry }
                // The transition hosting the screen on top, or the swipe back drawing the one beneath it.
                5 -> navigator.currentEntry.let { it.attach(); hosted += it }
                6 -> navigator.previousEntry?.let { it.attach(); hosted += it }
                7 -> if (hosted.isNotEmpty()) hosted.removeAt(random.nextInt(hosted.size)).detach()
            }
            navigator.currentEntry.let { entry -> models.getOrPut(entry) { entry.model() } }
            assertInvariants("seed $seed, step $step")
        }
        while (hosted.isNotEmpty()) hosted.removeAt(hosted.lastIndex).detach()
        assertInvariants("seed $seed, all detached")
    }

    /** The swipe back draws the previous screen under the current one while the transition still hosts it. */
    @Test
    fun entryDrawnTwiceIsReleasedAfterItsLastHostLetsGo() {
        val released = mutableListOf<NavEntry>()
        val navigator = Navigator(onReleased = { released += it })
        navigator.push(Screen.Settings)
        val settings = navigator.currentEntry
        val model = settings.model()
        settings.attach()
        settings.attach()
        navigator.pop(animated = false)
        settings.detach()
        assertTrue(model.scope.isActive)
        assertTrue(released.isEmpty())
        settings.detach()
        assertFalse(model.scope.isActive)
        assertEquals(listOf(settings), released)
    }

    /** A tab tapped while screens are still on their way out: each goes once it is off the screen, not before. */
    @Test
    fun screensLeftByATabSwitchAreReleasedAsTheyDetach() {
        val released = mutableListOf<NavEntry>()
        val navigator = Navigator(onReleased = { released += it })
        val homeModel = navigator.currentEntry.model()
        navigator.push(Screen.Settings)
        val settings = navigator.currentEntry
        navigator.push(Screen.Import)
        val import = navigator.currentEntry
        import.attach()
        settings.attach()
        navigator.switchTab(Tab.HISTORY)
        assertTrue(released.isEmpty())
        import.detach()
        assertEquals(listOf(import), released)
        settings.detach()
        assertEquals(listOf(import, settings), released)
        assertTrue(homeModel.scope.isActive)
    }

    @Test
    fun switchingToTheTabShownDropsWhatWasPushedOnItAndKeepsItsRoot() {
        val released = mutableListOf<NavEntry>()
        val navigator = Navigator(onReleased = { released += it })
        navigator.switchTab(Tab.HISTORY)
        val history = navigator.currentEntry
        val historyModel = history.model()
        navigator.push(Screen.Settings)
        val settings = navigator.currentEntry
        navigator.switchTab(Tab.HISTORY)
        assertEquals(listOf(history), navigator.stack.toList())
        assertEquals(listOf(settings), released)
        assertSame(historyModel, history.model())
        assertTrue(historyModel.scope.isActive)
        // Back to the root of the same tab is a way back.
        assertEquals(-1, navigator.direction)
    }

    @Test
    fun pushingTheScreenAlreadyOnTopAddsNothing() {
        val navigator = Navigator()
        val home = navigator.currentEntry
        navigator.push(Screen.Home)
        assertEquals(listOf(home), navigator.stack.toList())
        navigator.push(Screen.ExerciseDetail("a"))
        val detail = navigator.currentEntry
        navigator.push(Screen.ExerciseDetail("a"))
        assertEquals(listOf(home, detail), navigator.stack.toList())
        // An equal screen further down is no reason not to push.
        navigator.push(Screen.ExerciseDetail("b"))
        navigator.push(Screen.ExerciseDetail("a"))
        assertEquals(4, navigator.stack.size)
    }

    /** Saved UI state is kept under an entry's id, so no two entries may ever share one. */
    @Test
    fun entryIdsAreNeverReused() {
        val navigator = Navigator()
        val ids = mutableListOf(navigator.currentEntry.id)
        for (tab in Tab.entries.drop(1)) {
            navigator.push(Screen.Settings)
            ids += navigator.currentEntry.id
            navigator.replace(Screen.Import)
            ids += navigator.currentEntry.id
            navigator.pop()
            navigator.switchTab(tab)
            ids += navigator.currentEntry.id
            navigator.switchTab(Tab.HOME)
        }
        assertEquals(ids.distinct(), ids)
    }

    /**
     * A change of language remakes the models, since a model holds words in the language that made
     * it, but it is not a navigation: the entry, the stack and the entry's saved UI state stay. The
     * saved state goes with the entry once it is released. Arranged the way `App.kt` is: the
     * navigator and the [SaveableStateHolder] above the language, the screens below it.
     */
    @Test
    fun aChangeOfLanguageRemakesTheModelAndKeepsTheEntryAndItsSavedState() = runDesktopComposeUiTest {
        val released = mutableListOf<NavEntry>()
        lateinit var stateHolder: SaveableStateHolder
        val navigator = Navigator(onReleased = { released += it; stateHolder.removeState(it.id) })
        val home = navigator.currentEntry
        navigator.push(Screen.Settings)
        val settings = navigator.currentEntry
        var language by mutableStateOf(AppLanguage.ENGLISH)
        var shown by mutableStateOf(settings)
        // Reads what the holder keeps under the settings entry's id, without drawing the entry.
        var probing by mutableStateOf(false)
        val models = mutableListOf<Model>()
        var saved: MutableState<Int>? = null
        var probed: Int? = null
        setContent {
            stateHolder = rememberSaveableStateHolder()
            CompositionLocalProvider(LocalAppLanguage provides language) {
                key(language) {
                    val entry = shown
                    DisposableEffect(entry) {
                        entry.attach()
                        onDispose { entry.detach() }
                    }
                    CompositionLocalProvider(LocalNavEntry provides entry) {
                        stateHolder.SaveableStateProvider(entry.id) {
                            val model = rememberScreenModel { Model() }
                            val value = rememberSaveable(key = "value") { mutableStateOf(0) }
                            if (entry === settings) {
                                if (model !in models) models += model
                                saved = value
                            }
                        }
                    }
                }
            }
            if (probing) stateHolder.SaveableStateProvider(settings.id) { probed = rememberSaveable(key = "value") { mutableStateOf(0) }.value }
        }
        waitForIdle()
        saved!!.value = 7
        waitForIdle()

        // Covered, the screen's state is kept under its entry's id, and a change of language leaves it
        // there. Each change is a frame of its own: the holder saves a screen's state as it leaves.
        shown = home
        waitForIdle()
        language = AppLanguage.RUSSIAN
        waitForIdle()
        probing = true
        waitForIdle()
        assertEquals(7, probed)
        probing = false
        waitForIdle()

        // Drawn again, in the new language: a new model, and the saved value as it was left.
        shown = settings
        waitForIdle()
        assertEquals(2, models.size, "the model is made again in the new language")
        assertFalse(models[0].scope.isActive)
        assertTrue(models[1].scope.isActive)
        assertSame(models[1], settings.peek(Model::class))
        assertEquals(7, saved!!.value)
        assertEquals(listOf(home, settings), navigator.stack.toList())
        assertTrue(released.isEmpty())

        // Looks wrong, kept as today's behaviour (launch plan, item 32): changed while the screen is
        // drawn, the language loses that screen's saved state. `key(language)` makes the screen's new
        // registry, which restores from what the holder has saved, before the old one has saved.
        language = AppLanguage.ENGLISH
        waitForIdle()
        assertEquals(3, models.size)
        assertSame(settings, navigator.currentEntry)
        assertEquals(0, saved!!.value)

        saved!!.value = 9
        waitForIdle()
        shown = home
        waitForIdle()
        probing = true
        waitForIdle()
        assertEquals(9, probed)
        probing = false
        waitForIdle()
        navigator.pop()
        probing = true
        waitForIdle()
        assertEquals(listOf(settings), released)
        assertFalse(models[2].scope.isActive)
        assertEquals(0, probed, "the saved state is dropped with the entry")
    }

    /** How the "Skip rest" notice finds the editor running the workout: on any entry on the stack, never on one gone. */
    @Test
    fun aModelIsFoundOnACoveredEntryAndNotOnAReleasedOne() {
        val navigator = Navigator()
        navigator.push(Screen.EditSession(null, live = true))
        val running = navigator.currentEntry.model()
        navigator.push(Screen.EditSession("2026-03-01T10:00"))
        val past = navigator.currentEntry
        val pastModel = past.model()
        navigator.push(Screen.Settings)
        assertEquals(listOf(running, pastModel), navigator.stack.mapNotNull { it.peek(Model::class) })
        navigator.pop()
        navigator.pop()
        assertEquals(listOf(running), navigator.stack.mapNotNull { it.peek(Model::class) })
        assertEquals(null, past.peek(Model::class))
    }
}
