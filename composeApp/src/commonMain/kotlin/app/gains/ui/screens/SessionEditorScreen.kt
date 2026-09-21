package app.gains.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gains.analysis.Dates
import app.gains.analysis.Format
import app.gains.analysis.PreviousSets
import app.gains.analysis.TrainingData
import app.gains.analysis.TrainingSnapshot
import app.gains.analysis.UnitLabels
import app.gains.data.ExerciseRepository
import app.gains.data.LiveSessionRepository
import app.gains.data.ProgramRepository
import app.gains.data.SessionRepository
import app.gains.data.SettingsRepository
import app.gains.domain.Exercise
import app.gains.domain.ExerciseEntry
import app.gains.domain.LiveExercise
import app.gains.domain.LiveSession
import app.gains.domain.Modality
import app.gains.domain.Program
import app.gains.domain.ProgramDay
import app.gains.domain.ProgramDayRef
import app.gains.domain.RestTimer
import app.gains.domain.Session
import app.gains.domain.SetDraft
import app.gains.domain.WeightUnit
import app.gains.importer.ExerciseResolver
import app.gains.program.DayPlanner
import app.gains.program.Gzclp
import app.gains.program.PlanOptions
import app.gains.program.Progression
import app.gains.ui.ScreenModel
import app.gains.ui.components.ChooserRow
import app.gains.ui.components.DatePickerSheet
import app.gains.ui.components.Dp16
import app.gains.ui.components.DurationPickerSheet
import app.gains.ui.components.DurationWheels
import app.gains.ui.components.GainsCard
import app.gains.ui.components.Pill
import app.gains.ui.components.PrimaryButton
import app.gains.ui.components.SecondaryButton
import app.gains.ui.components.SectionHeader
import app.gains.ui.components.TimePickerSheet
import app.gains.ui.components.WeightPickerSheet
import app.gains.ui.demo.ExerciseDemoSheet
import app.gains.resources.Res
import app.gains.resources.*
import app.gains.ui.i18n.*
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import app.gains.ui.inject
import app.gains.ui.nowMs
import app.gains.ui.rememberScreenModel
import app.gains.ui.theme.GainsColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

internal data class ExerciseDraft(val exercise: Exercise, val sets: List<SetDraft>, val note: String = "") {
    val warmups: List<SetDraft> get() = sets.filter { it.isWarmup }
    val workSets: List<SetDraft> get() = sets.filter { !it.isWarmup }

    /** "W1, W2…" for the warm-ups and "1, 2…" for the work sets, in row order. */
    val labels: List<String> get() {
        var warmup = 0
        var work = 0
        return sets.map { if (it.isWarmup) "W${++warmup}" else (++work).toString() }
    }

    /**
     * What each row was last time, in row order: the same-numbered warm-up or work set of [previous]
     * as "60×5", or null where last time had no such set. All null without a previous session.
     */
    fun previousLabels(previous: ExerciseEntry?, unit: WeightUnit, labels: UnitLabels): List<String?> {
        var warmup = 0
        var work = 0
        return sets.map { set ->
            val ordinal = if (set.isWarmup) ++warmup else ++work
            PreviousSets.matching(previous, set.isWarmup, ordinal)?.let { PreviousSets.label(it, exercise.modality, unit, labels) }
        }
    }
}

/** A program day the workout can be tagged with: "GZCLP · A1". */
internal data class ProgramDayOption(val ref: ProgramDayRef, val programName: String, val dayName: String) {
    val label: String get() = "$programName · $dayName"
}

internal data class EditorState(
    val loading: Boolean = true,
    val isNew: Boolean = true,
    val id: String? = null,
    /** When the workout was, for a logged one; a timed workout takes its clock's start instead. */
    val date: LocalDate? = null,
    val time: LocalTime? = null,
    val durationMinutes: Int? = null,
    val exercises: List<ExerciseDraft> = emptyList(),
    val unit: WeightUnit = WeightUnit.KG,
    val catalogue: List<Exercise> = emptyList(),
    /** Most recently trained first; the picker shows these on top. */
    val recent: List<Exercise> = emptyList(),
    /** Set when a save found nothing to store: the screen says so. */
    val error: Boolean = false,
    val saved: Boolean = false,
    /**
     * The caption written on the summary screen, carried through the editor so that editing a
     * workout does not drop it. Changed on the summary screen, never here.
     */
    val caption: String? = null,
    /** The id an ended timed workout was stored under; the screen hands it to its summary. */
    val endedId: String? = null,
    /**
     * The program day this workout counts towards. Set when started from a program day, and editable:
     * a free workout tagged with a day drives that day's progression like one started from it.
     */
    val programDay: ProgramDayRef? = null,
    val title: String = "",
    /** Every day of every program, for the tag picker. */
    val programDays: List<ProgramDayOption> = emptyList(),
    /** exercise id -> "5 × 3+" */
    val targets: Map<String, String> = emptyMap(),
    /** exercise id -> the progression hint ("Last: 60 kg × 5,5,5 → try 62.5 kg"), worded on screen. */
    val hints: Map<String, Progression.Hint> = emptyMap(),
    /** exercise id -> the exercise's most recent session before this one, for the PREV column of its set table. */
    val previous: Map<String, ExerciseEntry> = emptyMap(),
    /** exercise id -> program note */
    val notes: Map<String, String> = emptyMap(),
    /** Exercises whose weights were borrowed from a session outside this slot's scheme; the card offers to clear them. */
    val seeded: Set<String> = emptySet(),
    /** exercise id -> where the borrowed weights came from, for the "estimated from…" line. */
    val sources: Map<String, Progression.Source> = emptyMap(),
    /** exercise id -> GZCLP tier of its slot, which sets the rest guidance and the timer length. */
    val tiers: Map<String, Gzclp.Tier> = emptyMap(),
    /** Exercises whose warm-up rows are folded away. */
    val collapsedWarmups: Set<String> = emptySet(),
    val restTimer: RestTimer? = null,
    /** Set when Save found filled work sets that were never ticked: how many, while the lifter decides. */
    val untickedOnSave: Int? = null,
    /**
     * Opened to run a workout against the clock rather than log a past one. The plan can be looked
     * over and adjusted first; nothing runs, ticks or is stored until Start is pressed.
     */
    val timed: Boolean = false,
    /**
     * Set once Start was pressed: when the clock started. The total time counts from it, it becomes
     * the session's timestamp, and the editor is stored to survive the app being killed.
     */
    val startedAtMs: Long? = null,
    /** A different workout is already in progress; the lifter picks which one to keep. */
    val conflict: LiveSession? = null,
    /** The timer ran past three hours: the minutes it measured, awaiting confirmation before the session is stored. */
    val longSessionMinutes: Int? = null,
) {
    val programDayOption: ProgramDayOption? get() = programDay?.let { ref -> programDays.firstOrNull { it.ref == ref } }
    /** The clock is running. */
    val isRunning: Boolean get() = startedAtMs != null
    /** Whether the plan can be changed: always for a logged workout, and once started for a timed one. */
    val editable: Boolean get() = !timed || isRunning

    /**
     * The exercise at [index] swapped for [exercise], with its sets, ticks and note kept: the same
     * work under another name. What described the slot stays with it (target, program note, tier,
     * folded warm-ups, a running rest); what described the old exercise's history goes (the hint,
     * the borrowed-weights offer) and the PREV column takes [previous], the new exercise's last
     * session. Unchanged when [exercise] is already in the workout or [index] is out of range.
     */
    fun replacing(index: Int, exercise: Exercise, previous: ExerciseEntry?): EditorState {
        val old = exercises.getOrNull(index) ?: return this
        val from = old.exercise.id
        val to = exercise.id
        if (from == to || exercises.any { it.exercise.id == to }) return this
        fun <V> Map<String, V>.rekeyed(): Map<String, V> = this[from]?.let { (this - from) + (to to it) } ?: this
        return copy(
            exercises = exercises.mapIndexed { i, e -> if (i == index) e.copy(exercise = exercise) else e },
            targets = targets.rekeyed(), notes = notes.rekeyed(), tiers = tiers.rekeyed(),
            hints = hints - from,
            seeded = seeded - from, sources = sources - from,
            collapsedWarmups = if (from in collapsedWarmups) collapsedWarmups - from + to else collapsedWarmups,
            previous = if (previous != null) (this.previous - from) + (to to previous) else this.previous - from,
            restTimer = restTimer?.let { if (it.exerciseId == from) it.copy(exerciseId = to) else it },
        )
    }

    /** The exercise at [index] moved [delta] places (−1 up, +1 down); unchanged when that leaves the list. */
    fun moved(index: Int, delta: Int): EditorState {
        val target = index + delta
        if (index !in exercises.indices || target !in exercises.indices) return this
        return copy(exercises = exercises.toMutableList().apply { add(target, removeAt(index)) })
    }

    /** The editor as a workout in progress, or null when there is nothing to keep. */
    fun toLive(): LiveSession? {
        val started = startedAtMs ?: return null
        if (loading || conflict != null) return null
        return LiveSession(
            startedAtMs = started, title = title, program = programDay, rest = restTimer,
            exercises = exercises.map { e ->
                LiveExercise(e.exercise.id, e.sets, e.note, seeded = e.exercise.id in seeded, warmupsCollapsed = e.exercise.id in collapsedWarmups)
            },
        )
    }
}

