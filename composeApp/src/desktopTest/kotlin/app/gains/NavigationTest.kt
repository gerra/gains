package app.gains

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import app.gains.ui.ScreenModel
import app.gains.ui.nav.LocalNavEntry
import app.gains.ui.nav.NavEntry
import app.gains.ui.nav.Navigator
import app.gains.ui.nav.Screen
import app.gains.ui.nav.Tab
import app.gains.ui.rememberScreenModel
import kotlinx.coroutines.isActive
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
}
