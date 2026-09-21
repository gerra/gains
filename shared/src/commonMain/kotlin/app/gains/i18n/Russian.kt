package app.gains.i18n

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
import app.gains.domain.WeightUnit
import app.gains.program.Progression
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate

/**
 * Русский. Plural forms follow the three-way rule (1 подход, 2 подхода, 5 подходов; see
 * [plural]); dates use the genitive month ("12 февраля") and headings the nominative ("Февраль").
 * A built-in exercise, program, day or slot note not found in [RussianNames] is shown in English.
 */
object Russian : Strings {
    override val languageTag = "ru"

    /**
     * The Russian plural: [one] for 1, 21, 31… (but not 11), [few] for 2–4, 22–24… (but not 12–14),
     * [many] for everything else, zero included.
     */
    fun plural(n: Int, one: String, few: String, many: String): String {
        val abs = if (n < 0) -n else n
        val form = when {
            abs % 10 == 1 && abs % 100 != 11 -> one
            abs % 10 in 2..4 && abs % 100 !in 12..14 -> few
            else -> many
        }
        return "$n $form"
    }

    override fun unit(unit: WeightUnit) = when (unit) { WeightUnit.KG -> "кг"; WeightUnit.LBS -> "lbs" }
    override val minuteAbbrev = "мин"
    override val hourAbbrev = "ч"
    override val secondAbbrev = "с"
    override val kmAbbrev = "км"
    override fun reps(n: Int) = "$n повт."
    override fun sessions(n: Int) = plural(n, "тренировка", "тренировки", "тренировок")
    override fun sets(n: Int) = plural(n, "подход", "подхода", "подходов")
    override fun exercises(n: Int) = plural(n, "упражнение", "упражнения", "упражнений")
    override fun weeks(n: Int) = plural(n, "неделя", "недели", "недель")
    override fun files(n: Int) = plural(n, "файл", "файла", "файлов")
    override fun rows(n: Int) = plural(n, "строка", "строки", "строк")
    override fun newExercises(n: Int) = plural(n, "новое упражнение", "новых упражнения", "новых упражнений")
    override fun outlierHolds(n: Int) = plural(n, "сомнительное удержание", "сомнительных удержания", "сомнительных удержаний")
    override fun daysAWeek(n: Int) = plural(n, "день в неделю", "дня в неделю", "дней в неделю")
    override fun daysPerWeekTag(n: Int) = "$n дн/нед"
    override fun weekStreak(n: Int) = "${plural(n, "неделя", "недели", "недель")} подряд"
    override fun weekWord(n: Int) = plural(n, "неделя", "недели", "недель").substringAfter(' ')
    override fun sessionWord(n: Int) = sessions(n).substringAfter(' ')

    override val monthsShort = listOf("янв", "фев", "мар", "апр", "мая", "июн", "июл", "авг", "сен", "окт", "ноя", "дек")
    override val monthsFull = listOf("Январь", "Февраль", "Март", "Апрель", "Май", "Июнь", "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь")
    override fun dayShort(day: DayOfWeek) = when (day) {
        DayOfWeek.MONDAY -> "Пн"; DayOfWeek.TUESDAY -> "Вт"; DayOfWeek.WEDNESDAY -> "Ср"; DayOfWeek.THURSDAY -> "Чт"
        DayOfWeek.FRIDAY -> "Пт"; DayOfWeek.SATURDAY -> "Сб"; DayOfWeek.SUNDAY -> "Вс"
    }
    override val today = "Сегодня"
    override val yesterday = "Вчера"

