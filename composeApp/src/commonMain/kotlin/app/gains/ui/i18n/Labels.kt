package app.gains.ui.i18n

import androidx.compose.runtime.Composable
import app.gains.analysis.Format
import app.gains.analysis.InsightKind
import app.gains.analysis.Trend
import app.gains.analysis.UnitLabels
import app.gains.auth.AccountKind
import app.gains.csv.CsvProblem
import app.gains.csv.SkipReason
import app.gains.data.AppLanguage
import app.gains.data.ThemeMode
import app.gains.domain.Equipment
import app.gains.domain.Experience
import app.gains.domain.Goal
import app.gains.domain.Modality
import app.gains.domain.MuscleGroup
import app.gains.domain.WeightUnit
import app.gains.program.Progression
import app.gains.resources.Res
import app.gains.resources.*
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

/*
 * Words for the things the shared module models as enums and numbers: a label per enum value, the
 * unit words, dates, and counts with their plural forms. Every screen reads these from the string
 * resources through here rather than through the enums' own English labels.
 */

// ---- Enums ------------------------------------------------------------------------------------

internal val MuscleGroup.res: StringResource get() = when (this) {
    MuscleGroup.CHEST -> Res.string.muscle_chest
    MuscleGroup.FRONT_DELTS -> Res.string.muscle_front_delts
    MuscleGroup.SIDE_DELTS -> Res.string.muscle_side_delts
    MuscleGroup.REAR_DELTS -> Res.string.muscle_rear_delts
    MuscleGroup.LATS -> Res.string.muscle_lats
    MuscleGroup.UPPER_BACK -> Res.string.muscle_upper_back
    MuscleGroup.LOWER_BACK -> Res.string.muscle_lower_back
    MuscleGroup.TRAPS -> Res.string.muscle_traps
    MuscleGroup.BICEPS -> Res.string.muscle_biceps
    MuscleGroup.TRICEPS -> Res.string.muscle_triceps
    MuscleGroup.FOREARMS -> Res.string.muscle_forearms
    MuscleGroup.QUADS -> Res.string.muscle_quads
    MuscleGroup.HAMSTRINGS -> Res.string.muscle_hamstrings
    MuscleGroup.GLUTES -> Res.string.muscle_glutes
    MuscleGroup.CALVES -> Res.string.muscle_calves
    MuscleGroup.CORE -> Res.string.muscle_core
    MuscleGroup.NECK -> Res.string.muscle_neck
}

@Composable internal fun MuscleGroup.label(): String = stringResource(res)

@Composable internal fun Modality.label(): String = stringResource(when (this) {
    Modality.WEIGHTED -> Res.string.modality_weighted
    Modality.BODYWEIGHT -> Res.string.modality_bodyweight
    Modality.ISOMETRIC -> Res.string.modality_isometric
    Modality.CARDIO -> Res.string.modality_cardio
})

/** "e1RM", "reps", "hold", "distance": what a lift's chart tracks. */
@Composable internal fun Modality.metricLabel(): String = stringResource(when (this) {
    Modality.WEIGHTED -> Res.string.metric_weighted
    Modality.BODYWEIGHT -> Res.string.metric_bodyweight
    Modality.ISOMETRIC -> Res.string.metric_isometric
    Modality.CARDIO -> Res.string.metric_cardio
})

@Composable internal fun Modality.bestMetricHeading(): String = stringResource(when (this) {
    Modality.WEIGHTED -> Res.string.best_metric_weighted
    Modality.BODYWEIGHT -> Res.string.best_metric_bodyweight
    Modality.ISOMETRIC -> Res.string.best_metric_isometric
    Modality.CARDIO -> Res.string.best_metric_cardio
})

@Composable internal fun Equipment.label(): String = stringResource(when (this) {
    Equipment.BARBELL -> Res.string.equipment_barbell
    Equipment.DUMBBELL -> Res.string.equipment_dumbbell
    Equipment.KETTLEBELL -> Res.string.equipment_kettlebell
    Equipment.CABLE -> Res.string.equipment_cable
    Equipment.MACHINE -> Res.string.equipment_machine
    Equipment.BODYWEIGHT -> Res.string.equipment_bodyweight
    Equipment.BANDS -> Res.string.equipment_bands
    Equipment.OTHER -> Res.string.equipment_other
})