internal class SessionEditorModel(
    private val sessionId: String?,
    private val programDay: ProgramDayRef? = null,
    /** Open a timed workout, ready to start (or resume the one running), rather than log a past one. */
    private val live: Boolean = false,
    /** The titles and day tags the model makes. */
    private val texts: Texts,
    private val sessions: SessionRepository = inject(),
    private val exercises: ExerciseRepository = inject(),
    private val liveSessions: LiveSessionRepository = inject(),
    settings: SettingsRepository = inject(),
    trainingData: TrainingData = inject(),
    private val programs: ProgramRepository = inject(),
) : ScreenModel() {
    private val _state = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _state

    /** What the planner and the restore need, kept for a resume after the conflict dialog. */
    /** The editor's fixed titles, read from the resources once when the model starts. */
    private class Titles(val logWorkout: String, val editWorkout: String, val workout: String)

    private class Context(
        val snapshot: TrainingSnapshot, val unit: WeightUnit, val planOptions: PlanOptions, val programs: List<Program>, val dayOptions: List<ProgramDayOption>,
        val titles: Titles,
        /** program day id -> its name on screen, for the title of a planned workout. */
        private val dayNames: Map<String, String>,
        /** The workout being edited, when it is a logged one: its own sets are not "previous", nor are any logged after it. */
        val editing: Session? = null,
    ) {
        fun dayName(day: ProgramDay): String = dayNames[day.id] ?: day.name

        /** The exercise's last session before this workout, for the PREV column; null when it has never been trained. */
        fun previousFor(exerciseId: String): ExerciseEntry? =
            PreviousSets.lastEntry(snapshot, exerciseId, before = editing?.timestamp, excludeSessionId = editing?.id)
    }
    private var context: Context? = null
    private var persistJob: Job? = null
    /** Set once the workout was stored or discarded, so nothing writes it back afterwards. */
    private var finished = false
    /** Set once the duration was chosen here; the stored one then no longer flows in over it. */
    private var durationTouched = false
    /** The answer to the unticked-sets question, carried through the long-session question to the store. */
    private var includeUntickedOnEnd = false

    init {
        scope.launch {
            val snapshot = trainingData.snapshot.first()
            val unit = settings.observeUnit().first()
            val planOptions = PlanOptions(barKg = settings.observeBarWeightKg().first(), warmups = settings.observeAutoWarmups().first())
            // The raw session carries only the warm-up flags the lifter set; the snapshot adds inferred ones,
            // which must not be frozen into the row on save.
            val existing = sessionId?.let { id -> sessions.observeRawSessions().first().firstOrNull { it.id == id } }
            val programList = programs.observePrograms().first()
            val dayOptions = programList.flatMap { p -> val programName = p.resolvedName(texts); p.days.map { ProgramDayOption(ProgramDayRef(p.id, it.id), programName, it.resolvedName(texts)) } }
            val titles = Titles(texts.get(Res.string.log_workout), texts.get(Res.string.edit_workout), texts.get(Res.string.workout))
            val ctx = Context(snapshot, unit, planOptions, programList, dayOptions, titles, dayOptions.associate { it.ref.dayId to it.dayName }, editing = existing)
            context = ctx
            val stored = if (live && existing == null) liveSessions.load() else null
            _state.value = when {
                existing != null -> EditorState(
                    loading = false, isNew = false, id = existing.id, date = existing.date,
                    time = LocalTime(existing.timestamp.hour, existing.timestamp.minute),
                    durationMinutes = existing.durationMinutes,
                    exercises = existing.exercises.mapNotNull { entry ->
                        snapshot.exercisesById[entry.exerciseId]?.let { ex -> ExerciseDraft(ex, entry.sets.map { SetDraft.from(it, unit) }, entry.note ?: "") }
                    },
                    unit = unit, catalogue = snapshot.exercises.sortedBy { it.name }, recent = snapshot.trainedExercises.take(12),
                    programDay = existing.program, title = ctx.titles.editWorkout, programDays = ctx.dayOptions,
                    caption = existing.caption,
                )
                // Coming back to the running workout, whether from the resume bar, a relaunch or the same day's Start.
                stored != null && (programDay == null || stored.program == programDay) -> restored(ctx, stored)
                // Any other day opens ready to start; a different workout still running is asked about at Start.
                programDay != null -> planned(ctx, programDay).copy(timed = live)
                live -> fresh(ctx).copy(title = ctx.titles.workout, timed = true)
                else -> fresh(ctx).copy(title = ctx.titles.logWorkout)
            }.withPrevious(ctx)
            if (live) persistWhileRunning()
            if (existing != null) followStoredSummary(existing.id)
        }
    }

    /**
     * The summary screen changes a stored workout's duration and caption while its editor waits
     * underneath, so the editor follows them rather than saving what it read when it opened. A
     * duration chosen here wins: that is an edit of its own, not yet saved.
     */
    private fun followStoredSummary(id: String) {
        scope.launch {
            sessions.observeRawSessions()
                .map { list -> list.firstOrNull { it.id == id } }
                .map { it?.let { s -> s.durationMinutes to s.caption } }
                .distinctUntilChanged()
                .collect { stored ->
                    if (stored == null || finished) return@collect
                    update { s -> s.copy(durationMinutes = if (durationTouched) s.durationMinutes else stored.first, caption = stored.second) }
                }
        }
    }

    private fun fresh(ctx: Context): EditorState {
        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        return EditorState(
            loading = false, isNew = true, date = now.date, time = LocalTime(now.hour, now.minute),
            unit = ctx.unit, catalogue = ctx.snapshot.exercises.sortedBy { it.name }, recent = ctx.snapshot.trainedExercises.take(12),
            programDays = ctx.dayOptions, title = ctx.titles.logWorkout,
        )
    }

    /** A fresh editor pre-filled from a program day; a plain fresh one when the day no longer exists. */
    private fun planned(ctx: Context, ref: ProgramDayRef?): EditorState {
        val base = fresh(ctx)
        if (ref == null) return base
        val program = ctx.programs.firstOrNull { it.id == ref.programId }
        val day = program?.day(ref.dayId) ?: return base
        val plan = DayPlanner.plan(program, day, ctx.snapshot, ctx.unit, ctx.planOptions)
        return base.copy(
            programDay = ref, title = ctx.dayName(day),
            exercises = plan.exercises.map { pe ->
                ExerciseDraft(pe.exercise, pe.sets.map { ps ->
                    SetDraft(
                        weight = ps.weightKg?.let { Format.weightValue(it, ctx.unit) } ?: "",
                        reps = ps.reps?.toString() ?: "",
                        seconds = ps.seconds?.toString() ?: "",
                        isWarmup = ps.isWarmup,
                    )
                })
            },
            targets = plan.exercises.associate { it.exercise.id to it.targetLabel },
            hints = plan.exercises.mapNotNull { pe -> pe.hint?.let { pe.exercise.id to it } }.toMap(),
            notes = plan.exercises.mapNotNull { pe -> pe.slot.note?.let { pe.exercise.id to it } }.toMap(),
            seeded = plan.exercises.filter { it.seeded }.map { it.exercise.id }.toSet(),
            sources = plan.exercises.mapNotNull { pe -> pe.source?.let { pe.exercise.id to it } }.toMap(),
            tiers = plan.exercises.mapNotNull { pe -> pe.tier?.let { pe.exercise.id to it } }.toMap(),
        )
    }

    /**
     * The stored workout back in the editor. The plan's guidance (targets, hints, tiers) is recomputed
     * from the day; the sets, ticks, notes and the clock are exactly as they were left.
     */
    private fun restored(ctx: Context, stored: LiveSession): EditorState = planned(ctx, stored.program).copy(
        timed = true, programDay = stored.program, title = stored.title, startedAtMs = stored.startedAtMs, restTimer = stored.rest,
        exercises = stored.exercises.mapNotNull { le -> ctx.snapshot.exercisesById[le.exerciseId]?.let { ExerciseDraft(it, le.sets, le.note) } },
        seeded = stored.exercises.filter { it.seeded }.map { it.exerciseId }.toSet(),
        collapsedWarmups = stored.exercises.filter { it.warmupsCollapsed }.map { it.exerciseId }.toSet(),
    )

    /** Writes the running workout to the database shortly after every change, so a kill loses at most a moment's typing. */
    private fun persistWhileRunning() {
        persistJob = scope.launch {
            _state.map { it.toLive() }.distinctUntilChanged().collectLatest { snapshot ->
                if (snapshot == null) return@collectLatest
                delay(PERSIST_DELAY_MS)
                // A newer change waits for this write rather than racing it.
                withContext(NonCancellable) { liveSessions.save(snapshot) }
            }
        }
    }

    /** The PREV column's data for every exercise in the editor. */
    private fun EditorState.withPrevious(ctx: Context): EditorState =
        copy(previous = exercises.mapNotNull { e -> ctx.previousFor(e.exercise.id)?.let { e.exercise.id to it } }.toMap())

    private fun update(f: (EditorState) -> EditorState) { _state.value = f(_state.value) }

    /**
     * An edit of the plan: exercises, sets, values, ticks and notes. A timed workout is read-only
     * until Start, so the plan can be looked over but not changed; the screen shows it all disabled.
     */
    private fun edit(f: (EditorState) -> EditorState) = update { s -> if (s.editable) f(s) else s }
    fun setDate(v: LocalDate) = update { it.copy(date = v) }
    fun setTime(v: LocalTime) = update { it.copy(time = v) }
    fun setDuration(v: Int?) {
        durationTouched = true
        update { it.copy(durationMinutes = v?.takeIf { m -> m > 0 }) }
    }

    /**
     * Starts the clock. A different workout still running is asked about first rather than silently
     * replaced; the one for this very day would have been resumed when the editor opened.
     */
    fun start() {
        val s = _state.value
        if (!s.timed || s.isRunning || s.conflict != null) return
        scope.launch {
            val running = liveSessions.load()
            if (running != null) update { it.copy(conflict = running) }
            else update { it.copy(startedAtMs = nowMs(), error = false) }
        }
    }

    fun addExercise(exercise: Exercise) = edit { s ->
        if (s.exercises.any { it.exercise.id == exercise.id }) s
        else {
            val previous = context?.previousFor(exercise.id)
            s.copy(
                exercises = s.exercises + ExerciseDraft(exercise, listOf(SetDraft())),
                previous = if (previous != null) s.previous + (exercise.id to previous) else s.previous,
            )
        }
    }

    fun addExercises(picked: List<Exercise>) = picked.forEach { addExercise(it) }

    /** A name not in the catalogue becomes a custom exercise with guessed muscles; returned so the picker can tick it. */
    fun createExercise(name: String): Exercise {
        val resolver = ExerciseResolver(_state.value.catalogue, emptyMap())
        val exercise = resolver.resolve(name, emptyList())
        scope.launch { exercises.insertIfMissing(listOf(exercise)) }
        update { it.copy(catalogue = (it.catalogue + exercise).distinctBy { e -> e.id }.sortedBy { e -> e.name }) }
        return exercise
    }

    fun removeExercise(index: Int) = edit { it.copy(exercises = it.exercises.filterIndexed { i, _ -> i != index }) }

    /** Swap the exercise of a card for another, keeping its sets, ticks and note (see [EditorState.replacing]). */
    fun replaceExercise(index: Int, exercise: Exercise) = edit { it.replacing(index, exercise, context?.previousFor(exercise.id)) }

    /** Move a card up (−1) or down (+1) the workout. */
    fun moveExercise(index: Int, delta: Int) = edit { it.moved(index, delta) }

    /** Tag the workout with a program day, or none for a free workout. */
    fun setProgramDay(ref: ProgramDayRef?) = update { it.copy(programDay = ref) }

    /** Drop the weights borrowed from outside the slot's scheme; sets and reps stay as prescribed. Warm-ups built on those weights go too. */
    fun startBlank(index: Int) = edit { s ->
        s.copy(
            exercises = s.exercises.mapIndexed { i, e -> if (i != index) e else e.copy(sets = e.workSets.map { it.copy(weight = "") }) },
            seeded = s.seeded - s.exercises[index].exercise.id,
        )
    }

    fun toggleWarmups(index: Int) = update { s ->
        val id = s.exercises[index].exercise.id
        s.copy(collapsedWarmups = if (id in s.collapsedWarmups) s.collapsedWarmups - id else s.collapsedWarmups + id)
    }

    fun removeWarmups(index: Int) = edit { s ->
        s.copy(exercises = s.exercises.mapIndexed { i, e -> if (i != index) e else e.copy(sets = e.workSets) })
    }

    /**
     * Tick a set off, or un-tick it. Ticking starts the rest timer at the tier's short end (warm-ups get
     * the warm-up rest, exercises with no tier a default); un-ticking leaves the timer alone. An empty
     * set cannot be ticked: there is nothing to record. A timed workout ticks only once it has started.
     */
    fun toggleDone(exerciseIndex: Int, setIndex: Int) = edit { s ->
        val exercise = s.exercises[exerciseIndex]
        val set = exercise.sets[setIndex]
        if (!set.done && !set.hasValues) return@edit s
        val done = !set.done
        val seconds = when {
            set.isWarmup -> Gzclp.WARMUP_REST.first
            else -> s.tiers[exercise.exercise.id]?.restTimerSeconds ?: Gzclp.DEFAULT_REST_SECONDS
        }
        s.copy(
            exercises = s.exercises.mapIndexed { i, e ->
                if (i != exerciseIndex) e else e.copy(sets = e.sets.mapIndexed { j, d -> if (j == setIndex) d.copy(done = done) else d })
            },
            restTimer = if (done) RestTimer(exercise.exercise.id, nowMs() + seconds * 1000L, seconds) else s.restTimer,
        )
    }

    fun dismissRest() = update { it.copy(restTimer = null) }

    /**
     * "Skip rest" from the platform's notice. True when the workout runs in this editor, which then
     * drops the rest (and writes it out with the next persist); false when the running workout is
     * not this editor's, so the caller has to change it in the database instead.
     */
    fun skipRest(): Boolean {
        val s = _state.value
        if (!live || finished || !s.isRunning || s.conflict != null) return false
        dismissRest()
        return true
    }
    fun setNote(index: Int, note: String) = edit { it.copy(exercises = it.exercises.mapIndexed { i, e -> if (i == index) e.copy(note = note) else e }) }

    /** Adds a work set like the last one (never a copy of a warm-up). */
    fun addSet(index: Int) = edit { s ->
        s.copy(exercises = s.exercises.mapIndexed { i, e ->
            if (i != index) e else e.copy(sets = e.sets + (e.workSets.lastOrNull()?.copy(done = false) ?: SetDraft()))
        })
    }

    /** Clearing every field of a ticked set un-ticks it: an empty set cannot count as done. */
    fun updateSet(exerciseIndex: Int, setIndex: Int, draft: SetDraft) = edit { s ->
        val next = if (draft.done && !draft.hasValues) draft.copy(done = false) else draft
        s.copy(exercises = s.exercises.mapIndexed { i, e ->
            if (i != exerciseIndex) e else e.copy(sets = e.sets.mapIndexed { j, d -> if (j == setIndex) next else d })
        })
    }

    fun removeSet(exerciseIndex: Int, setIndex: Int) = edit { s ->
        s.copy(exercises = s.exercises.mapIndexed { i, e ->
            if (i != exerciseIndex) e else e.copy(sets = e.sets.filterIndexed { j, _ -> j != setIndex })
        })
    }

    /**
     * Filled work sets that were ticked off, or every set when ticking was not used or the lifter asked
     * for them all (see [untickedWorkSets]). Null, with the error shown, when nothing is left.
     */
    private fun entries(s: EditorState, includeUnticked: Boolean): List<ExerciseEntry>? {
        val unticked = untickedWorkSets(s.exercises, s.unit)
        val entries = s.exercises.mapNotNull { draft ->
            val sets = draft.sets
                .filter { includeUnticked || unticked == 0 || it.isWarmup || it.done }
                .mapIndexedNotNull { i, d -> d.toSet(i, s.unit) }.mapIndexed { i, set -> set.copy(order = i) }
            if (sets.isEmpty()) null else ExerciseEntry(draft.exercise.id, sets, draft.note.ifBlank { null })
        }
        if (entries.isEmpty()) { update { it.copy(error = true) }; return null }
        return entries
    }

    /**
     * True when the lifter must first be asked about filled work sets that were never ticked: some
     * sets were ticked and others not, the answer is not in yet, and the question is not already up.
     */
    private fun askAboutUnticked(s: EditorState, includeUnticked: Boolean): Boolean {
        val unticked = untickedWorkSets(s.exercises, s.unit)
        if (unticked == 0 || includeUnticked || s.untickedOnSave != null) return false
        update { it.copy(untickedOnSave = unticked) }
        return true
    }

    /**
     * Save the workout. When some work sets were ticked off and others with values were not, the
     * lifter is asked first whether the unticked ones count (see [untickedWorkSets]); a workout where
     * nothing was ticked saves every set, as a log typed in after the fact would expect.
     */
    fun save(includeUnticked: Boolean = false) {
        val s = _state.value
        if (s.timed) return endSession(includeUnticked)
        // Both are set with the editor and only ever replaced through the choosers.
        val date = s.date ?: return
        val time = s.time ?: return
        if (askAboutUnticked(s, includeUnticked)) return
        val entries = entries(s, includeUnticked)
        update { it.copy(untickedOnSave = null) }
        if (entries == null) return
        val timestamp = LocalDateTime(date, time)
        val session = Session(
            id = s.id ?: timestamp.toString(),
            timestamp = timestamp,
            durationMinutes = s.durationMinutes?.takeIf { it > 0 },
            exercises = entries,
            source = Session.MANUAL,
            program = s.programDay,
            caption = s.caption,
        )
        scope.launch {
            // Ids are minute-precision timestamps; two workouts saved in the same minute must not replace each other.
            val id = s.id ?: uniqueId(timestamp.toString(), sessions.ids())
            sessions.upsert(session.copy(id = id))
            update { it.copy(saved = true, error = false) }
        }
    }

    /**
     * Ends the timed workout: the clock's minutes become the duration and its start the timestamp.
     * Unticked work sets are asked about as on save; past three hours the lifter is also asked whether
     * the timer was left running.
     */
    fun endSession(includeUnticked: Boolean = false) {
        val s = _state.value
        val started = s.startedAtMs ?: return
        if (askAboutUnticked(s, includeUnticked)) return
        val entries = entries(s, includeUnticked)
        update { it.copy(untickedOnSave = null) }
        if (entries == null) return
        includeUntickedOnEnd = includeUnticked
        val minutes = LiveSession.durationMinutes(nowMs() - started)
        if (LiveSession.isLong(nowMs() - started)) update { it.copy(longSessionMinutes = minutes, error = false) }
        else finish(minutes)
    }

    /** Leave the unticked sets in the editor and drop the save prompt. */
    fun dismissSavePrompt() = update { it.copy(untickedOnSave = null) }

    /** The answer to the long-session question: the duration to store. */
    fun confirmEnd(minutes: Int) { update { it.copy(longSessionMinutes = null) }; finish(minutes) }
    fun cancelEnd() = update { it.copy(longSessionMinutes = null) }

    private fun finish(minutes: Int) {
        val s = _state.value
        val started = s.startedAtMs ?: return
        val entries = entries(s, includeUntickedOnEnd) ?: return
        val startedAt = Instant.fromEpochMilliseconds(started).toLocalDateTime(TimeZone.currentSystemDefault())
        val timestamp = LocalDateTime(startedAt.date, LocalTime(startedAt.hour, startedAt.minute))
        finished = true
        scope.launch {
            // Runs to the end even if the screen is left meanwhile: a session must never be stored
            // with its live copy still around. Any write in flight lands first.
            withContext(NonCancellable) {
                persistJob?.cancelAndJoin()
                val id = uniqueId(timestamp.toString(), sessions.ids())
                sessions.upsert(Session(id, timestamp, minutes, entries, Session.MANUAL, s.programDay))
                liveSessions.clear()
                update { it.copy(endedId = id) }
            }
            update { it.copy(saved = true, error = false) }
        }
    }

    /** Throws the timed workout away. */
    fun discardLive() {
        finished = true
        scope.launch {
            withContext(NonCancellable) {
                persistJob?.cancelAndJoin()
                liveSessions.clear()
            }
            update { it.copy(saved = true) }
        }
    }

    /** Conflict dialog: go back to the workout that was already running. */
    fun resumeStored() {
        val ctx = context ?: return
        val stored = _state.value.conflict ?: return
        _state.value = restored(ctx, stored)
    }

    /** Conflict dialog: drop the running workout and start the one that was asked for. */
    fun discardStoredAndStart() {
        scope.launch {
            liveSessions.clear()
            update { it.copy(conflict = null, startedAtMs = nowMs()) }
        }
    }

    /** Conflict dialog dismissed: stay on this day, still not started, with the other workout untouched. */
    fun dismissConflict() = update { it.copy(conflict = null) }

    override fun onCleared() {
        // Leaving the screen keeps the workout running; make sure the last change reaches the database.
        val pending = persistJob
        val last = if (finished) null else _state.value.toLive()
        super.onCleared()
        if (last != null) flushScope.launch { pending?.join(); liveSessions.save(last) }
    }

    companion object {
        const val PERSIST_DELAY_MS = 300L

        /** Outlives the screen: a write started as the editor leaves must not be cancelled with it. */
        private val flushScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * Work sets with values that were not ticked off, when at least one work set was. Zero when
         * nothing was ticked: then ticking was not used and every set counts. Warm-ups never count here;
         * they are saved as entered either way, since nothing is analysed from them.
         */
        fun untickedWorkSets(exercises: List<ExerciseDraft>, unit: WeightUnit): Int {
            val work = exercises.flatMap { it.workSets }.filter { it.toSet(0, unit) != null }
            if (work.none { it.done }) return 0
            return work.count { !it.done }
        }

        fun uniqueId(base: String, taken: Set<String>): String {
            if (base !in taken) return base
            var n = 2
            while ("$base-$n" in taken) n++
            return "$base-$n"
        }
    }

    fun delete() {
        val id = _state.value.id ?: return
        scope.launch { sessions.deleteSession(id); update { it.copy(saved = true) } }
    }
}

