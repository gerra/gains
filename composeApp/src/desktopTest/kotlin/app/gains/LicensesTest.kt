package app.gains

import app.gains.ui.licenses.Libraries
import app.gains.ui.licenses.ThirdPartyWorks
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The Open-source licenses screen's two sources: the generated library list, and the hand-written works against NOTICE.md. */
class LicensesTest {
    @Test
    fun theBuildCarriesItsLibraryListWithEveryLicenseTextOnce() {
        val list = runBlocking { Libraries.load() }
        val ids = list.libraries.map { it.id }
        assertTrue("org.jetbrains.kotlin:kotlin-stdlib" in ids, "the desktop list should hold the standard library, got ${ids.take(5)}…")
        assertTrue(list.libraries.none { it.id.startsWith("androidx.activity") }, "the desktop list holds the desktop target's libraries only")
        val licenses = list.licenses.associateBy { it.id }
        for (library in list.libraries) {
            assertTrue(library.licenseIds.isNotEmpty(), "${library.id} has no license")
            library.licenseIds.forEach { assertTrue(it in licenses, "${library.id}: $it has no entry") }
        }
        assertEquals(list.licenses.size, licenses.size)
        assertTrue(assertNotNull(licenses["Apache-2.0"]?.text).trim().startsWith("Apache License"))
        // A license newly allowed in composeApp/build.gradle.kts comes with its text in files/license-texts.
        list.licenses.forEach { assertNotNull(it.text, "${it.id} has no text in ${Libraries.TEXTS}") }
    }

    @Test
    fun everyThirdPartyWorkIsInNoticeWithTheSameNotice() {
        val notice = File("../NOTICE.md").readText().normalized()
        for (work in ThirdPartyWorks.all) {
            assertTrue(work.url in notice, "NOTICE.md doesn't link ${work.name}")
            assertTrue(work.license in notice, "NOTICE.md doesn't name ${work.name}'s license")
            work.notice?.let { assertTrue(it.normalized() in notice, "NOTICE.md doesn't carry ${work.name}'s notice word for word") }
        }
    }

    /** NOTICE.md quotes the notice as a Markdown block; the words and their order are what must match. */
    private fun String.normalized() = lines().joinToString(" ") { it.trim().removePrefix(">").trim() }.replace(Regex("\\s+"), " ")
}