@Composable internal fun Goal.label(): String = stringResource(when (this) {
    Goal.BUILD_MUSCLE -> Res.string.goal_build_muscle
    Goal.GET_STRONGER -> Res.string.goal_get_stronger
    Goal.LOSE_FAT -> Res.string.goal_lose_fat
    Goal.GENERAL_FITNESS -> Res.string.goal_general_fitness
})

@Composable internal fun Goal.blurb(): String = stringResource(when (this) {
    Goal.BUILD_MUSCLE -> Res.string.goal_build_muscle_blurb
    Goal.GET_STRONGER -> Res.string.goal_get_stronger_blurb
    Goal.LOSE_FAT -> Res.string.goal_lose_fat_blurb
    Goal.GENERAL_FITNESS -> Res.string.goal_general_fitness_blurb
})

/** Subtitle under "What's moving": which signals the goal puts first, or null when none in particular. */
@Composable internal fun goalHeadline(goal: Goal?): String? = when (goal) {
    Goal.GET_STRONGER -> stringResource(Res.string.goal_headline_get_stronger)
    Goal.BUILD_MUSCLE -> stringResource(Res.string.goal_headline_build_muscle)
    Goal.LOSE_FAT -> stringResource(Res.string.goal_headline_lose_fat)
    Goal.GENERAL_FITNESS, null -> null
}

@Composable internal fun Experience.label(): String = stringResource(when (this) {
    Experience.BEGINNER -> Res.string.experience_beginner
    Experience.INTERMEDIATE -> Res.string.experience_intermediate
    Experience.ADVANCED -> Res.string.experience_advanced
})

@Composable internal fun Experience.blurb(): String = stringResource(when (this) {
    Experience.BEGINNER -> Res.string.experience_beginner_blurb
    Experience.INTERMEDIATE -> Res.string.experience_intermediate_blurb
    Experience.ADVANCED -> Res.string.experience_advanced_blurb
})

@Composable internal fun ThemeMode.label(): String = stringResource(when (this) {
    ThemeMode.DARK -> Res.string.theme_dark
    ThemeMode.LIGHT -> Res.string.theme_light
    ThemeMode.SYSTEM -> Res.string.theme_system
})

/** Each language names itself in its own words, so the list reads the same whichever is in force. */
@Composable internal fun AppLanguage.label(): String = stringResource(when (this) {
    AppLanguage.SYSTEM -> Res.string.language_system
    AppLanguage.ENGLISH -> Res.string.language_english
    AppLanguage.RUSSIAN -> Res.string.language_russian
})

@Composable internal fun AccountKind.label(): String = stringResource(when (this) {
    AccountKind.GUEST -> Res.string.account_guest
    AccountKind.GOOGLE -> Res.string.account_google
    AccountKind.APPLE -> Res.string.account_apple
})

@Composable internal fun InsightKind.label(): String = stringResource(when (this) {
    InsightKind.REGRESSION -> Res.string.insight_regression
    InsightKind.STALL -> Res.string.insight_stall
    InsightKind.NEGLECT -> Res.string.insight_neglect
    InsightKind.CONSISTENCY -> Res.string.insight_consistency
    InsightKind.PROGRESS -> Res.string.insight_progress
})

@Composable internal fun SkipReason.label(): String = stringResource(when (this) {
    SkipReason.EMPTY_ROW -> Res.string.skip_reason_empty_row
    SkipReason.BAD_DATE -> Res.string.skip_reason_bad_date
    SkipReason.BAD_NUMBER -> Res.string.skip_reason_bad_number
    SkipReason.WRONG_COLUMN_COUNT -> Res.string.skip_reason_wrong_column_count
})

@Composable internal fun Progression.Source.label(): String = stringResource(when (this) {
    Progression.Source.FREE_SESSION -> Res.string.source_free_session
    Progression.Source.DIFFERENT_SCHEME -> Res.string.source_different_scheme
})

@Composable internal fun Trend.frequencyTitle(): String = stringResource(when (this) {
    Trend.DOWN -> Res.string.training_less_often
    Trend.UP -> Res.string.training_more_often
    Trend.FLAT -> Res.string.steady_frequency
})

