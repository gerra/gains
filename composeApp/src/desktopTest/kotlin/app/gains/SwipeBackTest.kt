package app.gains

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.swipe
import app.gains.ui.nav.SwipeBack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exercises the edge-swipe gesture behind swipe-to-go-back with synthetic touch input. */
@OptIn(ExperimentalTestApi::class)
class SwipeBackTest {
    private val width = 400
    private val height = 800

    /** What the tests observe: how often the gesture went back, and whether the list underneath moved. */
    private class Harness(val ui: ComposeUiTest) {
        var backs = 0
        var list: LazyListState? = null
        val scrolled: Boolean get() = list?.let { it.firstVisibleItemIndex > 0 || it.firstVisibleItemScrollOffset > 0 } ?: false
    }

    private fun swipeBack(enabled: Boolean = true, body: Harness.() -> Unit) = runDesktopComposeUiTest(width, height) {
        val harness = Harness(this)
        setContent {
            SwipeBack(enabled = enabled, onBack = { harness.backs++ }, previous = { Text("previous") }) {
                val state = rememberLazyListState().also { harness.list = it }
                LazyColumn(Modifier.fillMaxSize(), state = state) {
                    items(200) { Box(Modifier.fillMaxWidth().height(80.dp)) { Text("row $it") } }
                }
            }
        }
        harness.body()
    }

    @Test
    fun dragFromLeftEdgePastThresholdGoesBack() = swipeBack {
        ui.onRoot().performTouchInput { swipe(Offset(4f, height / 2f), Offset(width * 0.7f, height / 2f), durationMillis = 300) }
        ui.waitForIdle()
        assertEquals(1, backs)
    }

    @Test
    fun quickFlickFromEdgeGoesBackEvenIfShort() = swipeBack {
        ui.onRoot().performTouchInput { swipe(Offset(4f, height / 2f), Offset(width * 0.25f, height / 2f), durationMillis = 60) }
        ui.waitForIdle()
        assertEquals(1, backs)
    }

    @Test
    fun shortSlowDragSpringsBack() = swipeBack {
        ui.onRoot().performTouchInput { swipe(Offset(4f, height / 2f), Offset(width * 0.15f, height / 2f), durationMillis = 1500) }
        ui.waitForIdle()
        assertEquals(0, backs)
    }

    @Test
    fun dragStartingAwayFromEdgeIsIgnored() = swipeBack {
        ui.onRoot().performTouchInput { swipe(Offset(width / 2f, height / 2f), Offset(width * 0.95f, height / 2f), durationMillis = 300) }
        ui.waitForIdle()
        assertEquals(0, backs)
    }

    @Test
    fun verticalDragFromEdgeScrollsTheListInstead() = swipeBack {
        ui.onRoot().performTouchInput { swipe(Offset(4f, height * 0.8f), Offset(4f, height * 0.2f), durationMillis = 300) }
        ui.waitForIdle()
        assertEquals(0, backs)
        assertTrue(scrolled, "the list under the gesture should have scrolled")
    }

    @Test
    fun disabledContainerDoesNothing() = swipeBack(enabled = false) {
        ui.onRoot().performTouchInput { swipe(Offset(4f, height / 2f), Offset(width * 0.9f, height / 2f), durationMillis = 300) }
        ui.waitForIdle()
        assertEquals(0, backs)
    }
}
