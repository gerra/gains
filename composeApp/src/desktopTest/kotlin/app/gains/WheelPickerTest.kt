package app.gains

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import app.gains.ui.components.WheelPicker
import app.gains.ui.components.WheelWeight
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Two wheels sharing one value, as the weight sheet lays them out: whole units on one and quarters
 * on the other. Each wheel writes its own column into the value the other wheel last set.
 */
@OptIn(ExperimentalTestApi::class)
class WheelPickerTest {
    private val quarters = listOf(".00", ".25", ".50", ".75")

    @Test
    fun turningOneWheelKeepsWhatTheOtherWheelSet() = runDesktopComposeUiTest(400, 600) {
        var weight = WheelWeight(32, 2)
        setContent {
            // As the weight sheet does: the row's text is the state, and each composition parses it afresh.
            var text by remember { mutableStateOf("32.5") }
            val current = WheelWeight.parse(text)
            weight = current
            Row {
                WheelPicker(List(101) { it.toString() }, current.whole, { text = WheelWeight(it, current.quarters).text }, Modifier.width(96.dp))
                WheelPicker(quarters, current.quarters, { text = WheelWeight(current.whole, it).text }, Modifier.width(80.dp))
            }
        }
        waitForIdle()
        assertEquals(WheelWeight(32, 2), weight)

        // Up to 34, then down to a round number: 34, not the 32 the sheet opened at.
        onNodeWithText("34").performClick()
        waitForIdle()
        assertEquals(WheelWeight(34, 2), weight)
        onNodeWithText(".00").performClick()
        waitForIdle()
        assertEquals(WheelWeight(34, 0), weight)

        // And back the other way: the whole wheel must keep the quarters at .00.
        onNodeWithText("35").performClick()
        waitForIdle()
        assertEquals(WheelWeight(35, 0), weight)
    }
}
