package app.gains

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import app.gains.ui.components.WheelPicker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The wheel's value follows the finger, and a jump set from outside lands without stops on the way. */
@OptIn(ExperimentalTestApi::class)
class WheelPickerLiveTest {
    private val labels = List(101) { it.toString() }

    @Test
    fun aJumpFromOutsideReportsNoRowsOnTheWay() = runDesktopComposeUiTest(400, 600) {
        var value by mutableStateOf(30)
        val picks = mutableListOf<Int>()
        setContent { WheelPicker(labels, value, { picks += it; value = it }, Modifier.width(96.dp)) }
        waitForIdle()

        // As the +5 chip does: the wheel turns to 35 and nothing between is picked.
        value = 35
        waitForIdle()
        assertEquals(35, value)
        assertEquals(emptyList(), picks)
    }

    @Test
    fun aDragPicksBeforeTheSnapSettles() = runDesktopComposeUiTest(400, 600) {
        var value by mutableStateOf(30)
        setContent { WheelPicker(labels, value, { value = it }, Modifier.width(96.dp).testTag("wheel")) }
        waitForIdle()

        mainClock.autoAdvance = false
        onNodeWithTag("wheel").performTouchInput { swipeUp(durationMillis = 200) }
        // A couple of frames after the finger lifts, well before any snap animation has finished.
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()
        val early = value
        assertNotEquals(30, early)

        mainClock.autoAdvance = true
        waitForIdle()
        assertTrue(value > 30)
    }
}
