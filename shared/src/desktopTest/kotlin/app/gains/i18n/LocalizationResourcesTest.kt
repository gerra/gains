package app.gains.i18n

import app.gains.catalogue.ExerciseCatalogue
import app.gains.catalogue.ProgramCatalogue
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The app's string resources (`composeApp/src/commonMain/composeResources`) against the
 * catalogues: every language defines the same keys, and every built-in exercise, program, day
 * and slot note has an entry, with the English one matching what the catalogue says. The
 * resources are the UI's, so the test reads the XML itself through the path Gradle passes in.
 */
class LocalizationResourcesTest {
    private val dir = File(System.getProperty("gains.composeResourcesDir") ?: "../composeApp/src/commonMain/composeResources")
    private val languages = listOf("values", "values-ru")

    /** name -> text for `<string>`, name -> item texts for `<plurals>` and `<string-array>`. */
    private class Table(val strings: Map<String, String>, val plurals: Map<String, Map<String, String>>, val arrays: Map<String, List<String>>) {
        val keys: Set<String> get() = strings.keys + plurals.keys + arrays.keys
    }

    private fun read(folder: String): Table {
        val file = File(dir, "$folder/strings.xml")
        assertTrue(file.isFile, "missing ${file.path}")
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
        fun Element.children(tag: String) = (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }
        return Table(
            strings = root.children("string").associate { it.getAttribute("name") to it.textContent },
            plurals = root.children("plurals").associate { p -> p.getAttribute("name") to p.children("item").associate { it.getAttribute("quantity") to it.textContent } },
            arrays = root.children("string-array").associate { a -> a.getAttribute("name") to a.children("item").map { it.textContent } },
        )
    }

    private val tables = languages.associateWith(::read)
    private val english = tables.getValue("values")

    @Test
    fun everyLanguageDefinesTheSameKeys() {
        for ((language, table) in tables) {
            assertEquals(emptySet(), english.keys - table.keys, "$language is missing keys")
            assertEquals(emptySet(), table.keys - english.keys, "$language has keys English does not")
            for ((name, items) in table.arrays) assertEquals(english.arrays.getValue(name).size, items.size, "$language: array $name")
            for ((name, items) in table.plurals) assertTrue("other" in items, "$language: plural $name has no 'other'")
        }
    }

    @Test
    fun russianPluralsCoverEveryQuantity() {
        for ((name, items) in tables.getValue("values-ru").plurals) {
            assertEquals(setOf("one", "few", "many", "other"), items.keys, "plural $name")
        }
    }

    @Test
    fun everyBuiltInExerciseIsNamedInEveryLanguage() {
        for (exercise in ExerciseCatalogue.builtIn) {
            val key = "exercise_${exercise.id}"
            assertEquals(exercise.name, english.strings[key], "English name of $key")
            for ((language, table) in tables) assertTrue(table.strings[key].orEmpty().isNotBlank(), "$language: $key")
        }
    }

    @Test
    fun everyBuiltInProgramItsDaysAndNotesAreNamedInEveryLanguage() {
        val englishNotes = english.strings.filterKeys { it.startsWith("slot_note_") }.values.toSet()
        for (program in ProgramCatalogue.builtIn) {
            assertEquals(program.name, english.strings["program_${program.id}"], "English name of ${program.id}")
            assertEquals(program.description, english.strings["program_${program.id}_description"], "English description of ${program.id}")
            for ((language, table) in tables) {
                assertTrue(table.strings["program_${program.id}"].orEmpty().isNotBlank(), "$language: program ${program.id}")
                assertTrue(table.strings["program_${program.id}_description"].orEmpty().isNotBlank(), "$language: description of ${program.id}")
            }
            for (day in program.days) {
                // "A1"-style names are the same in every language and have no entry.
                if (day.name.matches(Regex("[A-Z]\\d"))) continue
                val key = "day_" + day.name.lowercase().replace(' ', '_')
                assertEquals(day.name, english.strings[key], "English name of $key")
                for ((language, table) in tables) assertTrue(table.strings[key].orEmpty().isNotBlank(), "$language: $key")
                for (slot in day.slots) {
                    val note = slot.note ?: continue
                    assertTrue(note in englishNotes, "no slot_note entry for \"$note\"")
                }
            }
        }
    }
}