    override fun muscleGroup(group: MuscleGroup) = when (group) {
        MuscleGroup.CHEST -> "Грудь"
        MuscleGroup.FRONT_DELTS -> "Передние дельты"
        MuscleGroup.SIDE_DELTS -> "Средние дельты"
        MuscleGroup.REAR_DELTS -> "Задние дельты"
        MuscleGroup.LATS -> "Широчайшие"
        MuscleGroup.UPPER_BACK -> "Верх спины"
        MuscleGroup.LOWER_BACK -> "Поясница"
        MuscleGroup.TRAPS -> "Трапеции"
        MuscleGroup.BICEPS -> "Бицепс"
        MuscleGroup.TRICEPS -> "Трицепс"
        MuscleGroup.FOREARMS -> "Предплечья"
        MuscleGroup.QUADS -> "Квадрицепсы"
        MuscleGroup.HAMSTRINGS -> "Бицепс бедра"
        MuscleGroup.GLUTES -> "Ягодицы"
        MuscleGroup.CALVES -> "Икры"
        MuscleGroup.CORE -> "Кор"
        MuscleGroup.NECK -> "Шея"
    }
    override fun modality(modality: Modality) = when (modality) {
        Modality.WEIGHTED -> "С весом"
        Modality.BODYWEIGHT -> "Своим весом"
        Modality.ISOMETRIC -> "Статика"
        Modality.CARDIO -> "Кардио"
    }
    override fun equipment(equipment: Equipment) = when (equipment) {
        Equipment.BARBELL -> "Штанга"
        Equipment.DUMBBELL -> "Гантели"
        Equipment.KETTLEBELL -> "Гиря"
        Equipment.CABLE -> "Блок"
        Equipment.MACHINE -> "Тренажёр"
        Equipment.BODYWEIGHT -> "Свой вес"
        Equipment.BANDS -> "Резинки"
        Equipment.OTHER -> "Другое"
    }
    override fun goal(goal: Goal) = when (goal) {
        Goal.BUILD_MUSCLE -> "Набрать мышцы"
        Goal.GET_STRONGER -> "Стать сильнее"
        Goal.LOSE_FAT -> "Сбросить жир"
        Goal.GENERAL_FITNESS -> "Общая форма"
    }
    override fun goalBlurb(goal: Goal) = when (goal) {
        Goal.BUILD_MUSCLE -> "Больше повторов и подходов; главное — недельный объём."
        Goal.GET_STRONGER -> "Тяжёлые базовые движения, линейная прогрессия; главное — расчётный 1ПМ."
        Goal.LOSE_FAT -> "Сохранять силу и тренироваться регулярно; главное — регулярность."
        Goal.GENERAL_FITNESS -> "Сбалансированная программа, которой можно держаться годами."
    }
    override fun experience(experience: Experience) = when (experience) {
        Experience.BEGINNER -> "Новичок"
        Experience.INTERMEDIATE -> "Средний"
        Experience.ADVANCED -> "Продвинутый"
    }
    override fun experienceBlurb(experience: Experience) = when (experience) {
        Experience.BEGINNER -> "Меньше года регулярных тренировок или возвращение после долгого перерыва."
        Experience.INTERMEDIATE -> "От года до трёх; добавлять вес каждую тренировку уже не выходит."
        Experience.ADVANCED -> "Несколько лет; прогресс приходит блоками, а не тренировками."
    }
    override fun themeMode(mode: ThemeMode) = when (mode) { ThemeMode.DARK -> "Тёмная"; ThemeMode.LIGHT -> "Светлая"; ThemeMode.SYSTEM -> "Системная" }
    override fun accountKind(kind: AccountKind) = when (kind) { AccountKind.GUEST -> "Гость"; AccountKind.GOOGLE -> "Google"; AccountKind.APPLE -> "Apple" }
    override fun insightKind(kind: InsightKind) = when (kind) {
        InsightKind.REGRESSION -> "Регресс"
        InsightKind.STALL -> "Застой"
        InsightKind.NEGLECT -> "Заброшено"
        InsightKind.CONSISTENCY -> "Регулярность"
        InsightKind.PROGRESS -> "Прогресс"
    }
    override fun skipReason(reason: SkipReason) = when (reason) {
        SkipReason.EMPTY_ROW -> "Пустая строка (нет упражнения или показателей)"
        SkipReason.BAD_DATE -> "Нечитаемая дата"
        SkipReason.BAD_NUMBER -> "Нечитаемое число"
        SkipReason.WRONG_COLUMN_COUNT -> "Неверное число столбцов"
    }
    override fun progressionSource(source: Progression.Source) = when (source) {
        Progression.Source.FREE_SESSION -> "свободная тренировка"
        Progression.Source.DIFFERENT_SCHEME -> "другая схема"
    }
    override fun csvProblem(problem: CsvProblem) = when (problem) {
        CsvProblem.Empty -> "Файл пуст."
        CsvProblem.Unrecognised -> "Это не похоже на экспорт тренировок. Нужны столбцы с датой, упражнением, весом и повторами."
        is CsvProblem.MissingColumns -> "Нет столбцов: ${problem.columns.joinToString()}."
        is CsvProblem.NotLiftoff -> "Это не экспорт Liftoff: нет столбцов ${problem.columns.joinToString()}."
        CsvProblem.NoneReadable -> "Ни один из файлов не удалось прочитать."
    }

    override fun exerciseName(exercise: Exercise) = if (exercise.isBuiltIn) RussianNames.exercises[exercise.id] ?: exercise.name else exercise.name
    override fun programName(program: Program) = if (program.isBuiltIn) RussianNames.programs[program.id] ?: program.name else program.name
    override fun programDescription(program: Program) = if (program.isBuiltIn) RussianNames.programDescriptions[program.id] ?: program.description else program.description
    override fun programDayName(day: ProgramDay) = RussianNames.dayName(day.name)
    override fun slotNote(note: String) = RussianNames.slotNotes[note] ?: note
    override fun copyOf(name: String) = "$name (копия)"

    override val done = "Готово"
    override val cancel = "Отмена"
    override val delete = "Удалить"
    override val back = "Назад"
    override val close = "Закрыть"
    override val change = "Изменить"
    override val remove = "Убрать"
    override val reset = "Сбросить"
    override val edit = "Редактировать"
    override val show = "Показать"
    override val hide = "Скрыть"
    override val on = "Вкл"
    override val off = "Выкл"
    override val all = "Все"
    override val none = "Нет"
    override val start = "Начать"
    override val notSet = "Не задан"
    override val perDumbbell = "На гантель"
    override val perDumbbellSuffix = " · на гантель"
    override val warmUp = "Разминка"

    override val tabHome = "Главная"
    override val tabHistory = "История"
    override val tabLifts = "Упражнения"
    override val tabVolume = "Объём"
    override val tabBody = "Вес"
    override val addDescription = "Добавить"
    override val settingsDescription = "Настройки"
    override fun menuStartDay(dayName: String) = "Начать: $dayName"
    override val menuStartWorkout = "Начать тренировку"
    override val menuLogPastWorkout = "Записать прошедшую"
    override val menuImportCsv = "Импорт CSV"
    override val menuPrograms = "Программы"
    override fun restCountdown(clock: String) = "Отдых $clock"
    override val resumeChevron = "Вернуться ›"

