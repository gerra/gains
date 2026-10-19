package app.gains

import app.gains.ui.components.WheelHold
import kotlin.test.Test
import kotlin.test.assertEquals

/** The hold chooser's wheels: what a set row's seconds become on them, and what they hand back. */
class WheelHoldTest {
    @Test
    fun splitsARowIntoMinutesAndSeconds() {
        assertEquals(WheelHold(1, 30), WheelHold.parse("90"))
        assertEquals(WheelHold(0, 45), WheelHold.parse(" 45 "))
        assertEquals(WheelHold(0, 0), WheelHold.parse(""))
        assertEquals(WheelHold(0, 0), WheelHold.parse("abc"))
    }

    @Test
    fun writesTheRowBackInSeconds() {
        assertEquals("90", WheelHold(1, 30).text)
        assertEquals("45", WheelHold(0, 45).text)
        // Zero is no hold yet, which the row shows as empty.
        assertEquals("", WheelHold(0, 0).text)
    }

    @Test
    fun jumpsStayWithinTheWheels() {
        assertEquals(WheelHold(1, 0), WheelHold(0, 45).plus(15))
        assertEquals(WheelHold(0, 0), WheelHold(0, 10).plus(-15))
        assertEquals(WheelHold.of(WheelHold.MAX_SECONDS), WheelHold(59, 50).plus(15))
        assertEquals(WheelHold(59, 59), WheelHold.parse("100000"))
    }
}
