package app.gains.ui.charts

import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.PathParser
import app.gains.domain.MuscleGroup
import kotlin.math.abs
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

    /**
     * Compose's [PathParser] does not read SVG's compact arc-flag syntax (`a1 1 0 01.5.5`): it drops the arc
     * without complaint and the rest of the shape is drawn from the wrong point. Each arc command in the
     * generated data carries exactly one arc, so the parsed arcs must match the commands one to one.
     */
    @Test
    fun composeKeepsEveryArcOfTheDrawing() {
        val paths = (BodyMapPaths.front + BodyMapPaths.back).map { it.d } + BodyMapPaths.frontOutline + BodyMapPaths.backOutline
        for (d in paths) {
            val arcs = PathParser().parsePathString(d).toNodes().count { it is PathNode.ArcTo || it is PathNode.RelativeArcTo }
            assertEquals(d.count { it == 'a' || it == 'A' }, arcs, "an arc was lost in: ${d.take(80)}…")
        }
    }

    /** A lost arc shifts part of a shape sideways; the head is the easiest place to see it, so it must stay centred. */
    @Test
    fun theHeadSitsOnTheMidlineOfBothFigures() {
        for (region in BodyMapModel.regions) {
            if (region.slug != "head" && region.slug != "hair") continue
            val centre = (region.bounds.left + region.bounds.right) / 2
            assertTrue(abs(centre - BodyMapPaths.WIDTH / 2) < 8f, "${region.side} ${region.slug} is off centre: ${region.bounds}")
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
