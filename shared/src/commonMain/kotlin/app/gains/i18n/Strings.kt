package app.gains.i18n

import app.gains.analysis.Format
import app.gains.analysis.InsightKind
import app.gains.auth.AccountKind
import app.gains.csv.CsvProblem
import app.gains.csv.SkipReason
import app.gains.data.ThemeMode
import app.gains.domain.Equipment
import app.gains.domain.Exercise
import app.gains.domain.Experience
import app.gains.domain.Goal
import app.gains.domain.Modality
import app.gains.domain.MuscleGroup
import app.gains.domain.Program
import app.gains.domain.ProgramDay
import app.gains.domain.Units
import app.gains.domain.WeightUnit
import app.gains.program.Progression
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate

/**
 * Every sentence, label and unit the app shows, in one language. [English] is the reference and
 * the fallback; [Russian] is the first translation. The UI reads the current one from its
 * `LocalStrings`, the shared code that produces text (insights, progression hints, the day
 * planner) takes one as a parameter, and platform code outside Compose asks [Strings.system].
 *
 * Each entry is a `val` for fixed text or a `fun` for text with something in it, so that a
 * language with different word order or plural forms can put the parts where they belong. A new
 * string is added here first; the compiler then insists every language supplies it. The built-in
 * exercise, program and day names live in the database in English and are translated on the way
 * to the screen through [exerciseName], [programName] and [programDayName]; custom ones are shown
 * as typed.
 */
interface Strings {
    /** BCP 47 language subtag: "en", "ru". */
    val languageTag: String

    // ---- Units, numbers and plurals ---------------------------------------------------------

    fun unit(unit: WeightUnit): String
    /** "min", "h", "s" and "km" as they follow a number. */
    val minuteAbbrev: String
    val hourAbbrev: String
    val secondAbbrev: String
    val kmAbbrev: String

    /** "60 kg" / "132.3 lbs". */
    fun weight(kg: Double, unit: WeightUnit, decimals: Int = if (unit == WeightUnit.KG) 2 else 1): String =
        Format.number(Units.display(kg, unit), decimals) + " " + unit(unit)

    /** "60 kg × 8". */
    fun set(weightKg: Double, reps: Int, unit: WeightUnit): String = "${weight(weightKg, unit)} × $reps"

    /** "1:30" past a minute, else "30 s". */
    fun seconds(seconds: Int): String =
        if (seconds >= 60) "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}" else "$seconds $secondAbbrev"

