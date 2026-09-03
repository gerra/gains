package app.gains.data

import app.gains.domain.Equipment
import app.gains.domain.Goal
import app.gains.domain.ProgressionRule
import app.gains.domain.RepTarget
import app.gains.domain.SetsReps
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProgramCodecTest {
    @Test
    fun repsRoundTrip() {
        for (r in listOf(RepTarget.Fixed(5), RepTarget.Range(8, 12), RepTarget.Amrap(5))) {
            assertEquals(r, ProgramCodec.decodeReps(ProgramCodec.encodeReps(r)))
        }
        assertEquals(RepTarget.Range(8, 12), ProgramCodec.decodeReps(" 8 – 12 "))
        assertEquals(RepTarget.Fixed(10), ProgramCodec.decodeReps("10-10"))
        assertNull(ProgramCodec.decodeReps("12-8"))
        assertNull(ProgramCodec.decodeReps("abc"))
        assertNull(ProgramCodec.decodeReps(""))
        assertNull(ProgramCodec.decodeReps("0+"))
    }

    @Test
    fun rulesRoundTrip() {
        val rules = listOf(
            ProgressionRule.None,
            ProgressionRule.Linear(2.5, 5.0),
            ProgressionRule.DoubleProgression(8, 12, 2.5, 5.0),
            ProgressionRule.StageLadder(listOf(SetsReps(5, RepTarget.Amrap(3)), SetsReps(6, RepTarget.Amrap(2)), SetsReps(10, RepTarget.Amrap(1))), 5.0, 10.0),
        )
        for (rule in rules) assertEquals(rule, ProgramCodec.decodeRule(ProgramCodec.encodeRule(rule)))
        assertEquals("ladder:5x3+|6x2+|10x1+:5/10", ProgramCodec.encodeRule(rules[3]))
        assertEquals("linear:2.5/5", ProgramCodec.encodeRule(rules[1]))
    }

    @Test
    fun garbageDecodesToNone() {
        assertEquals(ProgressionRule.None, ProgramCodec.decodeRule(null))
        assertEquals(ProgressionRule.None, ProgramCodec.decodeRule("wat:1"))
        assertEquals(ProgressionRule.None, ProgramCodec.decodeRule("linear:"))
        assertEquals(ProgressionRule.None, ProgramCodec.decodeRule("ladder::5"))
        assertEquals(ProgressionRule.Linear(2.5, 5.0), ProgramCodec.decodeRule("linear:2.5"))
    }

    @Test
    fun setsRoundTrip() {
        val gear = setOf(Equipment.BARBELL, Equipment.MACHINE)
        assertEquals(gear, ProgramCodec.decodeEquipment(ProgramCodec.encodeEquipment(gear)))
        assertEquals(emptySet(), ProgramCodec.decodeEquipment(""))
        assertEquals(setOf(Equipment.CABLE), ProgramCodec.decodeEquipment("CABLE,BOGUS"))
        val goals = setOf(Goal.BUILD_MUSCLE, Goal.LOSE_FAT)
        assertEquals(goals, ProgramCodec.decodeGoals(ProgramCodec.encodeGoals(goals)))
    }
}