/**
 * [onEnded] is called instead of [onDone] when a timed workout was ended and stored, with the id it
 * was stored under, so the session can hand over to its summary rather than simply closing.
 */
@Composable
internal fun SessionEditorScreen(
    sessionId: String?,
    programDay: ProgramDayRef? = null,
    live: Boolean = false,
    onDone: () -> Unit,
    onEnded: (String) -> Unit = { onDone() },
    onOpenSummary: (String) -> Unit = {},
) {
    val texts = rememberTexts()
    val model = rememberScreenModel(sessionId, programDay, live) { SessionEditorModel(sessionId, programDay, live, texts) }
    val state by model.state.collectAsState()
    val palette = GainsColors.palette
    var pickerOpen by remember { mutableStateOf(false) }
    /** The exercise whose "Change exercise" picker is open: its index in the workout. */
    var replaceTarget by remember { mutableStateOf<Int?>(null) }
    /** Index of the exercise whose demo sheet is open. */
    var demoTarget by remember { mutableStateOf<Int?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var dayPickerOpen by remember { mutableStateOf(false) }
    var datePickerOpen by remember { mutableStateOf(false) }
    var timePickerOpen by remember { mutableStateOf(false) }
    var durationPickerOpen by remember { mutableStateOf(false) }
    /** The set whose weight chooser is open: exercise index to set index. */
    var weightTarget by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    if (state.loading) return
    if (state.saved) {
        val ended = state.endedId
        if (ended != null) onEnded(ended) else onDone()
        return
    }
    val fieldColors = OutlinedTextFieldDefaults.colors(focusedBorderColor = palette.volt, unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant)
    val startedAt = state.startedAtMs

    Column(Modifier.fillMaxSize()) {
        // Pinned above the list however far it is scrolled: Start until the clock runs, then the clock.
        when {
            startedAt != null -> SessionClock(startedAt, state.restTimer, onSkipRest = model::dismissRest, onEnd = model::endSession)
            state.timed -> ReadyCard(state.exercises, onStart = model::start)
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(state.title, style = MaterialTheme.typography.headlineLarge)
                        if (state.programDays.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            val tag = state.programDayOption
                            Pill(tag?.label ?: stringResource(Res.string.not_part_of_a_program), if (tag != null) palette.volt else MaterialTheme.colorScheme.onSurfaceVariant, onClick = { dayPickerOpen = true })
                        }
                    }
                    if (!state.isNew) TextButton(onClick = { confirmDelete = true }) { Text(stringResource(Res.string.delete), color = palette.coral) }
                }
                Spacer(Modifier.height(12.dp))
                when {
                    startedAt != null -> {
                        val started = Instant.fromEpochMilliseconds(startedAt).toLocalDateTime(TimeZone.currentSystemDefault())
                        Text(
                            stringResource(Res.string.started_at, clock(started.hour, started.minute)),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    state.timed -> Text(
                        stringResource(Res.string.look_over_the_plan),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> WhenCard(
                        state.date, state.time, state.durationMinutes,
                        onDate = { datePickerOpen = true }, onTime = { timePickerOpen = true }, onDuration = { durationPickerOpen = true },
                        onSummary = state.id?.let { id -> { onOpenSummary(id) } },
                    )
                }
                SectionHeader(stringResource(Res.string.exercises_section), action = {
                    TextButton(onClick = { pickerOpen = true }, enabled = state.editable) { Text(stringResource(Res.string.plus_add_exercise), color = if (state.editable) palette.volt else disabledColor()) }
                })
                if (state.exercises.isEmpty()) {
                    Text(stringResource(Res.string.add_an_exercise_note, state.unit.label()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            itemsIndexed(state.exercises, key = { _, e -> e.exercise.id }) { exerciseIndex, draft ->
                ExerciseCard(
                    exerciseIndex, draft, state.unit, model, fieldColors,
                    state.targets[draft.exercise.id], state.hints[draft.exercise.id], state.notes[draft.exercise.id],
                    seeded = draft.exercise.id in state.seeded,
                    source = state.sources[draft.exercise.id],
                    tier = state.tiers[draft.exercise.id],
                    warmupsCollapsed = draft.exercise.id in state.collapsedWarmups,
                    previous = state.previous[draft.exercise.id],
                    editable = state.editable,
                    count = state.exercises.size,
                    onPickWeight = { setIndex -> weightTarget = exerciseIndex to setIndex },
                    onChangeExercise = { replaceTarget = exerciseIndex },
                    onShowDemo = { demoTarget = exerciseIndex },
                    // A moved card slides to its new place rather than jumping, so the eye can follow it.
                    modifier = Modifier.animateItem(),
                )
            }
            item {
                if (state.error) Text(stringResource(Res.string.add_at_least_one_exercise), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
                Spacer(Modifier.height(12.dp))
                when {
                    startedAt != null -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SecondaryButton(stringResource(Res.string.discard), { confirmDiscard = true }, Modifier.weight(1f))
                        PrimaryButton(stringResource(Res.string.end_session), { model.endSession() }, Modifier.weight(1f))
                    }
                    // Not started: Start stays pinned above the list, so nothing is needed down here.
                    state.timed -> {}
                    else -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SecondaryButton(stringResource(Res.string.cancel), onDone, Modifier.weight(1f))
                        PrimaryButton(if (state.isNew) stringResource(Res.string.save_workout) else stringResource(Res.string.save_changes), { model.save() }, Modifier.weight(1f))
                    }
                }
            }
        }
    }

    if (pickerOpen) ExercisePickerSheet(
        catalogue = state.catalogue,
        recent = state.recent,
        alreadyAdded = state.exercises.map { it.exercise.id }.toSet(),
        onAdd = model::addExercises,
        onCreate = model::createExercise,
        onDismiss = { pickerOpen = false },
    )
    replaceTarget?.let { index ->
        // The same picker, single-select: the tapped exercise takes the card over and its sets stay as typed.
        val current = state.exercises.getOrNull(index)
        if (current == null) replaceTarget = null
        else ExercisePickerSheet(
            catalogue = state.catalogue,
            recent = state.recent,
            alreadyAdded = state.exercises.map { it.exercise.id }.toSet(),
            onAdd = { picked -> picked.firstOrNull()?.let { model.replaceExercise(index, it) } },
            onCreate = model::createExercise,
            onDismiss = { replaceTarget = null },
            single = true,
            title = stringResource(Res.string.replace_named, current.exercise.displayName()),
        )
    }
    demoTarget?.let { index ->
        val draft = state.exercises.getOrNull(index)
        if (draft == null) demoTarget = null
        else ExerciseDemoSheet(draft.exercise, onDismiss = { demoTarget = null })
    }
    if (dayPickerOpen) ProgramDayDialog(
        options = state.programDays,
        selected = state.programDay,
        onSelect = { model.setProgramDay(it); dayPickerOpen = false },
        onDismiss = { dayPickerOpen = false },
    )
    if (datePickerOpen) state.date?.let { DatePickerSheet(it, onPick = model::setDate, onDismiss = { datePickerOpen = false }) }
    if (timePickerOpen) state.time?.let { TimePickerSheet(it, onPick = model::setTime, onDismiss = { timePickerOpen = false }) }
    if (durationPickerOpen) DurationPickerSheet(state.durationMinutes, onPick = model::setDuration, onDismiss = { durationPickerOpen = false })
    weightTarget?.let { (exerciseIndex, setIndex) ->
        val draft = state.exercises.getOrNull(exerciseIndex)
        val set = draft?.sets?.getOrNull(setIndex)
        if (draft != null && set != null) WeightPickerSheet(
            value = set.weight, unit = state.unit, title = draft.exercise.displayName(),
            subtitle = (if (set.isWarmup) stringResource(Res.string.warm_up_n, draft.labels[setIndex].trimStart('W')) else stringResource(Res.string.set_n, draft.labels[setIndex])) + if (draft.exercise.isDumbbell) stringResource(Res.string.per_dumbbell_suffix) else "",
            onPick = { model.updateSet(exerciseIndex, setIndex, set.copy(weight = it)) },
            onDismiss = { weightTarget = null },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            shape = MaterialTheme.shapes.large,
            title = { Text(stringResource(Res.string.delete_this_workout)) },
            text = { Text(stringResource(Res.string.delete_this_workout_body)) },
            confirmButton = { PrimaryButton(stringResource(Res.string.delete), onClick = { model.delete(); confirmDelete = false }) },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(Res.string.cancel)) } },
        )
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            shape = MaterialTheme.shapes.large,
            title = { Text(stringResource(Res.string.discard_this_workout)) },
            text = { Text(stringResource(Res.string.discard_this_workout_body)) },
            confirmButton = { PrimaryButton(stringResource(Res.string.discard), onClick = { model.discardLive(); confirmDiscard = false }) },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(Res.string.keep_going)) } },
        )
    }
    state.untickedOnSave?.let { count ->
        // Liftoff drops unfinished sets on finish; here the lifter chooses, since a set may have been done and just not ticked.
        AlertDialog(
            onDismissRequest = model::dismissSavePrompt,
            shape = MaterialTheme.shapes.large,
            title = { Text(pluralStringResource(Res.plurals.sets_not_ticked, count, count)) },
            text = { Text(pluralStringResource(Res.plurals.leave_out_or_save, count, count)) },
            confirmButton = { PrimaryButton(stringResource(Res.string.leave_out), onClick = { model.save(includeUnticked = false) }) },
            dismissButton = { TextButton(onClick = { model.save(includeUnticked = true) }) { Text(stringResource(Res.string.save_all), color = palette.volt) } },
        )
    }
    state.conflict?.let { running ->
        val elapsed = LiveSession.durationMinutes(running.elapsedMs(nowMs()))
        AlertDialog(
            onDismissRequest = model::dismissConflict,
            shape = MaterialTheme.shapes.large,
            title = { Text(stringResource(Res.string.workout_in_progress)) },
            text = { Text(stringResource(Res.string.conflict_body, running.title, minutesText(elapsed), state.title)) },
            confirmButton = { PrimaryButton(stringResource(Res.string.resume_named, running.title), onClick = model::resumeStored) },
            dismissButton = { TextButton(onClick = model::discardStoredAndStart) { Text(stringResource(Res.string.discard_and_start, state.title), color = palette.coral) } },
        )
    }
    state.longSessionMinutes?.let { timed -> LongSessionDialog(timed, onConfirm = model::confirmEnd, onCancel = model::cancelEnd) }
}