    /** "45 min", "1 h", "3 h 42 min". */
    fun minutes(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> "$m $minuteAbbrev"
            m == 0 -> "$h $hourAbbrev"
            else -> "$h $hourAbbrev $m $minuteAbbrev"
        }
    }

    fun km(km: Double): String = Format.number(km, 2) + " " + kmAbbrev

    /** "12 reps". */
    fun reps(n: Int): String
    fun sessions(n: Int): String
    fun sets(n: Int): String
    fun exercises(n: Int): String
    fun weeks(n: Int): String
    fun files(n: Int): String
    fun rows(n: Int): String
    fun newExercises(n: Int): String
    fun outlierHolds(n: Int): String
    /** "3 days a week". */
    fun daysAWeek(n: Int): String
    /** "3d/wk", the program tag. */
    fun daysPerWeekTag(n: Int): String
    /** "3 wk streak". */
    fun weekStreak(n: Int): String
    /** The word alone, for the tile under a big number: "week" / "weeks". */
    fun weekWord(n: Int): String
    fun sessionWord(n: Int): String

    // ---- Dates -------------------------------------------------------------------------------

    /** "Jan", "Feb"… by month ordinal, as they follow a day number. */
    val monthsShort: List<String>
    /** "January"… as a heading. */
    val monthsFull: List<String>
    /** "Mon", "Tue"… */
    fun dayShort(day: DayOfWeek): String
    val today: String
    val yesterday: String

    /** "12 Feb". */
    fun dateShort(date: LocalDate): String = "${date.day} ${monthsShort[date.month.ordinal]}"
    fun dateShortWithYear(date: LocalDate): String = "${date.day} ${monthsShort[date.month.ordinal]} ${date.year}"
    fun monthName(date: LocalDate): String = monthsFull[date.month.ordinal]
    fun monthShort(date: LocalDate): String = monthsShort[date.month.ordinal]
    /** "12 Feb" in the current year, otherwise "12 Feb 2025". */
    fun dateContextual(date: LocalDate, today: LocalDate): String =
        if (date.year == today.year) dateShort(date) else dateShortWithYear(date)
    /** "Wed 17 Sep", with the year once it is not this one. */
    fun dateWithWeekday(date: LocalDate, today: LocalDate): String = "${dayShort(date.dayOfWeek)} ${dateContextual(date, today)}"

    // ---- Names of things ---------------------------------------------------------------------

    fun muscleGroup(group: MuscleGroup): String
    fun modality(modality: Modality): String
    fun equipment(equipment: Equipment): String
    fun goal(goal: Goal): String
    fun goalBlurb(goal: Goal): String
    fun experience(experience: Experience): String
    fun experienceBlurb(experience: Experience): String
    fun themeMode(mode: ThemeMode): String
    fun accountKind(kind: AccountKind): String
    fun insightKind(kind: InsightKind): String
    fun skipReason(reason: SkipReason): String
    fun progressionSource(source: Progression.Source): String
    fun csvProblem(problem: CsvProblem): String

    /** A built-in exercise in this language; a custom one as it was typed. */
    fun exerciseName(exercise: Exercise): String
    fun programName(program: Program): String
    fun programDescription(program: Program): String
    fun programDayName(day: ProgramDay): String
    /** A slot note of a built-in program (or a copy of one) in this language; any other note as typed. */
    fun slotNote(note: String): String
    /** The name a duplicated program gets: "GZCLP (copy)". */
    fun copyOf(name: String): String

    // ---- Shared words ------------------------------------------------------------------------

    val done: String
    val cancel: String
    val delete: String
    val back: String
    val close: String
    val change: String
    val remove: String
    val reset: String
    val edit: String
    val show: String
    val hide: String
    val on: String
    val off: String
    val all: String
    val none: String
    val start: String
    val notSet: String
    val perDumbbell: String
    /** " · per dumbbell", appended to a line. */
    val perDumbbellSuffix: String
    val warmUp: String

    // ---- Navigation, top bar, resume bar ------------------------------------------------------

    val tabHome: String
    val tabHistory: String
    val tabLifts: String
    val tabVolume: String
    val tabBody: String
    val addDescription: String
    val settingsDescription: String
    fun menuStartDay(dayName: String): String
    val menuStartWorkout: String
    val menuLogPastWorkout: String
    val menuImportCsv: String
    val menuPrograms: String
    /** "Rest 1:30" on the resume bar. */
    fun restCountdown(clock: String): String
    val resumeChevron: String

    // ---- Sign-in -------------------------------------------------------------------------------

    val signInHeadline: String
    val signInBlurb: String
    val continueAsGuest: String
    val guestNoteWithSync: String
    val guestNoteComingSoon: String
    fun signInNotConfigured(provider: AccountKind): String
    val heroExercise: String
    val heroDetail: String
    val featureLog: String
    val featureLogBody: String
    val featureImport: String
    val featureImportBody: String
    val featureAnalyse: String
    val featureAnalyseBody: String

    // ---- Onboarding ------------------------------------------------------------------------------

    val skipForNow: String
    val onboardingGoalTitle: String
    val onboardingGoalBlurb: String
    val onboardingExperienceTitle: String
    val onboardingExperienceBlurb: String
    val onboardingDaysTitle: String
    val onboardingDaysBlurb: String
    fun onboardingDaysNote(days: Int): String
    val programsThatFit: String
    /** "Get stronger · Beginner · 3 days a week". */
    fun profileSummary(goal: Goal, experience: Experience, days: Int): String
    val bestMatch: String
    val useThisProgram: String
    val justSaveMyGoal: String
    val next: String
    val active: String

    // ---- Home -------------------------------------------------------------------------------------

    val noWorkoutsYet: String
    val noWorkoutsYetBody: String
    val logAWorkout: String
    val importHistory: String
    val homeTitle: String
    fun lastSession(date: String): String
    val plusLog: String
    val whatsMoving: String
    fun goalHeadline(goal: Goal?): String?
    val oneSessionImported: String
    val nothingToFlag: String
    fun upNext(programName: String): String
    /** "4 exercises · 1 of 3 this week". */
    fun exercisesAndWeekCount(exercises: Int, done: Int, perWeek: Int): String
    val wholeProgram: String
    val pickAProgram: String
    val chooseRoutineBlurb: String
    val chooseAProgram: String
    val setYourGoal: String
    val whatAreYouTrainingFor: String
    val threeQuickQuestions: String
    val getStarted: String
    val thisWeek: String
    val heroSessions: String
    val heroLifts: String
    val heroUp: String
    val heroDown: String
    fun sessionLink(date: String): String

    // ---- Insights (text produced in shared) ------------------------------------------------------

    fun regressionDetail(current: String, best: String, bestDate: String, drop: String): String
    fun stallDetail(value: String, since: String, sessions: Int, weeks: Int): String
    fun neglectedExerciseDetail(lastDate: String, weeksAgo: Int, priorSessions: Int, lookbackWeeks: Int): String
    fun neglectedMuscleDetail(recentAvg: String, recentWeeks: Int, baselineAvg: String, baselineWeeks: Int): String
    fun consistencyRecent(rate: String, weeks: Int): String
    fun consistencyUp(recent: String, previous: String, weeks: Int): String
    fun consistencyDown(recent: String, previous: String, weeks: Int): String
    fun consistencySteady(recent: String, previous: String, weeks: Int): String
    val trainingLessOften: String
    val trainingMoreOften: String
    val steadyFrequency: String
    fun progressDetail(current: String, currentDate: String, gain: String, previous: String, previousDate: String): String

    // ---- History -----------------------------------------------------------------------------------

    val noSessionsYet: String
    val noSessionsYetBody: String
    val historyTitle: String
    fun onRecord(sessions: Int): String
    val perWeek: String
    fun lastNWeeks(n: Int): String
    val trend: String
    val trendUp: String
    val trendDown: String
    val trendSteady: String
    fun wasPerWeek(rate: String): String
    val streak: String
    val last26Weeks: String
    val tapADayToOpen: String
    val sessionsPerWeek: String
    val fourWeekAverage: String
    val sessionsLegend: String
    /** The heat-map cell for screen readers: "Wed 17 Sep 2025, 2 sessions". */
    fun heatmapDay(date: LocalDate, sessions: Int): String
    val logged: String
    /** " · 45 min" after the time of a session. */
    fun durationSuffix(minutes: Int): String

    // ---- Lifts -------------------------------------------------------------------------------------

    val noLiftsYet: String
    val noLiftsYetBody: String
    val liftsTitle: String
    fun liftsSubtitle(count: Int): String
    val searchLifts: String

    // ---- Lift detail ---------------------------------------------------------------------------------

    val unknownExercise: String
    val unknownExerciseBody: String
    val noSessions: String
    val noSessionsForExercise: String
    val currentBest: String
    val allTimeBest: String
    val atYourBest: String
    val fromAllTimeBest: String
    val window: String
    fun windowLabel(days: Int?): String
    val nothingInWindow: String
    fun pickALongerWindow(sessions: Int): String
    val oneSessionInWindow: String
    val e1rmSection: String
    val noWeightedSetsInWindow: String
    val e1rm: String
    val topSetWeight: String
    val topSet: String
    val volumePerSession: String
    val totalVolume: String
    fun bestMetricPerSession(modality: Modality): String
    fun metricLabel(modality: Modality): String
    val workingSetRule: String
    val sessionsTapToOpen: String
    fun e1rmValue(weight: String): String
    fun bestSetVolume(weight: String): String
    fun volumeValue(weight: String): String
    fun workingSetsOf(working: Int, total: Int): String
    fun workingSetRuleBlurb(percent: String, defaultPercent: String?): String
    val resetToDefault: String

    // ---- Exercise picker -------------------------------------------------------------------------------

    val addExercises: String
    fun inYourLibrary(count: Int): String
    fun nSelected(count: Int): String
    val searchOrTypeNew: String
    val clear: String
    val recent: String
    fun noExercisesFor(group: MuscleGroup, query: String): String
    fun nothingMatches(query: String): String
    val results: String
    val selectExercises: String
    fun addNExercises(count: Int): String
    fun createNamed(name: String): String
    val customExerciseGuessed: String
    val added: String
    fun replaceNamed(name: String): String

    // ---- Volume ---------------------------------------------------------------------------------------

    val noVolumeYet: String
    val noVolumeYetBody: String
    val volumeTitle: String
    val volumeSubtitle: String
    val setsSoFar: String
    val lastWeek: String
    fun weekCommencing(date: String): String
    val avg: String
    fun nWeekCaption(weeks: Int): String
    val onTheBody: String
    fun nWeekAvg(weeks: Int): String
    fun nWeeksChip(weeks: Int): String
    val bodyMapThisWeek: String
    val bodyMapLastWeek: String
    val bodyMapAvg: String
    /** "3.5 sets this week" / "… last week" / "… a week on average". */
    fun setsThisWeek(sets: String): String
    fun setsLastWeek(sets: String): String
    fun setsAWeekOnAverage(sets: String): String
    val showAll: String
    fun bodyMapBlurb(windowSets: String): String
    /** The window phrase alone, for [bodyMapBlurb]: "this week", "last week", "a week on average". */
    val windowThisWeek: String
    val windowLastWeek: String
    val windowAverage: String
    val bodyMapBlurbSelected: String
    val volumeCreditBlurb: String
    fun thisWeekFrom(date: String): String
    fun volumeBarsBlurb(junk: Int, maintenance: Int): String
    val statusNone: String
    fun statusUnder(sets: Int): String
    val statusOnTarget: String
    fun statusOver(sets: Int): String
    fun legendMaxSets(sets: Int): String

    // ---- Bodyweight -------------------------------------------------------------------------------------

    val bodyweightTitle: String
    val bodyweightSubtitle: String
    val plusAdd: String
    val noBodyweightEntries: String
    val noBodyweightEntriesBody: String
    val latest: String
    val sevenDayAvg: String
    val changeLabel: String
    fun sinceDate(date: String): String
    val oneEntrySoFar: String
    val daily: String
    fun e1rmOf(exercise: String): String
    val overlayALift: String
    val entries: String
    val date: String
    val time: String
    val duration: String
    val weightLabel: String
    val saveEntry: String
    val chooseAWeight: String
    val importToOverlay: String
    val chooseALift: String
    val rightAxisBlurb: String

    // ---- Settings ---------------------------------------------------------------------------------------

    val settingsTitle: String
    val account: String
    val notSignedIn: String
    val guestDataNote: String
    val syncedToServer: String
    val signIn: String
    val signOut: String
    val signInNotConfiguredNote: String
    val trainingGoal: String
    val daysAWeekLabel: String
    val activeProgram: String
    val redoSetup: String
    val noGoalSetNote: String
    fun goalSortNote(goal: Goal): String
    val appearance: String
    val displayUnits: String
    val unitsNote: String
    val warmUps: String
    val prefillWarmUps: String
    val emptyBar: String
    val warmUpsNote: String
    val customExercises: String
    val allExercisesMatched: String
    val customExercisesNote: String
    val noMuscleGroupsGuessed: String
    val mergeInto: String
    val aliases: String
    val workingSetOverrides: String
    val data: String
    val dataNote: String
    val deleteAllSessions: String
    val deleteAllSessionsTitle: String
    val deleteAllSessionsBody: String

    // ---- Programs -----------------------------------------------------------------------------------------

    val programsTitle: String
    val setAGoalToSort: String
    val plusNew: String
    val yourPrograms: String
    val builtInBestFit: String
    val builtIn: String
    val builtInNote: String
    val deactivate: String
    val activate: String
    val duplicateToEdit: String
    val schedule: String
    fun weeksAndDays(weeks: Int, daysPerWeek: Int): String
    val days: String
    val tapAnyDayToStart: String
    val howItProgresses: String
    val deleteProgram: String
    fun deleteNamed(name: String): String
    val deleteProgramBody: String
    val upNextPill: String
    fun lastDone(date: String): String
    val notDoneYet: String
    fun weekN(n: Int): String
    val rotationNote: String
    val noAutomaticRule: String
    fun restBetweenSets(rest: String): String
    /** "3–5 min" / "60–90 s". */
    fun restRange(fromSeconds: Int, toSeconds: Int): String
    val warmUpRestOrAsNeeded: String
    /** The rest line of an exercise card; [warmupRest] is added when the card has warm-ups. */
    fun restLine(rest: String, warmupRest: String?): String

    // ---- Program editor -----------------------------------------------------------------------------------

    val newProgram: String
    val editProgram: String
    val name: String
    val descriptionOptional: String
    val plusAddDay: String
    val daysRotateNote: String
    val dayName: String
    fun dayN(n: Int): String
    val plusAddExercise: String
    val saveProgram: String
    val giveTheProgramAName: String
    val addAtLeastOneDay: String
    fun dayHasNoExercises(day: String): String
    fun slotInvalid(exercise: String): String
    val setsLabel: String
    val repsLabel: String
    val note: String
    val programLadderKept: String
    val progressionNone: String
    val progressionSmall: String
    val progressionBig: String
    val progressionDouble: String
    fun changeNamed(name: String): String

    // ---- Progression (text produced in shared) ------------------------------------------------------------

    /** "Last: 60 kg × 5,5,5"; [weight] already carries its " × " or is empty. */
    fun lastLabel(weight: String, reps: String): String
    fun hintTry(last: String, weight: String): String
    fun hintRepeat(last: String, weight: String): String
    fun hintMoveOn(last: String, maxReps: Int): String
    fun hintTryReps(last: String, weight: String, reps: Int): String
    fun hintTarget(last: String, weightPrefix: String, reps: Int): String
    fun hintTryStage(last: String, weight: String, stage: String): String
    fun hintMissed(last: String, stage: String, weight: String): String
    fun hintReset(last: String, weight: String, stage: String): String
    fun hintEstimate(last: String, e1rm: String, tier: String, start: String, cap: String): String
    fun capKeptUnder(weight: String): String
    val withNoAddedLoad: String
    val asLightAsYouCanLoad: String
    fun hintStart(last: String, source: String, target: String, at: String): String
    fun atWeight(weight: String): String
    fun describeLinear(step: String): String
    fun describeDouble(min: Int, max: Int, then: String): String
    fun describeDoubleThenAdd(step: String, min: Int): String
    val describeDoubleThenHarder: String
    fun describeLadder(stages: String, step: String, first: String): String
    /** Formats a progression step: "2.5 kg". */
    fun stepLabel(step: Double, unit: WeightUnit): String = "${Format.number(step, 2)} ${unit(unit)}"

    // ---- Import -------------------------------------------------------------------------------------------

    val importYourHistory: String
    val importBlurb: String
    val chooseCsvFiles: String
    val readingTheFile: String
    fun readingFiles(count: Int): String
    val saving: String
    val couldNotImport: String
    val couldNotReadTheFile: String
    fun importFailed(reason: String): String
    fun savingFailed(reason: String): String
    val chooseAnotherFile: String
    val imported: String
    fun importedSummary(sessions: Int, exercisesCreated: Int, outliersDiscarded: Int): String
    val importTitle: String
    val csv: String
    fun appearedInSeveralFiles(sessions: Int): String
    val weightsInFileAreIn: String
    val unitNote: String
    val summary: String
    val sessionsFound: String
    val dateRange: String
    val newSessions: String
    val changedSinceLastImport: String
    val alreadyImported: String
    val inMoreThanOneFile: String
    val duplicateSessions: String
    val durationsDiscarded: String
    val newExercisesLabel: String
    val emptyRowsSkipped: String
    fun rowsSkipped(reason: SkipReason): String
    val exercisesNotInCatalogue: String
    val exercisesNotInCatalogueNote: String
    val duplicatesDetected: String
    fun loggedTwice(date: String, alreadyStored: Boolean): String
    val suspiciousHolds: String
    val keepAll: String
    val discardAll: String
    val suspiciousHoldsNote: String
    fun holdOutlier(sets: Int, seconds: String, usual: String): String
    val nothingNew: String
    fun importN(count: Int): String

    // ---- Workout editor -------------------------------------------------------------------------------------

    val logWorkout: String
    val editWorkout: String
    val workout: String
    val notPartOfAProgram: String
    fun startedAt(clock: String): String
    val lookOverThePlan: String
    val exercisesSection: String
    fun addAnExerciseNote(unit: String): String
    val discard: String
    val endSession: String
    val saveWorkout: String
    val saveChanges: String
    val addAtLeastOneExercise: String
    fun warmUpN(n: String): String
    fun setN(n: String): String
    val deleteThisWorkout: String
    val deleteThisWorkoutBody: String
    val discardThisWorkout: String
    val discardThisWorkoutBody: String
    val keepGoing: String
    fun setsNotTicked(count: Int): String
    fun leaveOutOrSave(count: Int): String
    val leaveOut: String
    val saveAll: String
    val workoutInProgress: String
    fun conflictBody(running: String, ago: String, wanted: String): String
    fun resumeNamed(name: String): String
    fun discardAndStart(name: String): String
    val longSession: String
    fun longSessionBody(timed: String): String
    val turnTheWheels: String
    val keptAsTimed: String
    fun storedAs(duration: String): String
    val saveSession: String
    val backToWorkout: String
    val ready: String
    val nothingPlannedYet: String
    fun exercisesAndSets(exercises: Int, sets: Int): String
    val startWorkout: String
    val total: String
    val rest: String
    val skip: String
    val ok: String
    val end: String
    val programDay: String
    val programDayNote: String
    val noneFreeWorkout: String
    val weightsFromOtherScheme: String
    val weightsFromFreeSessions: String
    val startBlank: String
    val columnSet: String
    val columnPrev: String
    val columnReps: String
    val columnSeconds: String
    val columnKm: String
    fun nHidden(n: Int): String
    val plusAddSet: String
    val changeExercise: String
    val moveUp: String
    val moveDown: String
    fun optionsFor(name: String): String
    fun weightForSet(set: String, value: String?): String
    fun previousSet(set: String, value: String?): String
    fun setDone(set: String, done: Boolean): String
    fun removeSet(set: String): String
    val leaveAtZero: String
    val noWeight: String
    val previousMonth: String
    val nextMonth: String
    val chosen: String

    // ---- Platform notices -------------------------------------------------------------------------------------

    fun trayInProgress(title: String, resting: Boolean): String
    fun trayResume(title: String): String
    val skipRest: String
    val chooseCsvExports: String
    fun restingUntil(clock: String): String
    fun runningSince(clock: String, elapsed: String): String
    val restOver: String
    fun restOverBody(title: String, elapsed: String): String
    val underAMinute: String

    companion object {
        val all: List<Strings> get() = listOf(English, Russian)

        /** The language for a BCP 47 tag ("ru", "ru-RU", "en_GB"); [English] for anything unknown. */
        fun forLanguage(tag: String?): Strings {
            val language = tag?.substringBefore('-')?.substringBefore('_')?.lowercase() ?: return English
            return all.firstOrNull { it.languageTag == language } ?: English
        }

        /** The device's language as of now; asked again each time, so a change of language is picked up on relaunch. */
        fun system(): Strings = forLanguage(systemLanguageTag())
    }
}

/** The device's preferred language as a BCP 47 tag ("ru-RU"), or null when it cannot be read. */
expect fun systemLanguageTag(): String?