    override val signInHeadline = "Знай, что\nреально растёт."
    override val signInBlurb = "Записывай тренировки или импортируй из Liftoff, Strong, Hevy и любого CSV. Gains покажет, какие упражнения растут, стоят на месте или проседают, — с цифрами."
    override val continueAsGuest = "Продолжить как гость"
    override val guestNoteWithSync = "В режиме гостя всё хранится на этом устройстве. Войдите позже, чтобы сделать резервную копию и синхронизацию."
    override val guestNoteComingSoon = "Вход и облачная синхронизация появятся позже. В режиме гостя всё хранится на этом устройстве."
    override fun signInNotConfigured(provider: AccountKind) = "Вход через ${accountKind(provider)} пока не настроен."
    override val heroExercise = "Жим лёжа"
    override val heroDetail = "62.5 кг × 8 (20 авг) — на 6% больше, чем 60 кг × 8 в июне."
    override val featureLog = "Записывай"
    override val featureLogBody = "Подходы, повторы, статика, кардио"
    override val featureImport = "Импортируй"
    override val featureImportBody = "Liftoff · Strong · Hevy · CSV"
    override val featureAnalyse = "Анализируй"
    override val featureAnalyseBody = "1ПМ, объём, серии"

    override val skipForNow = "Пропустить"
    override val onboardingGoalTitle = "Ради чего\nтренируешься?"
    override val onboardingGoalBlurb = "От этого зависят предлагаемые программы и то, какие сигналы идут первыми на главном экране."
    override val onboardingExperienceTitle = "Как давно\nзанимаешься?"
    override val onboardingExperienceBlurb = "Новички добавляют вес каждую тренировку; дальше прогресс идёт медленнее, и программы меняются."
    override val onboardingDaysTitle = "Сколько дней\nв неделю?"
    override val onboardingDaysBlurb = "Выбирай то, что реально выдержишь, а не то, на что надеешься. Программы подбираются под это."
    override fun onboardingDaysNote(days: Int) = when (days) {
        2 -> "Две тренировки на всё тело. Хватит, чтобы стать сильнее; масса пойдёт медленнее."
        3 -> "Классика. Всё тело или цикл жим/тяга/ноги раз в неделю."
        4 -> "Сплит верх/низ хорошо ложится на четыре дня."
        5 -> "Есть место для акцента на группу мышц или верх/низ плюс один день."
        else -> "Жим/тяга/ноги дважды в неделю. Ограничением становится восстановление."
    }
    override val programsThatFit = "Подходящие программы"
    override fun profileSummary(goal: Goal, experience: Experience, days: Int) = "${goal(goal)} · ${experience(experience)} · ${daysAWeek(days)}"
    override val bestMatch = "Лучший вариант"
    override val useThisProgram = "Выбрать эту программу"
    override val justSaveMyGoal = "Только сохранить цель"
    override val next = "Далее"
    override val active = "Активна"

    override val noWorkoutsYet = "Тренировок пока нет"
    override val noWorkoutsYetBody = "Запиши первую тренировку или перенеси историю из Liftoff, Strong, Hevy или любого CSV с тренировками."
    override val logAWorkout = "Записать тренировку"
    override val importHistory = "Импортировать историю"
    override val homeTitle = "Прогресс"
    override fun lastSession(date: String) = "Последняя тренировка $date"
    override val plusLog = "+ Записать"
    override val whatsMoving = "Что меняется"
    override fun goalHeadline(goal: Goal?) = when (goal) {
        Goal.GET_STRONGER -> "Сначала сила"
        Goal.BUILD_MUSCLE -> "Сначала объём и забытые мышцы"
        Goal.LOSE_FAT -> "Сначала регулярность"
        Goal.GENERAL_FITNESS, null -> null
    }
    override val oneSessionImported = "Импортирована одна тренировка. Для выводов нужно несколько недель истории для сравнения."
    override val nothingToFlag = "Пока отмечать нечего. Продолжай импортировать — и тренды появятся здесь."
    override fun upNext(programName: String) = "ДАЛЬШЕ · ${programName.uppercase()}"
    override fun exercisesAndWeekCount(exercises: Int, done: Int, perWeek: Int) = "${exercises(exercises)} · $done из $perWeek на этой неделе"
    override val wholeProgram = "Вся программа ›"
    override val pickAProgram = "ВЫБЕРИ ПРОГРАММУ"
    override val chooseRoutineBlurb = "Выбери программу — и каждый её день станет готовой тренировкой с твоими последними весами."
    override val chooseAProgram = "Выбрать программу ›"
    override val setYourGoal = "ЗАДАЙ ЦЕЛЬ"
    override val whatAreYouTrainingFor = "Ради чего тренируешься?"
    override val threeQuickQuestions = "Три коротких вопроса — и программа под твою неделю, где каждый день уже готовая тренировка."
    override val getStarted = "Начать ›"
    override val thisWeek = "Эта неделя"
    override val heroSessions = "Тренировок"
    override val heroLifts = "Упражнений"
    override val heroUp = "Растут"
    override val heroDown = "Падают"
    override fun sessionLink(date: String) = "Тренировка $date ›"

    override fun regressionDetail(current: String, best: String, bestDate: String, drop: String) = "Сейчас $current, было $best ($bestDate) — минус $drop."
    override fun stallDetail(value: String, since: String, sessions: Int, weeks: Int) = "$value с $since. ${sessions(sessions)}, без изменений ${plural(weeks, "неделю", "недели", "недель")}."
    override fun neglectedExerciseDetail(lastDate: String, weeksAgo: Int, priorSessions: Int, lookbackWeeks: Int) =
        "Последний раз $lastDate, ${plural(weeksAgo, "неделю", "недели", "недель")} назад, после ${sessionsGenitive(priorSessions)} за предыдущие ${plural(lookbackWeeks, "неделю", "недели", "недель")}."
    override fun neglectedMuscleDetail(recentAvg: String, recentWeeks: Int, baselineAvg: String, baselineWeeks: Int) =
        "$recentAvg подх./нед за последние ${plural(recentWeeks, "неделю", "недели", "недель")} против $baselineAvg/нед за ${plural(baselineWeeks, "неделю", "недели", "недель")} до этого."
    override fun consistencyRecent(rate: String, weeks: Int) = "$rate тренировки/нед за последние ${plural(weeks, "неделю", "недели", "недель")}"
    override fun consistencyUp(recent: String, previous: String, weeks: Int) = "$recent, больше, чем $previous за ${plural(weeks, "неделю", "недели", "недель")} до этого."
    override fun consistencyDown(recent: String, previous: String, weeks: Int) = "$recent, меньше, чем $previous за ${plural(weeks, "неделю", "недели", "недель")} до этого."
    override fun consistencySteady(recent: String, previous: String, weeks: Int) = "$recent, примерно как $previous за ${plural(weeks, "неделю", "недели", "недель")} до этого."
    override val trainingLessOften = "Тренировок стало меньше"
    override val trainingMoreOften = "Тренировок стало больше"
    override val steadyFrequency = "Стабильная частота"
    override fun progressDetail(current: String, currentDate: String, gain: String, previous: String, previousDate: String) =
        "$current ($currentDate) — на $gain больше, чем $previous ($previousDate)."