/** "18:05" */
private fun clock(hour: Int, minute: Int) = "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"

/** "Today", "Yesterday", otherwise "Wed 17 Sep", with the year once it is not this one. */
@Composable
private fun dateLabel(date: LocalDate): String {
    val today = Dates.today()
    return when (Dates.daysBetween(date, today)) {
        0 -> stringResource(Res.string.today)
        1 -> stringResource(Res.string.yesterday)
        else -> dateWithWeekday(date, today)
    }
}

/**
 * When a logged workout was and how long it took, as three rows that open a chooser each: a
 * calendar for the date, wheels for the time and the duration. Nothing is typed.
 */
@Composable
private fun WhenCard(
    date: LocalDate?,
    time: LocalTime?,
    durationMinutes: Int?,
    onDate: () -> Unit,
    onTime: () -> Unit,
    onDuration: () -> Unit,
    /** Set for a workout that is already stored: its summary, with the muscles it trained and its photo. */
    onSummary: (() -> Unit)? = null,
) {
    val hairline = MaterialTheme.colorScheme.outlineVariant
    GainsCard(Modifier.fillMaxWidth(), contentPadding = Dp16.Tight) {
        ChooserRow(stringResource(Res.string.date), date?.let { dateLabel(it) } ?: "", onClick = onDate)
        HorizontalDivider(color = hairline)
        ChooserRow(stringResource(Res.string.time), time?.let { clock(it.hour, it.minute) } ?: "", onClick = onTime)
        HorizontalDivider(color = hairline)
        ChooserRow(stringResource(Res.string.duration), durationMinutes?.let { minutesText(it) } ?: stringResource(Res.string.not_timed), onClick = onDuration, muted = durationMinutes == null)
        if (onSummary != null) {
            HorizontalDivider(color = hairline)
            ChooserRow(stringResource(Res.string.summary), stringResource(Res.string.summary_row), onClick = onSummary, muted = true)
        }
    }
}

