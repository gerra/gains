package app.gains

import app.gains.domain.WeightUnit
import app.gains.ui.components.WheelWeight
import kotlin.test.Test
import kotlin.test.assertEquals

/** The weight chooser's wheels: what a set row's text becomes on them, and what they hand back. */
class WheelWeightTest {
    @Test
    fun splitsARowIntoWholeUnitsAndQuarters() {
        assertEquals(WheelWeight(62, 2), WheelWeight.parse("62.5"))
        assertEquals(WheelWeight(62, 1), WheelWeight.parse("62,25"))
        assertEquals(WheelWeight(60, 0), WheelWeight.parse("60"))
        assertEquals(WheelWeight(0, 0), WheelWeight.parse(""))
        assertEquals(WheelWeight(0, 0), WheelWeight.parse("abc"))
    }

    @Test
    fun roundsToTheNearestQuarter() {
        assertEquals(WheelWeight(62, 1), WheelWeight.parse("62.3"))
        assertEquals(WheelWeight(63, 0), WheelWeight.parse("62.9"))
        assertEquals(WheelWeight(0, 0), WheelWeight.parse("-5"))
    }

    @Test
    fun writesTheRowBackWithoutTrailingZeros() {
        assertEquals("62.5", WheelWeight(62, 2).text)
        assertEquals("62.25", WheelWeight(62, 1).text)
        assertEquals("60", WheelWeight(60, 0).text)
        // Zero is no added weight, which the row shows as empty.
        assertEquals("", WheelWeight(0, 0).text)
    }

    @Test
    fun plateJumpsStayWithinTheWheel() {
        val max = WheelWeight.max(WeightUnit.KG)
        assertEquals(WheelWeight(62, 2), WheelWeight(60, 0).plus(2.5, max))
        assertEquals(WheelWeight(0, 0), WheelWeight(2, 0).plus(-5.0, max))
        assertEquals(WheelWeight(max, 0), WheelWeight(max - 1, 0).plus(5.0, max))
    }

    @Test
    fun stepsFollowTheUnit() {
        assertEquals(listOf(-5.0, -2.5, 2.5, 5.0), WheelWeight.steps(WeightUnit.KG))
        assertEquals(listOf(-10.0, -5.0, 5.0, 10.0), WheelWeight.steps(WeightUnit.LBS))
    }
}