@Composable internal fun csvProblemText(problem: CsvProblem): String = when (problem) {
    CsvProblem.Empty -> stringResource(Res.string.csv_empty)
    CsvProblem.Unrecognised -> stringResource(Res.string.csv_unrecognised)
    is CsvProblem.MissingColumns -> stringResource(Res.string.csv_missing_columns, problem.columns.joinToString())
    is CsvProblem.NotLiftoff -> stringResource(Res.string.csv_not_liftoff, problem.columns.joinToString())
    CsvProblem.NoneReadable -> stringResource(Res.string.csv_none_readable)
}

// ---- Units ------------------------------------------------------------------------------------

internal val WeightUnit.res: StringResource get() = when (this) { WeightUnit.KG -> Res.string.unit_kg; WeightUnit.LBS -> Res.string.unit_lbs }

@Composable internal fun WeightUnit.label(): String = stringResource(res)

/** The unit words for the shared formatting helpers, in the screen's language. */
@Composable
internal fun unitLabels(): UnitLabels = UnitLabels(
    kg = stringResource(Res.string.unit_kg), lbs = stringResource(Res.string.unit_lbs),
    second = stringResource(Res.string.second_abbrev), minute = stringResource(Res.string.minute_abbrev), hour = stringResource(Res.string.hour_abbrev),
    km = stringResource(Res.string.km_abbrev), reps = stringResource(Res.string.reps_suffix),
)

/** [unitLabels] for a screen model, which lives outside the composition. */
internal suspend fun resolvedUnitLabels(texts: Texts): UnitLabels = UnitLabels(
    kg = texts.get(Res.string.unit_kg), lbs = texts.get(Res.string.unit_lbs),
    second = texts.get(Res.string.second_abbrev), minute = texts.get(Res.string.minute_abbrev), hour = texts.get(Res.string.hour_abbrev),
    km = texts.get(Res.string.km_abbrev), reps = texts.get(Res.string.reps_suffix),
)

@Composable internal fun weightText(kg: Double, unit: WeightUnit, decimals: Int = if (unit == WeightUnit.KG) 2 else 1): String = Format.weight(kg, unit, unitLabels(), decimals)
@Composable internal fun minutesText(minutes: Int): String = Format.minutes(minutes, unitLabels())
@Composable internal fun secondsText(seconds: Int): String = Format.seconds(seconds, unitLabels())

/** "3–5 min" when the range sits on whole minutes, else "60–90 s". */
@Composable
internal fun restRangeText(range: IntRange): String =
    if (range.first % 60 == 0 && range.last % 60 == 0 && range.first >= 60) stringResource(Res.string.rest_range_minutes, range.first / 60, range.last / 60)
    else stringResource(Res.string.rest_range_seconds, range.first, range.last)

// ---- Dates ------------------------------------------------------------------------------------

@Composable internal fun dayShort(day: DayOfWeek): String = stringArrayResource(Res.array.days_short)[day.isoDayNumber - 1]
@Composable internal fun monthShort(date: LocalDate): String = stringArrayResource(Res.array.months_short)[date.month.ordinal]
@Composable internal fun monthName(date: LocalDate): String = stringArrayResource(Res.array.months_full)[date.month.ordinal]
/** "12 Feb". */
@Composable internal fun dateShort(date: LocalDate): String = stringResource(Res.string.date_short, date.day, monthShort(date))
@Composable internal fun dateShortWithYear(date: LocalDate): String = stringResource(Res.string.date_with_year, date.day, monthShort(date), date.year)
/** "Feb 2025": a chart tick months away from its neighbours. */
@Composable internal fun monthYear(date: LocalDate): String = stringResource(Res.string.month_year, monthShort(date), date.year)
/** "12 Feb" in the current year, otherwise "12 Feb 2025". */
@Composable internal fun dateContextual(date: LocalDate, today: LocalDate): String = if (date.year == today.year) dateShort(date) else dateShortWithYear(date)
/** "Wed 17 Sep", with the year once it is not this one. */
@Composable internal fun dateWithWeekday(date: LocalDate, today: LocalDate): String = "${dayShort(date.dayOfWeek)} ${dateContextual(date, today)}"