/**
 * The timer ran past three hours, which usually means it was left running. The measured time is
 * offered as the duration and can be turned to something else before the session is stored.
 */
@Composable
private fun LongSessionDialog(timedMinutes: Int, onConfirm: (Int) -> Unit, onCancel: () -> Unit) {
    var minutes by remember(timedMinutes) { mutableStateOf(timedMinutes) }
    AlertDialog(
        onDismissRequest = onCancel,
        shape = MaterialTheme.shapes.large,
        title = { Text(stringResource(Res.string.long_session)) },
        text = {
            Column {
                Text(stringResource(Res.string.long_session_body, minutesText(timedMinutes)))
                Spacer(Modifier.height(12.dp))
                // The dialog's own surface, so the wheel's ends fade into it.
                DurationWheels(minutes, onChange = { minutes = it }, fadeColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                Spacer(Modifier.height(8.dp))
                Text(
                    when {
                        minutes <= 0 -> stringResource(Res.string.turn_the_wheels)
                        minutes == timedMinutes -> stringResource(Res.string.kept_as_timed)
                        else -> stringResource(Res.string.stored_as, minutesText(minutes))
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { PrimaryButton(stringResource(Res.string.save_session), onClick = { onConfirm(minutes) }, enabled = minutes > 0) },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(Res.string.back_to_workout)) } },
    )
}

/**
 * Pinned above a timed workout that has not started: what is planned, and the one button that
 * starts the clock. It sits where the clock will, so the card simply changes over on Start.
 */
@Composable
private fun ReadyCard(exercises: List<ExerciseDraft>, onStart: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val workSets = exercises.sumOf { it.workSets.size }
    GainsCard(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 8.dp), contentPadding = Dp16.Tight) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(Res.string.ready), style = MaterialTheme.typography.labelSmall, color = muted)
                Text(
                    if (exercises.isEmpty()) stringResource(Res.string.nothing_planned_yet) else stringResource(Res.string.exercises_and_sets, exercisesText(exercises.size), setsText(workSets)),
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
            val startDescription = stringResource(Res.string.start_workout)
            Button(
                onClick = onStart, shape = CircleShape, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
                modifier = Modifier.semantics { contentDescription = startDescription },
            ) { Text(stringResource(Res.string.start), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold) }
        }
    }
}

