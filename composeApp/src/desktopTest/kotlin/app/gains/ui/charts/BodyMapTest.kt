package app.gains.ui.charts

import app.gains.domain.MuscleGroup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BodyMapTest {
    @Test
    fun everyPathParsesToSomethingWithArea() {
        for (region in BodyMapModel.regions) {
            for (path in region.paths) {
                val b = path.getBounds()
                assertTrue(b.width > 1f && b.height > 1f, "${region.side} ${region.slug} has a degenerate path: $b")
                assertTrue(b.left >= 0f && b.right <= BodyMapPaths.WIDTH, "${region.side} ${region.slug} leaves the figure horizontally: $b")
                assertTrue(b.top >= 0f && b.bottom <= BodyMapPaths.HEIGHT, "${region.side} ${region.slug} leaves the figure vertically: $b")
            }
        }
        for (outline in listOf(BodyMapModel.frontOutline, BodyMapModel.backOutline)) {
            val b = outline.getBounds()
            assertTrue(b.left >= 0f && b.right <= BodyMapPaths.WIDTH && b.height > 1000f, "outline out of place: $b")
        }
    }

    @Test
    fun everyMuscleGroupIsSomewhereOnTheBody() {
        for (group in MuscleGroup.entries) {
            assertTrue(BodyMapModel.regionsOf(group).isNotEmpty(), "$group has no region on the map")
        }
    }

    @Test
    fun theBackShowsWhatTheFrontCannot() {
        val front = BodyMapModel.regions.filter { it.side == BodySide.FRONT }.flatMap { it.groups }.toSet()
        val back = BodyMapModel.regions.filter { it.side == BodySide.BACK }.flatMap { it.groups }.toSet()
        assertTrue(MuscleGroup.CHEST in front && MuscleGroup.CHEST !in back)
        assertTrue(MuscleGroup.LATS in back && MuscleGroup.LATS !in front)
        assertTrue(MuscleGroup.SIDE_DELTS in front && MuscleGroup.SIDE_DELTS in back)
    }

    @Test
    fun hitTestingFindsTheRegionUnderAPoint() {
        // Centres taken from the drawing: the left pec on the front view, a lat and a glute on the back view.
        assertEquals(listOf(MuscleGroup.CHEST), BodyMapModel.regionAt(BodySide.FRONT, 310f, 375f)?.groups)
        assertEquals(listOf(MuscleGroup.LATS), BodyMapModel.regionAt(BodySide.BACK, 1030f - BodyMapPaths.BACK_OFFSET_X, 500f)?.groups)
        assertEquals(listOf(MuscleGroup.GLUTES), BodyMapModel.regionAt(BodySide.BACK, 1030f - BodyMapPaths.BACK_OFFSET_X, 700f)?.groups)
        // Outside the figure and between the legs there is nothing to tap.
        assertNull(BodyMapModel.regionAt(BodySide.FRONT, 5f, 5f))
        assertNull(BodyMapModel.regionAt(BodySide.FRONT, BodyMapPaths.WIDTH / 2, 1100f))
    }
}