    /** "1 тренировки", "5 тренировок": the genitive that follows "после". */
    private fun sessionsGenitive(n: Int) = plural(n, "тренировки", "тренировок", "тренировок")

    override val noSessionsYet = "Тренировок пока нет"
    override val noSessionsYetBody = "Запиши тренировку здесь или импортируй историю. Календарь и тренд тренировок в неделю будут заполняться по ходу."
    override val historyTitle = "История"
    override fun onRecord(sessions: Int) = "${sessions(sessions)} в записи"
    override val perWeek = "В неделю"
    override fun lastNWeeks(n: Int) = "последние ${plural(n, "неделя", "недели", "недель")}"
    override val trend = "Тренд"
    override val trendUp = "Рост"
    override val trendDown = "Спад"
    override val trendSteady = "Ровно"
    override fun wasPerWeek(rate: String) = "было $rate/нед"
    override val streak = "Серия"
    override val last26Weeks = "Последние 26 недель"
    override val tapADayToOpen = "Нажми на день, чтобы открыть"
    override val sessionsPerWeek = "Тренировок в неделю"
    override val fourWeekAverage = "Среднее за 4 недели"
    override val sessionsLegend = "Тренировки"
    override fun heatmapDay(date: LocalDate, sessions: Int) =
        "${dayShort(date.dayOfWeek)} ${dateShortWithYear(date)}, " + if (sessions == 0) "нет тренировок" else sessions(sessions)
    override val logged = "вручную"
    override fun durationSuffix(minutes: Int) = " · $minutes мин"

    override val noLiftsYet = "Упражнений пока нет"
    override val noLiftsYetBody = "Импортируй экспорт Liftoff — и каждое записанное упражнение появится здесь."
    override val liftsTitle = "Упражнения"
    override fun liftsSubtitle(count: Int) = "${exercises(count)}, последние сверху"
    override val searchLifts = "Поиск упражнений"

    override val unknownExercise = "Неизвестное упражнение"
    override val unknownExerciseBody = "Этого упражнения больше нет. Возможно, его объединили с другим."
    override val noSessions = "Нет тренировок"
    override val noSessionsForExercise = "Это упражнение не встречается ни в одной импортированной тренировке."
    override val currentBest = "Текущий лучший"
    override val allTimeBest = "Лучший за всё время"
    override val atYourBest = "На пике"
    override val fromAllTimeBest = "от лучшего результата"
    override val window = "Период"
    override fun windowLabel(days: Int?) = when (days) { 90 -> "3 мес"; 180 -> "6 мес"; 365 -> "1 год"; else -> "Всё" }
    override val nothingInWindow = "За этот период ничего нет"
    override fun pickALongerWindow(sessions: Int) = "Выбери период подлиннее, чтобы увидеть ${sessionsAccusative(sessions)} в записи."
    override val oneSessionInWindow = "За этот период одна тренировка. Графику нужны хотя бы две точки, чтобы показать тренд."
    override val e1rmSection = "Расчётный 1ПМ · по Эпли, рабочие подходы"
    override val noWeightedSetsInWindow = "За этот период нет рабочих подходов с весом."
    override val e1rm = "1ПМ"
    override val topSetWeight = "Вес верхнего подхода"
    override val topSet = "Верхний подход"
    override val volumePerSession = "Объём за тренировку · Σ вес × повторы"
    override val totalVolume = "Общий объём"
    override fun bestMetricPerSession(modality: Modality) = when (modality) {
        Modality.WEIGHTED -> "Лучший 1ПМ за тренировку"
        Modality.BODYWEIGHT -> "Лучшие повторы за тренировку"
        Modality.ISOMETRIC -> "Лучшее удержание за тренировку"
        Modality.CARDIO -> "Лучшая дистанция за тренировку"
    }
    override fun metricLabel(modality: Modality) = when (modality) {
        Modality.WEIGHTED -> "1ПМ"
        Modality.BODYWEIGHT -> "повторы"
        Modality.ISOMETRIC -> "удержание"
        Modality.CARDIO -> "дистанция"
    }
    override val workingSetRule = "Правило рабочих подходов"
    override val sessionsTapToOpen = "Тренировки · нажми, чтобы открыть"
    override fun e1rmValue(weight: String) = "1ПМ $weight"
    override fun bestSetVolume(weight: String) = "лучший подход $weight"
    override fun volumeValue(weight: String) = "объём $weight"
    override fun workingSetsOf(working: Int, total: Int) = "$working/$total рабочих подходов"
    override fun workingSetRuleBlurb(percent: String, defaultPercent: String?) =
        "Подходы с весом от $percent верхнего веса тренировки считаются рабочими." + (defaultPercent?.let { " (по умолчанию $it)" } ?: "")
    override val resetToDefault = "Вернуть по умолчанию"

