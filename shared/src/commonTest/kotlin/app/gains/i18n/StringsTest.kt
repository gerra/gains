package app.gains.i18n

import app.gains.analysis.Format
import app.gains.catalogue.ExerciseCatalogue
import app.gains.catalogue.ProgramCatalogue
import app.gains.domain.WeightUnit
import app.gains.program.Gzclp
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StringsTest {
    @Test
    fun languageResolvesFromAnyTagShape() {
        assertSame(Russian, Strings.forLanguage("ru"))
        assertSame(Russian, Strings.forLanguage("ru-RU"))
        assertSame(Russian, Strings.forLanguage("ru_RU"))
        assertSame(English, Strings.forLanguage("en-GB"))
        assertSame(English, Strings.forLanguage("de"))
        assertSame(English, Strings.forLanguage(null))
    }

    @Test
    fun russianPluralsFollowTheThreeForms() {
        assertEquals("1 подход", Russian.sets(1))
        assertEquals("2 подхода", Russian.sets(2))
        assertEquals("5 подходов", Russian.sets(5))
        assertEquals("11 подходов", Russian.sets(11))
        assertEquals("21 подход", Russian.sets(21))
        assertEquals("22 подхода", Russian.sets(22))
        assertEquals("0 тренировок", Russian.sessions(0))
        assertEquals("3 дня в неделю", Russian.daysAWeek(3))
        assertEquals("1 неделя подряд", Russian.weekStreak(1))
    }

    @Test
    fun englishPluralsMatchTheOldWording() {
        assertEquals("1 session", English.sessions(1))
        assertEquals("2 sessions", English.sessions(2))
        assertEquals("3 wks streak", English.weekStreak(3))
    }

    @Test
    fun unitsAndDatesAreWordedPerLanguage() {
        assertEquals("60 kg", English.weight(60.0, WeightUnit.KG))
        assertEquals("60 кг", Russian.weight(60.0, WeightUnit.KG))
        assertEquals("3 h 42 min", English.minutes(222))
        assertEquals("3 ч 42 мин", Russian.minutes(222))
        assertEquals("30 s", English.seconds(30))
        assertEquals("30 с", Russian.seconds(30))
        assertEquals("1:30", Russian.seconds(90))
        val date = LocalDate(2025, 2, 12)
        assertEquals("12 Feb", English.dateContextual(date, LocalDate(2025, 9, 1)))
        assertEquals("12 фев 2025", Russian.dateContextual(date, LocalDate(2026, 9, 1)))
        assertEquals("Февраль", Russian.monthName(date))
        assertEquals("Ср", Russian.dayShort(DayOfWeek.WEDNESDAY))
        assertEquals("Wed", English.dayShort(DayOfWeek.WEDNESDAY))
    }

    @Test
    fun formatAndDatesDefaultToEnglishButTakeALanguage() {
        assertEquals("60 kg", Format.weight(60.0, WeightUnit.KG))
        assertEquals("60 кг", Format.weight(60.0, WeightUnit.KG, strings = Russian))
        assertEquals("3–5 min", Gzclp.Tier.T1.restLabel())
        assertEquals("3–5 мин", Gzclp.Tier.T1.restLabel(Russian))
        assertEquals("30–60 с или по самочувствию", Gzclp.warmupRestLabel(Russian))
    }

    @Test
    fun everyBuiltInExerciseHasARussianName() {
        val missing = ExerciseCatalogue.builtIn.filter { RussianNames.exercises[it.id] == null }.map { it.id }
        assertTrue(missing.isEmpty(), "no Russian name for: $missing")
        val unknown = RussianNames.exercises.keys - ExerciseCatalogue.builtIn.map { it.id }.toSet()
        assertTrue(unknown.isEmpty(), "Russian names for exercises that do not exist: $unknown")
        for (exercise in ExerciseCatalogue.builtIn) assertNotEquals("", Russian.exerciseName(exercise).trim())
    }

    @Test
    fun everyBuiltInProgramIsTranslated() {
        for (program in ProgramCatalogue.builtIn) {
            assertTrue(RussianNames.programs.containsKey(program.id), "no Russian name for program ${program.id}")
            assertTrue(RussianNames.programDescriptions.containsKey(program.id), "no Russian description for program ${program.id}")
            for (day in program.days) {
                // Letter-and-number day names (A1, B2) are the same in both languages.
                val translated = Russian.programDayName(day)
                assertTrue(translated != day.name || day.name.all { it.isLetterOrDigit() && it.code < 128 } && day.name.length <= 2, "day ${day.name} of ${program.id} has no Russian name")
                for (slot in day.slots) slot.note?.let { note ->
                    assertTrue(RussianNames.slotNotes.containsKey(note), "no Russian slot note for \"$note\"")
                }
            }
        }
    }

    @Test
    fun customExercisesAndProgramsKeepTheirOwnNames() {
        val custom = ExerciseCatalogue.builtIn.first().copy(id = "custom_x", name = "Моё упражнение", isBuiltIn = false)
        assertEquals("Моё упражнение", Russian.exerciseName(custom))
        assertEquals("Моё упражнение", English.exerciseName(custom))
        assertEquals("Own note", Russian.slotNote("Own note"))
    }

    @Test
    fun everyLanguageDeclaresADistinctTag() {
        assertEquals(Strings.all.size, Strings.all.map { it.languageTag }.toSet().size)
    }
}
