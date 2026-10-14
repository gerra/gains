package app.gains

import app.gains.ui.components.ButtonHeight
import app.gains.ui.components.CompactButtonHeight
import app.gains.ui.components.Dp16
import app.gains.ui.theme.TypeScale
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * docs/design.md is what agents and people read instead of the theme code, so it must say what the
 * code does: every colour of the palettes and colour schemes, the type scale, the button heights and
 * the card paddings. A change to the look that forgets the doc fails here.
 */
class DesignDocTest {
    private val doc = File("../docs/design.md").readText()
    private val theme = File("src/commonMain/kotlin/app/gains/ui/theme/Theme.kt").readText()

    @Test
    fun everyThemeColourIsInTheDoc() {
        // From the named base colours to the end of the light scheme: the palettes and both schemes,
        // not the chart series list above them, which is a range of hues rather than roles.
        val from = theme.indexOf("private val Graphite")
        val to = theme.indexOf("private fun gainsTypography")
        check(from in 0 until to) { "Theme.kt no longer has the markers this test reads between" }
        val opaque = Regex("""Color\(0xFF([0-9A-Fa-f]{6})\)""").findAll(theme.substring(from, to)).map { it.groupValues[1].uppercase() }.toSet()
        check(opaque.size > 20) { "found only ${opaque.size} colours in Theme.kt" }
        val missing = opaque.filter { "#$it" !in doc.uppercase() }
        assertTrue(missing.isEmpty(), "docs/design.md doesn't list these Theme.kt colours: ${missing.joinToString { "#$it" }}")
    }

    @Test
    fun theTypeScaleIsTheDocs() {
        for (size in listOf(TypeScale.CAPTION, TypeScale.BODY, TypeScale.TITLE, TypeScale.FIGURE, TypeScale.HERO)) {
            assertTrue("| $size sp |" in doc, "docs/design.md's type table has no $size sp row")
        }
    }

    @Test
    fun buttonsAndCardsMeasureWhatTheDocSays() {
        assertTrue("`PrimaryButton` (${ButtonHeight.value.toInt()} dp" in doc, "docs/design.md gives another height for PrimaryButton than $ButtonHeight")
        assertTrue("`compact = true` (${CompactButtonHeight.value.toInt()} dp)" in doc, "docs/design.md gives another compact button height than $CompactButtonHeight")
        assertTrue("pad ${Dp16.Normal.dp.value.toInt()} (`Dp16.Normal`)" in doc, "docs/design.md gives another card padding than ${Dp16.Normal.dp}")
        assertTrue("${Dp16.Tight.dp.value.toInt()} when dense (`Dp16.Tight`)" in doc, "docs/design.md gives another dense card padding than ${Dp16.Tight.dp}")
        assertTrue("${Dp16.Loose.dp.value.toInt()} for a hero (`Dp16.Loose`)" in doc, "docs/design.md gives another hero card padding than ${Dp16.Loose.dp}")
    }
}