    /** "5 тренировок", "2 тренировки": the accusative after "увидеть". */
    private fun sessionsAccusative(n: Int) = plural(n, "тренировку", "тренировки", "тренировок")

    override val addExercises = "Добавить упражнения"
    override fun inYourLibrary(count: Int) = "${plural(count, "упражнение", "упражнения", "упражнений")} в библиотеке"
    override fun nSelected(count: Int) = "Выбрано: $count"
    override val searchOrTypeNew = "Поиск или новое упражнение"
    override val clear = "Очистить"
    override val recent = "Недавние"
    override fun noExercisesFor(group: MuscleGroup, query: String) = "Нет упражнений для группы «${muscleGroup(group)}»${if (query.isNotEmpty()) " по запросу «$query»" else ""}."
    override fun nothingMatches(query: String) = "Ничего не найдено по запросу «$query»."
    override val results = "Результаты"
    override val selectExercises = "Выбери упражнения"
    override fun addNExercises(count: Int) = "Добавить: ${exercises(count)}"
    override fun createNamed(name: String) = "Создать «$name»"
    override val customExerciseGuessed = "Своё упражнение · мышцы подобраны по названию"
    override val added = "Добавлено"
    override fun replaceNamed(name: String) = "Заменить: $name"

    override val noVolumeYet = "Объёма пока нет"
    override val noVolumeYetBody = "Рабочие подходы по группам мышц за неделю появятся здесь после импорта тренировок."
    override val volumeTitle = "Объём"
    override val volumeSubtitle = "Рабочие подходы по группам мышц за неделю"
    override val setsSoFar = "подходов пока"
    override val lastWeek = "Прошлая неделя"
    override fun weekCommencing(date: String) = "с $date"
    override val avg = "Среднее"
    override fun nWeekCaption(weeks: Int) = "за ${plural(weeks, "неделю", "недели", "недель")}"
    override val onTheBody = "На теле"
    override fun nWeekAvg(weeks: Int) = "ср. $weeks нед"
    override fun nWeeksChip(weeks: Int) = "$weeks нед"
    override val bodyMapThisWeek = "Эта неделя"
    override val bodyMapLastWeek = "Прошлая"
    override val bodyMapAvg = "Среднее"
    override fun setsThisWeek(sets: String) = "$sets подх. $windowThisWeek"
    override fun setsLastWeek(sets: String) = "$sets подх. $windowLastWeek"
    override fun setsAWeekOnAverage(sets: String) = "$sets подх. $windowAverage"
    override val showAll = "Показать все"
    override fun bodyMapBlurb(windowSets: String) = "Рабочие подходы $windowSets, спереди и сзади. Нажми на мышцу, чтобы увидеть её цифры и отфильтровать список."
    override val windowThisWeek = "на этой неделе"
    override val windowLastWeek = "на прошлой неделе"
    override val windowAverage = "в неделю в среднем"
    override val bodyMapBlurbSelected = "Нажми на мышцу ещё раз или «Показать все», чтобы увидеть все группы."
    override val volumeCreditBlurb = "Подход засчитывается целиком основным мышцам и наполовину вспомогательным. Разминка и кардио не считаются."
    override fun thisWeekFrom(date: String) = "Эта неделя · с $date"
    override fun volumeBarsBlurb(junk: Int, maintenance: Int) =
        "Шкала до $junk подходов; отметка на $maintenance. Меньше $maintenance подходов в неделю — поддержание; больше $junk — скорее всего, лишний объём."
    override val statusNone = "нет"
    override fun statusUnder(sets: Int) = "меньше $sets"
    override val statusOnTarget = "в норме"
    override fun statusOver(sets: Int) = "больше $sets"
    override fun legendMaxSets(sets: Int) = "$sets+ подх."

    override val bodyweightTitle = "Вес тела"
    override val bodyweightSubtitle = "Ежедневные записи и среднее за 7 дней"
    override val plusAdd = "+ Добавить"
    override val noBodyweightEntries = "Записей веса нет"
    override val noBodyweightEntriesBody = "Записывай вес здесь. Среднее за 7 дней сглаживает дневные колебания, а сверху можно наложить силовой тренд любого упражнения."
    override val latest = "Последний"
    override val sevenDayAvg = "Ср. за 7 дней"
    override val changeLabel = "Изменение"
    override fun sinceDate(date: String) = "с $date"
    override val oneEntrySoFar = "Пока одна запись. График появится после второй."
    override val daily = "По дням"
    override fun e1rmOf(exercise: String) = "$exercise, 1ПМ"
    override val overlayALift = "Наложить упражнение"
    override val entries = "Записи"
    override val date = "Дата"
    override val time = "Время"
    override val duration = "Длительность"
    override val weightLabel = "Вес"
    override val saveEntry = "Сохранить запись"
    override val chooseAWeight = "Выбери вес."
    override val importToOverlay = "Импортируй тренировки, чтобы наложить упражнение."
    override val chooseALift = "Выбрать упражнение"
    override val rightAxisBlurb = "Правая ось: расчётный 1ПМ за тренировку (Эпли: вес × (1 + повторы/30))."

