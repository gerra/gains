package app.gains

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import app.gains.ui.nav.Navigator
import app.gains.ui.nav.Screen
import app.gains.ui.nav.ScreenStack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How the frame shows the stack: which screens are drawn as one gives way to the next or is swiped
 * away, and that a screen keeps its saved state however it comes back on show.
 */
@OptIn(ExperimentalTestApi::class)
class ScreenStackTest {
    private val width = 400
    private val height = 800

    /** Every screen is a long list under its entry's saved state; [scroll] is how far each entry's list was scrolled when last drawn. */
    private class Harness(val ui: ComposeUiTest, val navigator: Navigator) {
        val scroll = mutableMapOf<Int, Int>()

        fun frames(count: Int) = repeat(count) { ui.mainClock.advanceTimeByFrame() }
        fun settle() = frames(60)

        /** The ids of the entries drawn now. */
        fun onShow(): Set<Int> = ui.onAllNodes(hasText(": row ", substring = true)).fetchSemanticsNodes()
            .map { it.config[SemanticsProperties.Text].first().text.substringBefore(':').toInt() }
            .toSet()

        /** Scrolls the only list drawn, which must be the top screen's, so that [row] is the first one shown. */
        fun scrollTo(row: Int) {
            val list = ui.onAllNodes(hasScrollAction()).fetchSemanticsNodes().single()
            ui.runOnUiThread { list.config[SemanticsActions.ScrollToIndex].action?.invoke(row) }
            settle()
        }

        fun dragFromEdge(steps: Int, release: Boolean) = ui.onRoot().performTouchInput {
            repeat(steps) { moveBy(Offset(15f, 0f)); advanceEventTime(16) }
            if (release) up()
        }
    }

    private fun stack(body: Harness.() -> Unit) = runDesktopComposeUiTest(width, height) {
        val navigator = Navigator()
        val harness = Harness(this, navigator)
        // Driven frame by frame, so a test can stop a transition halfway.
        mainClock.autoAdvance = false
        setContent {
            val holder = rememberSaveableStateHolder()
            ScreenStack(navigator, Modifier.fillMaxSize()) { entry ->
                // The real screens are behind Koin; any content under the entry's saved state will do.
                holder.SaveableStateProvider(entry.id) {
                    val list = rememberLazyListState()
                    harness.scroll[entry.id] = list.firstVisibleItemIndex
                    LazyColumn(Modifier.fillMaxSize(), state = list) {
                        items(200) { Box(Modifier.fillMaxWidth().height(80.dp)) { Text("${entry.id}: row $it") } }
                    }
                }
            }
        }
        harness.settle()
        harness.body()
    }

    @Test
    fun screensGoingOutStayUntilTheyHaveGoneWhenAnotherNavigationInterrupts() = stack {
        val home = navigator.currentEntry.id
        navigator.push(Screen.Settings)
        val settings = navigator.currentEntry.id
        frames(5)
        // Home is still going out and Settings still coming in when the lifter goes on to Licenses:
        // both go out from where they got to.
        navigator.push(Screen.Licenses)
        val licenses = navigator.currentEntry.id
        frames(1)
        assertEquals(setOf(home, settings, licenses), onShow())
        settle()
        assertEquals(setOf(licenses), onShow())
    }

    @Test
    fun aScreenBroughtBackWhileGoingOutKeepsItsState() = stack {
        val home = navigator.currentEntry.id
        scrollTo(40)
        navigator.push(Screen.Settings)
        frames(4)
        navigator.pop()
        settle()
        assertEquals(setOf(home), onShow())
        assertEquals(40, scroll[home])
    }

    @Test
    fun aSwipeBackShowsTheScreenUnderneathAsItWasLeft() = stack {
        val home = navigator.currentEntry.id
        scrollTo(40)
        navigator.push(Screen.Settings)
        settle()
        assertEquals(setOf(navigator.currentEntry.id), onShow())

        ui.onRoot().performTouchInput { down(Offset(4f, height / 2f)) }
        dragFromEdge(steps = 12, release = false)
        frames(4)
        assertTrue(home in onShow())
        assertEquals(40, scroll[home], "Home underneath the swipe")
        dragFromEdge(steps = 12, release = true)
        settle()
        assertEquals(listOf(Screen.Home), navigator.stack.map { it.screen })
        assertEquals(setOf(home), onShow())
        assertEquals(40, scroll[home])
    }

    @Test
    fun aSwipeWhileAScreenIsStillComingInIsIgnored() = stack {
        navigator.push(Screen.Settings)
        frames(3)
        ui.onRoot().performTouchInput { down(Offset(4f, height / 2f)) }
        dragFromEdge(steps = 24, release = true)
        settle()
        assertEquals(listOf(Screen.Home, Screen.Settings), navigator.stack.map { it.screen })
    }
}