/**
 * Pinned above the workout: total time since the session started and the rest left on the current
 * countdown, both against the wall clock so backgrounding and relaunches change nothing.
 */
@Composable
private fun SessionClock(startedAtMs: Long, rest: RestTimer?, onSkipRest: () -> Unit, onEnd: () -> Unit) {
    val palette = GainsColors.palette
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var now by remember { mutableStateOf(nowMs()) }
    LaunchedEffect(Unit) { while (true) { delay(500); now = nowMs() } }
    val remaining = rest?.remainingSeconds(now)
    GainsCard(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 8.dp), contentPadding = Dp16.Tight) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(Res.string.total), style = MaterialTheme.typography.labelSmall, color = muted)
                Text(Format.clock((now - startedAtMs) / 1000), style = MaterialTheme.typography.headlineSmall, color = palette.volt)
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(Res.string.rest), style = MaterialTheme.typography.labelSmall, color = muted)
                Text(
                    when {
                        remaining == null -> "–"
                        remaining > 0 -> Format.clock(remaining.toLong())
                        else -> stringResource(Res.string.done)
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    color = when { remaining == null -> muted; remaining > 0 -> palette.cyan; else -> palette.volt },
                )
            }
            if (remaining != null) {
                TextButton(onClick = onSkipRest) { Text(if (remaining > 0) stringResource(Res.string.skip) else stringResource(Res.string.ok), color = muted) }
            }
            Button(
                onClick = onEnd, shape = CircleShape, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
            ) { Text(stringResource(Res.string.end), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold) }
        }
    }
}

/**
 * Which program day the workout counts towards. Tagging a free workout with a day makes it part of
 * that day's progression; "None" turns a program workout into a free one.
 */