    override val settingsTitle = "Настройки"
    override val account = "Аккаунт"
    override val notSignedIn = "Вход не выполнен"
    override val guestDataNote = "Данные хранятся только на этом устройстве. Войдите позже, чтобы сделать резервную копию и синхронизацию."
    override val syncedToServer = "Синхронизируется с вашим сервером"
    override val signIn = "Войти"
    override val signOut = "Выйти"
    override val signInNotConfiguredNote = "Вход через Google и Apple пока не настроен; сервер синхронизации появится позже."
    override val trainingGoal = "Цель тренировок"
    override val daysAWeekLabel = "Дней в неделю"
    override val activeProgram = "Активная программа"
    override val redoSetup = "Пройти настройку заново"
    override val noGoalSetNote = "Цель не задана. Выбери её — и программы отсортируются по соответствию, а главный экран начнёт с важных для неё сигналов."
    override fun goalSortNote(goal: Goal) = "Программы отсортированы по соответствию цели «${goal(goal)}»; главный экран начинает с её сигналов."
    override val appearance = "Оформление"
    override val displayUnits = "Единицы"
    override val unitsNote = "Веса хранятся в кг (с округлением до 0.25 кг), в чём бы они ни показывались. Для упражнений с гантелями показан вес одной гантели."
    override val warmUps = "Разминка"
    override val prefillWarmUps = "Заполнять разминочные подходы"
    override val emptyBar = "Пустой гриф"
    override val warmUpsNote = "В дни программы T1 начинается с пустого грифа, затем 40, 60 и 80% рабочего веса; T2 — гриф и 60%. Разминочные подходы помечены и не идут в объём, рекорды и прогрессию."
    override val customExercises = "Свои упражнения"
    override val allExercisesMatched = "Все импортированные упражнения совпали со встроенным каталогом."
    override val customExercisesNote = "Названия, которых каталог не узнал. Объедини такое с упражнением из каталога, чтобы совместить историю и запомнить соответствие для будущих импортов."
    override val noMuscleGroupsGuessed = "Группы мышц не подобраны"
    override val mergeInto = "Объединить с…"
    override val aliases = "Псевдонимы"
    override val workingSetOverrides = "Свои правила рабочих подходов"
    override val data = "Данные"
    override val dataNote = "Импортированные тренировки и записи веса хранятся в локальной базе на этом устройстве. Ничего не покидает устройство, пока нет синхронизации и вы не вошли в аккаунт."
    override val deleteAllSessions = "Удалить все импортированные тренировки"
    override val deleteAllSessionsTitle = "Удалить все тренировки?"
    override val deleteAllSessionsBody = "Импортированные тренировки будут удалены. Записи веса, псевдонимы и правила останутся. CSV можно импортировать заново в любой момент."

    override val programsTitle = "Программы"
    override val setAGoalToSort = "Задай цель в настройках, чтобы отсортировать по соответствию"
    override val plusNew = "+ Новая"
    override val yourPrograms = "Твои программы"
    override val builtInBestFit = "Встроенные, самые подходящие сверху"
    override val builtIn = "Встроенные"
    override val builtInNote = "Встроенные программы повторяют программы из вики r/Fitness и r/bodyweightfitness. Открой одну и продублируй, чтобы поменять упражнения или подходы."
    override val deactivate = "Отключить"
    override val activate = "Активировать"
    override val duplicateToEdit = "Дублировать и изменить"
    override val schedule = "Расписание"
    override fun weeksAndDays(weeks: Int, daysPerWeek: Int) = "${weeks(weeks)} · ${daysAWeek(daysPerWeek)}"
    override val days = "Дни"
    override val tapAnyDayToStart = "Нажми на день, чтобы начать"
    override val howItProgresses = "Как идёт прогрессия"
    override val deleteProgram = "Удалить программу"
    override fun deleteNamed(name: String) = "Удалить «$name»?"
    override val deleteProgramBody = "Записанные по ней тренировки останутся, только потеряют метку дня."
    override val upNextPill = "Следующий"
    override fun lastDone(date: String) = "Был $date"
    override val notDoneYet = "Ещё не был"
    override fun weekN(n: Int) = "Неделя $n"
    override val rotationNote = "Дни идут в этом порядке по мере выполнения, независимо от дня недели. Начни другой день — и остальные пойдут за ним."
    override val noAutomaticRule = "Без автоматического правила. Каждый день заполняется весами и повторами из последней тренировки с этим упражнением."
    override fun restBetweenSets(rest: String) = "Отдых между подходами $rest."
    override fun restRange(fromSeconds: Int, toSeconds: Int) =
        if (fromSeconds % 60 == 0 && toSeconds % 60 == 0 && fromSeconds >= 60) "${fromSeconds / 60}–${toSeconds / 60} $minuteAbbrev"
        else "$fromSeconds–$toSeconds $secondAbbrev"
    override val warmUpRestOrAsNeeded = "или по самочувствию"
    override fun restLine(rest: String, warmupRest: String?) = "Отдых между подходами $rest" + (warmupRest?.let { " · разминка $it" } ?: "") + "."

    override val newProgram = "Новая программа"
    override val editProgram = "Изменить программу"
    override val name = "Название"
    override val descriptionOptional = "Описание (необязательно)"
    override val plusAddDay = "+ Добавить день"
    override val daysRotateNote = "Каждый день — одна тренировка. Дни чередуются в этом порядке по мере выполнения."
    override val dayName = "Название дня"
    override fun dayN(n: Int) = "День $n"
    override val plusAddExercise = "+ Добавить упражнение"
    override val saveProgram = "Сохранить программу"
    override val giveTheProgramAName = "Дай программе название."
    override val addAtLeastOneDay = "Добавь хотя бы один день."
    override fun dayHasNoExercises(day: String) = "В дне «$day» нет упражнений."
    override fun slotInvalid(exercise: String) = "$exercise: подходов должно быть от 1 до 20, а повторы выглядят как 5, 8-12 или 5+."
    override val setsLabel = "Подходы"
    override val repsLabel = "Повторы"
    override val note = "Заметка"
    override val programLadderKept = "Лестница программы сохранена"
    override val progressionNone = "Нет"
    override val progressionSmall = "+2.5 кг"
    override val progressionBig = "+5 кг"
    override val progressionDouble = "Повторы, потом вес"
    override fun changeNamed(name: String) = "Изменить: $name"