// ---- Counts -----------------------------------------------------------------------------------

@Composable internal fun sessionsText(n: Int): String = pluralStringResource(Res.plurals.sessions, n, n)
@Composable internal fun setsText(n: Int): String = pluralStringResource(Res.plurals.sets, n, n)
@Composable internal fun exercisesText(n: Int): String = pluralStringResource(Res.plurals.exercises, n, n)
@Composable internal fun weeksText(n: Int): String = pluralStringResource(Res.plurals.weeks, n, n)
/** "13 weeks" after "in", "for", "ago": the accusative in languages that have one. */
@Composable internal fun weeksAccusative(n: Int): String = pluralStringResource(Res.plurals.weeks_accusative, n, n)
@Composable internal fun daysAWeekText(n: Int): String = pluralStringResource(Res.plurals.days_a_week, n, n)

// ---- Small sentences with a plural or a label inside ------------------------------------------

/** The heat-map cell for screen readers: "Wed 17 Sep 2025, 2 sessions". */
@Composable
internal fun heatmapDayText(date: LocalDate, sessions: Int): String = stringResource(
    Res.string.heatmap_day, dayShort(date.dayOfWeek), dateShortWithYear(date),
    if (sessions == 0) stringResource(Res.string.no_sessions_count) else sessionsText(sessions),
)

/** "3M", "6M", "1Y", "All": the chip for a lift-detail window of [days]. */
@Composable
internal fun windowLabel(days: Int?): String = stringResource(when (days) { 90 -> Res.string.window_3m; 180 -> Res.string.window_6m; 365 -> Res.string.window_1y; else -> Res.string.window_all })

@Composable
internal fun workingSetRuleBlurb(percent: String, defaultPercent: String?): String =
    stringResource(Res.string.working_set_rule_blurb, percent) + (defaultPercent?.let { stringResource(Res.string.working_set_rule_default, it) } ?: "")

@Composable
internal fun noExercisesFor(group: MuscleGroup, query: String): String =
    if (query.isEmpty()) stringResource(Res.string.no_exercises_for, group.label()) else stringResource(Res.string.no_exercises_for_matching, group.label(), query)

/** The rest line of an exercise card; [warmupRest] is added when the card has warm-ups. */
@Composable
internal fun restLineText(rest: String, warmupRest: String?): String =
    if (warmupRest == null) stringResource(Res.string.rest_line, rest) else stringResource(Res.string.rest_line_with_warm_ups, rest, warmupRest)

@Composable
internal fun importedSummary(sessions: Int, exercisesCreated: Int, outliersDiscarded: Int): String = buildString {
    append(stringResource(Res.string.imported_saved, sessionsText(sessions)))
    if (exercisesCreated > 0) append(stringResource(Res.string.imported_created, pluralStringResource(Res.plurals.new_exercises, exercisesCreated, exercisesCreated)))
    if (outliersDiscarded > 0) append(stringResource(Res.string.imported_discarded, pluralStringResource(Res.plurals.outlier_holds, outliersDiscarded, outliersDiscarded)))
    append(".")
}

@Composable
internal fun loggedTwiceText(date: String, alreadyStored: Boolean): String =
    stringResource(if (alreadyStored) Res.string.logged_twice_stored else Res.string.logged_twice, date)

@Composable
internal fun weightForSetText(set: String, value: String?): String = stringResource(Res.string.weight_for_set, set, value ?: stringResource(Res.string.none_value))

@Composable
internal fun previousSetText(set: String, value: String?): String = stringResource(Res.string.previous_set, set, value ?: stringResource(Res.string.none_value))

@Composable
internal fun setDoneText(set: String, done: Boolean): String = stringResource(if (done) Res.string.set_done else Res.string.set_not_done, set)

@Composable
internal fun onboardingDaysNote(days: Int): String = stringResource(when (days) {
    2 -> Res.string.onboarding_days_note_2
    3 -> Res.string.onboarding_days_note_3
    4 -> Res.string.onboarding_days_note_4
    5 -> Res.string.onboarding_days_note_5
    else -> Res.string.onboarding_days_note_6
})