@Composable
private fun ProgramDayDialog(options: List<ProgramDayOption>, selected: ProgramDayRef?, onSelect: (ProgramDayRef?) -> Unit, onDismiss: () -> Unit) {
    val palette = GainsColors.palette
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.large,
        title = { Text(stringResource(Res.string.program_day)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(Res.string.program_day_note),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                DayChoice(stringResource(Res.string.none_free_workout), selected == null, palette.volt) { onSelect(null) }
                for ((programName, days) in options.groupBy { it.programName }) {
                    Spacer(Modifier.height(8.dp))
                    Text(programName.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    for (day in days) DayChoice(day.dayName, day.ref == selected, palette.volt) { onSelect(day.ref) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.close)) } },
    )
}

@Composable
private fun DayChoice(label: String, selected: Boolean, accent: Color, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = if (selected) accent else MaterialTheme.colorScheme.onSurface)
        if (selected) Icon(Icons.Default.Check, null, tint = accent, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun ExerciseCard(
    exerciseIndex: Int, draft: ExerciseDraft, unit: WeightUnit, model: SessionEditorModel, fieldColors: androidx.compose.material3.TextFieldColors,
    target: String? = null, hint: Progression.Hint? = null, programNote: String? = null, seeded: Boolean = false,
    source: Progression.Source? = null, tier: Gzclp.Tier? = null, warmupsCollapsed: Boolean = false,
    /** The exercise's last session, whose sets fill the PREV column row by row. */
    previous: ExerciseEntry? = null,
    /** False while a timed workout has not started: every control of the plan waits for Start, shown disabled. */
    editable: Boolean = true,
    /** How many exercises the workout has, so the menu knows whether the card can move up or down. */
    count: Int = exerciseIndex + 1,
    onPickWeight: (setIndex: Int) -> Unit,
    onChangeExercise: () -> Unit = {},
    onShowDemo: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val palette = GainsColors.palette
    val modality = draft.exercise.modality
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val warmups = draft.warmups
    val labels = draft.labels
    val previousLabels = draft.previousLabels(previous, unit, unitLabels())
    val name = draft.exercise.displayName()
    GainsCard(modifier.fillMaxWidth().padding(bottom = 10.dp), contentPadding = Dp16.Tight) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (target != null) Pill(target, palette.volt)
                    Pill(modality.label(), palette.cyan)
                    if (draft.exercise.isDumbbell) Pill(stringResource(Res.string.per_dumbbell), palette.amber)
                }
            }
            ExerciseMenu(
                name = name, enabled = editable,
                canMoveUp = exerciseIndex > 0, canMoveDown = exerciseIndex < count - 1,
                onDemo = onShowDemo,
                onChange = onChangeExercise,
                onMoveUp = { model.moveExercise(exerciseIndex, -1) },
                onMoveDown = { model.moveExercise(exerciseIndex, 1) },
                onRemove = { model.removeExercise(exerciseIndex) },
            )
        }
        if (hint != null) {
            Spacer(Modifier.height(4.dp))
            Text(hintText(hint, unit), style = MaterialTheme.typography.bodySmall, color = palette.volt)
        }
        if (seeded) {
            // The weights came from a session that never attempted this scheme: one tap declines them.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (source) {
                        Progression.Source.DIFFERENT_SCHEME -> stringResource(Res.string.weights_from_other_scheme)
                        else -> stringResource(Res.string.weights_from_free_sessions)
                    },
                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = muted,
                )
                TextButton(onClick = { model.startBlank(exerciseIndex) }, enabled = editable) { Text(stringResource(Res.string.start_blank), color = if (editable) palette.volt else disabledColor()) }
            }
        }
        if (programNote != null) {
            Spacer(Modifier.height(2.dp))
            Text(slotNoteText(programNote), style = MaterialTheme.typography.bodySmall, color = muted)
        }
        if (tier != null) {
            Text(
                restLineText(restRangeText(tier.restSeconds), if (warmups.isNotEmpty()) warmupRestText() else null),
                style = MaterialTheme.typography.bodySmall, color = muted,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().padding(bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(CELL_GAP), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.column_set), Modifier.width(LABEL_WIDTH), style = MaterialTheme.typography.labelSmall, color = muted)
            Header(stringResource(Res.string.column_prev), Modifier.width(PREVIOUS_WIDTH))
            when (modality) {
                Modality.WEIGHTED, Modality.BODYWEIGHT -> { Header(unit.label().uppercase()); Header(stringResource(Res.string.column_reps)) }
                Modality.ISOMETRIC -> { Header(stringResource(Res.string.column_seconds)); Header(unit.label().uppercase()) }
                Modality.CARDIO -> { Header(stringResource(Res.string.column_km)); Header(stringResource(Res.string.column_seconds)) }
            }
            // Room for the check and the remove control on each row, so the column heads sit over the cells.
            Spacer(Modifier.width(CHECK_HIT + REMOVE_HIT))
        }
        if (warmups.isNotEmpty()) {
            // Warm-ups are numbered W1, W2… and drawn muted, so the work sets stay 1–5 and read as the workout.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Pill(stringResource(Res.string.warm_up), muted)
                Spacer(Modifier.width(8.dp))
                Text(if (warmupsCollapsed) stringResource(Res.string.n_hidden, warmups.size) else setsText(warmups.size), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = muted)
                TextButton(onClick = { model.toggleWarmups(exerciseIndex) }) { Text(if (warmupsCollapsed) stringResource(Res.string.show) else stringResource(Res.string.hide), color = muted) }
                TextButton(onClick = { model.removeWarmups(exerciseIndex) }, enabled = editable) { Text(stringResource(Res.string.remove), color = if (editable) muted else disabledColor()) }
            }
        }
        for ((setIndex, set) in draft.sets.withIndex()) {
            val label = labels[setIndex]
            if (set.isWarmup && warmupsCollapsed) continue
            // A ticked row keeps its place in the table: the number and the check turn green and the cells take a tint,
            // so a glance shows how far the workout has got without the row turning into a card of its own.
            val done = set.done
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(CELL_GAP), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label, Modifier.width(LABEL_WIDTH),
                    style = if (set.isWarmup) MaterialTheme.typography.labelMedium else MaterialTheme.typography.titleSmall,
                    color = when { done -> palette.volt; set.isWarmup -> muted; else -> MaterialTheme.colorScheme.onSurface },
                )
                PreviousCell(previousLabels[setIndex], label)
                // The keyboard's action key moves from the first field to the second, then closes the keyboard.
                when (modality) {
                    Modality.WEIGHTED, Modality.BODYWEIGHT -> {
                        WeightCell(set.weight, done, set.isWarmup, editable, Modifier.weight(1f), label) { onPickWeight(setIndex) }
                        SetCell(set.reps, { model.updateSet(exerciseIndex, setIndex, set.copy(reps = it)) }, Modifier.weight(1f), done, set.isWarmup, editable, KeyboardType.Number)
                    }
                    Modality.ISOMETRIC -> {
                        SetCell(set.seconds, { model.updateSet(exerciseIndex, setIndex, set.copy(seconds = it)) }, Modifier.weight(1f), done, set.isWarmup, editable, KeyboardType.Number)
                        WeightCell(set.weight, done, set.isWarmup, editable, Modifier.weight(1f), label) { onPickWeight(setIndex) }
                    }
                    Modality.CARDIO -> {
                        SetCell(set.distanceKm, { model.updateSet(exerciseIndex, setIndex, set.copy(distanceKm = it)) }, Modifier.weight(1f), done, set.isWarmup, editable, KeyboardType.Decimal, ImeAction.Next)
                        SetCell(set.seconds, { model.updateSet(exerciseIndex, setIndex, set.copy(seconds = it)) }, Modifier.weight(1f), done, set.isWarmup, editable, KeyboardType.Number)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DoneCheck(
                        done = done, enabled = editable && (done || set.hasValues), label = label,
                        onClick = { model.toggleDone(exerciseIndex, setIndex) },
                    )
                    RemoveSetButton(label, editable) { model.removeSet(exerciseIndex, setIndex) }
                }
            }
        }
        TextButton(onClick = { model.addSet(exerciseIndex) }, enabled = editable) { Text(stringResource(Res.string.plus_add_set), color = if (editable) palette.volt else disabledColor()) }
        OutlinedTextField(
            draft.note, { model.setNote(exerciseIndex, it) }, placeholder = { Text(stringResource(Res.string.note)) }, singleLine = true, enabled = editable,
            modifier = Modifier.fillMaxWidth(), colors = fieldColors, shape = MaterialTheme.shapes.medium,
        )
    }
}