    override fun lastLabel(weight: String, reps: String) = "Прошлый раз: $weight$reps"
    override fun hintTry(last: String, weight: String) = "$last → пробуй $weight"
    override fun hintRepeat(last: String, weight: String) = "$last → повтори $weight"
    override fun hintMoveOn(last: String, maxReps: Int) = "$last → все подходы по $maxReps: переходи к следующей прогрессии"
    override fun hintTryReps(last: String, weight: String, reps: Int) = "$last → пробуй $weight × $reps"
    override fun hintTarget(last: String, weightPrefix: String, reps: Int) = "$last → $weightPrefix$reps"
    override fun hintTryStage(last: String, weight: String, stage: String) = "$last → пробуй $weight, $stage"
    override fun hintMissed(last: String, stage: String, weight: String) = "$last → повторы не вышли: $stage на $weight"
    override fun hintReset(last: String, weight: String, stage: String) = "$last → цикл пройден: сброс до $weight и снова $stage"
    override fun hintEstimate(last: String, e1rm: String, tier: String, start: String, cap: String) = "$last → расч. 1ПМ ~$e1rm → старт $tier $start$cap"
    override fun capKeptUnder(weight: String) = " (ниже $weight)"
    override val withNoAddedLoad = "без отягощения"
    override val asLightAsYouCanLoad = "с минимальным весом"
    override fun hintStart(last: String, source: String, target: String, at: String) = "$last ($source) → начни с $target$at"
    override fun atWeight(weight: String) = " на $weight"
    override fun describeLinear(step: String) = "Добавляй $step каждую тренировку, где выполнены все подходы. Не вышли повторы — вес повторяется."
    override fun describeDouble(min: Int, max: Int, then: String) = "Повторы растут с $min до $max на одном весе. Когда все подходы доходят до $max, $then."
    override fun describeDoubleThenAdd(step: String, min: Int) = "добавь $step и вернись к $min"
    override val describeDoubleThenHarder = "переходи к более сложному варианту"
    override fun describeLadder(stages: String, step: String, first: String) =
        "Ступени: $stages. Повторы выполнены: добавь $step и останься на ступени. Не выполнены: следующая ступень на том же весе. Не вышла последняя ступень: сбрось около 10% и начни заново с $first."

    override val importYourHistory = "Импортируй историю"
    override val importBlurb = "Экспорты Liftoff, Strong и Hevy распознаются сами, а ещё подойдёт любой CSV со столбцами даты, упражнения, веса и повторов. Выбери один или несколько файлов; перед сохранением покажем сводку, и каждая тренировка сохраняется только один раз."
    override val chooseCsvFiles = "Выбрать CSV-файлы"
    override val readingTheFile = "Читаем файл…"
    override fun readingFiles(count: Int) = "Читаем ${files(count)}…"
    override val saving = "Сохраняем…"
    override val couldNotImport = "Не удалось импортировать"
    override val couldNotReadTheFile = "Не удалось прочитать файл."
    override fun importFailed(reason: String) = "Ошибка импорта: $reason"
    override fun savingFailed(reason: String) = "Ошибка сохранения: $reason"
    override val chooseAnotherFile = "Выбрать другой файл"
    override val imported = "Импортировано"
    override fun importedSummary(sessions: Int, exercisesCreated: Int, outliersDiscarded: Int) = buildString {
        append("Сохранено: ").append(sessions(sessions))
        if (exercisesCreated > 0) append(", создано: ").append(newExercises(exercisesCreated))
        if (outliersDiscarded > 0) append(", отброшено: ").append(outlierHolds(outliersDiscarded))
        append(".")
    }
    override val importTitle = "Импорт"
    override val csv = "CSV"
    override fun appearedInSeveralFiles(sessions: Int) = "${sessions(sessions)} встречается в нескольких файлах и будет сохранено один раз."
    override val weightsInFileAreIn = "Веса в файле указаны в"
    override val unitNote = "Для файлов, где единица не указана (Liftoff экспортирует фунты, даже если ты записываешь в кг). Веса хранятся в кг с округлением до 0.25 кг."
    override val summary = "Сводка"
    override val sessionsFound = "Найдено тренировок"
    override val dateRange = "Период"
    override val newSessions = "Новых тренировок"
    override val changedSinceLastImport = "Изменились с прошлого импорта"
    override val alreadyImported = "Уже импортированы (пропущены)"
    override val inMoreThanOneFile = "В нескольких файлах (объединены)"
    override val duplicateSessions = "Дубликаты (пропущены)"
    override val durationsDiscarded = "Отброшено длительностей (>4 ч)"
    override val newExercisesLabel = "Новых упражнений"
    override val emptyRowsSkipped = "Пропущено пустых строк"
    override fun rowsSkipped(reason: SkipReason) = "Пропущено строк: ${skipReason(reason).lowercase()}"
    override val exercisesNotInCatalogue = "Упражнений нет в каталоге"
    override val exercisesNotInCatalogueNote = "Они будут созданы как свои упражнения. Позже их можно объединить с упражнением из каталога в настройках."
    override val duplicatesDetected = "Найдены дубликаты"
    override fun loggedTwice(date: String, alreadyStored: Boolean) = "$date · записано дважды" + if (alreadyStored) " (уже сохранено)" else ""
    override val suspiciousHolds = "Сомнительные удержания"
    override val keepAll = "Оставить все"
    override val discardAll = "Отбросить все"
    override val suspiciousHoldsNote = "Эти статические удержания длиннее обычного для упражнения более чем в 5 раз. Неотмеченные будут отброшены."
    override fun holdOutlier(sets: Int, seconds: String, usual: String) = "${sets(sets)} по $seconds (обычно $usual) · похоже на значение таймера по умолчанию"
    override val nothingNew = "Ничего нового"
    override fun importN(count: Int) = "Импортировать $count"

    override val logWorkout = "Записать тренировку"
    override val editWorkout = "Изменить тренировку"
    override val workout = "Тренировка"
    override val notPartOfAProgram = "Вне программы"
    override fun startedAt(clock: String) = "Начало в $clock. Отмечай подход, когда он сделан, — запустится таймер отдыха."
    override val lookOverThePlan = "Просмотри план и нажми «Начать». С этого момента идёт время, подходы можно менять и отмечать, а отметка подхода запускает таймер отдыха."
    override val exercisesSection = "Упражнения"
    override fun addAnExerciseNote(unit: String) = "Добавь упражнение, чтобы записывать подходы. Веса в $unit; для упражнений со своим весом оставь вес пустым, для статики укажи секунды, для кардио — км. Отмечай подход, когда он сделан, — запустится таймер отдыха."
    override val discard = "Отменить"
    override val endSession = "Завершить"
    override val saveWorkout = "Сохранить тренировку"
    override val saveChanges = "Сохранить изменения"
    override val addAtLeastOneExercise = "Добавь хотя бы одно упражнение с подходом."
    override fun warmUpN(n: String) = "Разминка $n"
    override fun setN(n: String) = "Подход $n"
    override val deleteThisWorkout = "Удалить эту тренировку?"
    override val deleteThisWorkoutBody = "Она исчезнет из истории и всех расчётов."
    override val discardThisWorkout = "Отменить эту тренировку?"
    override val discardThisWorkoutBody = "Ничего из неё не сохранится. Если выйти кнопкой «Назад», она продолжит идти."
    override val keepGoing = "Продолжить"
    override fun setsNotTicked(count: Int) = "${plural(count, "подход не отмечен", "подхода не отмечены", "подходов не отмечены")}"
    override fun leaveOutOrSave(count: Int) = if (count == 1) "Не включать его в тренировку или сохранить как выполненный?" else "Не включать их в тренировку или сохранить как выполненные?"
    override val leaveOut = "Не включать"
    override val saveAll = "Сохранить все"
    override val workoutInProgress = "Тренировка уже идёт"
    override fun conflictBody(running: String, ago: String, wanted: String) = "«$running» начата $ago назад и не завершена. Вернуться к ней или отменить её и начать «$wanted»?"
    override fun resumeNamed(name: String) = "Вернуться: $name"
    override fun discardAndStart(name: String) = "Отменить и начать: $name"
    override val longSession = "Долгая тренировка"
    override fun longSessionBody(timed: String) = "Таймер шёл $timed. Столько и тренировался? Если нет, поменяй длительность ниже."
    override val turnTheWheels = "Покрути колёсики до реального времени тренировки."
    override val keptAsTimed = "Как по таймеру."
    override fun storedAs(duration: String) = "Будет сохранено: $duration."
    override val saveSession = "Сохранить"
    override val backToWorkout = "К тренировке"
    override val ready = "ГОТОВО К СТАРТУ"
    override val nothingPlannedYet = "План пока пуст"
    override fun exercisesAndSets(exercises: Int, sets: Int) = "${exercises(exercises)} · ${sets(sets)}"
    override val startWorkout = "Начать тренировку"
    override val total = "ВСЕГО"
    override val rest = "ОТДЫХ"
    override val skip = "Пропустить"
    override val ok = "ОК"
    override val end = "Завершить"
    override val programDay = "День программы"
    override val programDayNote = "Тренировка с меткой дня идёт в зачёт прогрессии этого дня. Без метки — свободная тренировка."
    override val noneFreeWorkout = "Нет (свободная тренировка)"
    override val weightsFromOtherScheme = "Веса оценены по другой схеме этой программы."
    override val weightsFromFreeSessions = "Веса оценены по твоим свободным тренировкам."
    override val startBlank = "Начать с нуля"
    override val columnSet = "ПОДХ"
    override val columnPrev = "БЫЛО"
    override val columnReps = "ПОВТ"
    override val columnSeconds = "СЕК"
    override val columnKm = "КМ"
    override fun nHidden(n: Int) = "скрыто: $n"
    override val plusAddSet = "+ Добавить подход"
    override val changeExercise = "Заменить упражнение"
    override val moveUp = "Выше"
    override val moveDown = "Ниже"
    override fun optionsFor(name: String) = "Действия: $name"
    override fun weightForSet(set: String, value: String?) = "Вес подхода $set, ${value ?: "нет"}"
    override fun previousSet(set: String, value: String?) = "Прошлый подход $set, ${value ?: "нет"}"
    override fun setDone(set: String, done: Boolean) = "Подход $set ${if (done) "выполнен" else "не выполнен"}"
    override fun removeSet(set: String) = "Убрать подход $set"
    override val leaveAtZero = "Оставь ноль, если не засекал время."
    override val noWeight = "Без веса"
    override val previousMonth = "Предыдущий месяц"
    override val nextMonth = "Следующий месяц"
    override val chosen = "выбрано"

    override fun trayInProgress(title: String, resting: Boolean) = if (resting) "$title идёт, отдых" else "$title идёт"
    override fun trayResume(title: String) = "Вернуться: $title"
    override val skipRest = "Пропустить отдых"
    override val chooseCsvExports = "Выбери CSV-экспорты"
    override fun restingUntil(clock: String) = "Отдых до $clock. "
    override fun runningSince(clock: String, elapsed: String) = "Начало в $clock, прошло $elapsed. Нажми, чтобы вернуться к тренировке."
    override val restOver = "Отдых окончен"
    override fun restOverBody(title: String, elapsed: String) = "$title, прошло $elapsed. Нажми, чтобы вернуться к тренировке."
    override val underAMinute = "меньше минуты"
}