/**
 * The ⋯ at the top right of an exercise card, Hevy-style: everything that changes the card rather than
 * a set. "Change exercise" swaps the lift and keeps every set typed so far, the arrows move the card
 * through the workout, and Remove drops it. "How to do it" opens the exercise's demo. While the plan
 * waits for Start the menu still opens, for the demo, but everything that edits the card is inert.
 */
@Composable
private fun ExerciseMenu(
    name: String, enabled: Boolean, canMoveUp: Boolean, canMoveDown: Boolean,
    onDemo: () -> Unit, onChange: () -> Unit, onMoveUp: () -> Unit, onMoveDown: () -> Unit, onRemove: () -> Unit,
) {
    val palette = GainsColors.palette
    var open by remember { mutableStateOf(false) }
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    val optionsDescription = stringResource(Res.string.options_for, name)
    Box {
        Box(
            Modifier.size(CHECK_HIT, CELL_HEIGHT).clip(CellShape).clickable { open = true }
                .semantics { role = Role.Button; contentDescription = optionsDescription },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.MoreVert, null, tint = tint, modifier = Modifier.size(20.dp)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = MaterialTheme.shapes.medium) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.how_to_do_it)) },
                leadingIcon = { Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp)) },
                onClick = { open = false; onDemo() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.change_exercise)) }, enabled = enabled,
                leadingIcon = { Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp)) },
                onClick = { open = false; onChange() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.move_up)) }, enabled = enabled && canMoveUp,
                leadingIcon = { Icon(Icons.Default.KeyboardArrowUp, null, modifier = Modifier.size(18.dp)) },
                onClick = { open = false; onMoveUp() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.move_down)) }, enabled = enabled && canMoveDown,
                leadingIcon = { Icon(Icons.Default.KeyboardArrowDown, null, modifier = Modifier.size(18.dp)) },
                onClick = { open = false; onMoveDown() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.remove), color = if (enabled) palette.coral else disabledColor()) }, enabled = enabled,
                leadingIcon = { Icon(Icons.Default.Delete, null, tint = if (enabled) palette.coral else disabledColor(), modifier = Modifier.size(18.dp)) },
                onClick = { open = false; onRemove() },
            )
        }
    }
}

/* The set table: a 28 dp number column, a 56 dp "last time" column, two equal cells, then a check and a remove control of fixed width. */
private val LABEL_WIDTH = 28.dp
private val PREVIOUS_WIDTH = 56.dp
private val CELL_GAP = 8.dp
private val CELL_HEIGHT = 44.dp
private val CellShape = RoundedCornerShape(12.dp)
/** Tap targets of the two controls at the end of a row; what is drawn inside is smaller. */
private val CHECK_HIT = 36.dp
private val REMOVE_HIT = 32.dp
private val CHECK_SIZE = 28.dp

/** How far a control fades while the plan waits for Start: Material's disabled content alpha. */
private const val DISABLED_ALPHA = 0.38f

/** The colour of a disabled control's label or icon, in place of its accent. */
@Composable
private fun disabledColor(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)

/** The fill of a set cell: a quiet container, tinted with the accent once the set is ticked, and faded while disabled. */
@Composable
private fun cellFill(done: Boolean, enabled: Boolean = true): Color {
    val fill = if (done) GainsColors.palette.volt.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainerHighest
    return if (enabled) fill else fill.copy(alpha = fill.alpha * DISABLED_ALPHA)
}

/** The text colour of a set cell: muted for a warm-up, and faded while disabled. */
@Composable
private fun cellText(muted: Boolean, enabled: Boolean): Color {
    val color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    return if (enabled) color else color.copy(alpha = DISABLED_ALPHA)
}

/**
 * One typed cell of a set row: reps, seconds or distance. A compact filled box rather than an
 * outlined text field, so five rows fit a screen and the columns line up with their headings.
 * Disabled, it shows its value faded and takes no focus.
 */
@Composable
private fun SetCell(
    value: String, onChange: (String) -> Unit, modifier: Modifier, done: Boolean, muted: Boolean, enabled: Boolean,
    keyboardType: KeyboardType, imeAction: ImeAction = ImeAction.Done,
) {
    val palette = GainsColors.palette
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    BasicTextField(
        value, onChange, modifier = modifier, enabled = enabled, singleLine = true, interactionSource = interaction,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        textStyle = MaterialTheme.typography.titleMedium.copy(color = cellText(muted, enabled), textAlign = TextAlign.Center),
        cursorBrush = SolidColor(palette.volt),
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().height(CELL_HEIGHT).clip(CellShape).background(cellFill(done, enabled))
                    .border(1.5.dp, if (focused) palette.volt else Color.Transparent, CellShape)
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                // The dash stands in for an empty cell until the caret takes its place.
                if (value.isEmpty() && !focused) CellPlaceholder(enabled)
                inner()
            }
        },
    )
}

/** The weight cell: the same box as a typed cell, but tapping it opens the weight chooser. Disabled, it is faded and inert. */
@Composable
private fun WeightCell(value: String, done: Boolean, muted: Boolean, enabled: Boolean, modifier: Modifier, setLabel: String, onClick: () -> Unit) {
    val description = weightForSetText(setLabel, value.ifEmpty { null })
    Box(
        modifier.height(CELL_HEIGHT).clip(CellShape).background(cellFill(done, enabled)).clickable(enabled = enabled, onClick = onClick)
            .semantics { role = Role.Button; contentDescription = description }
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (value.isEmpty()) CellPlaceholder(enabled)
        else Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1, color = cellText(muted, enabled))
    }
}

/**
 * What the same set was last session, read-only and muted so the typed cells stay the focus:
 * "60×5", or the dash when last time had no such set or the exercise is new.
 */
@Composable
private fun PreviousCell(label: String?, setLabel: String) {
    val description = previousSetText(setLabel, label)
    Box(
        Modifier.width(PREVIOUS_WIDTH).height(CELL_HEIGHT).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (label == null) CellPlaceholder()
        else Text(
            label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun CellPlaceholder(enabled: Boolean = true) {
    Text("–", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 0.5f else 0.5f * DISABLED_ALPHA))
}

/**
 * The tick at the end of a set row, Liftoff-style: an outlined circle until the set is done, then a
 * filled one with a check. Tapping toggles it either way; it is dimmed while the row is empty or the
 * workout has not started.
 */
@Composable
private fun DoneCheck(done: Boolean, enabled: Boolean, label: String, onClick: () -> Unit) {
    val palette = GainsColors.palette
    val outline = MaterialTheme.colorScheme.outline
    val description = setDoneText(label, done)
    Box(
        Modifier.size(CHECK_HIT, CELL_HEIGHT).clip(CellShape).clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(CHECK_SIZE).clip(CircleShape)
                .background(if (done) palette.volt else Color.Transparent)
                .border(1.5.dp, if (done) palette.volt else outline.copy(alpha = if (enabled) 1f else DISABLED_ALPHA), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (done) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
        }
    }
}

/** A quiet × that removes the row, the same height as the check so the two sit on one line; faded and inert while disabled. */
@Composable
private fun RemoveSetButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val description = stringResource(Res.string.remove_set, label)
    Box(
        Modifier.size(REMOVE_HIT, CELL_HEIGHT).clip(CellShape).clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.Close, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 0.7f else DISABLED_ALPHA), modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Header(text: String, modifier: Modifier = Modifier.weight(1f)) {
    Text(text, modifier, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
}
