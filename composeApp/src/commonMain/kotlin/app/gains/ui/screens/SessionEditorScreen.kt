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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.unit.dp
import app.gains.analysis.Dates
import app.gains.analysis.Format
import app.gains.analysis.TrainingData
import app.gains.analysis.TrainingSnapshot
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

data class ExerciseDraft(val exercise: Exercise, val sets: List<SetDraft>, val note: String = "") {
    val warmups: List<SetDraft> get() = sets.filter { it.isWarmup }
    val workSets: List<SetDraft> get() = sets.filter { !it.isWarmup }

    /** "W1, W2…" for the warm-ups and "1, 2…" for the work sets, in row order. */
    val labels: List<String> get() {
        var warmup = 0
        var work = 0
        return sets.map { if (it.isWarmup) "W${++warmup}" else (++work).toString() }
    }
}

/** A program day the workout can be tagged with: "GZCLP · A1". */
data class ProgramDayOption(val ref: ProgramDayRef, val programName: String, val dayName: String) {
    val label: String get() = "$programName · $dayName"
}

data class EditorState(
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
    val error: String? = null,
    val saved: Boolean = false,
    /**
     * The program day this workout counts towards. Set when started from a program day, and editable:
     * a free workout tagged with a day drives that day's progression like one started from it.
     */
    val programDay: ProgramDayRef? = null,
    val title: String = "Log workout",
    /** Every day of every program, for the tag picker. */
    val programDays: List<ProgramDayOption> = emptyList(),
    /** exercise id -> "5 × 3+" */
    val targets: Map<String, String> = emptyMap(),
    /** exercise id -> "Last: 60 kg × 5,5,5 → try 62.5 kg" */
    val hints: Map<String, String> = emptyMap(),
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

class SessionEditorModel(
    private val sessionId: String?,
    private val programDay: ProgramDayRef? = null,
    /** Open a timed workout, ready to start (or resume the one running), rather than log a past one. */
    private val live: Boolean = false,
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
    private class Context(val snapshot: TrainingSnapshot, val unit: WeightUnit, val planOptions: PlanOptions, val programs: List<Program>, val dayOptions: List<ProgramDayOption>)
    private var context: Context? = null
    private var persistJob: Job? = null
    /** Set once the workout was stored or discarded, so nothing writes it back afterwards. */
    private var finished = false
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
            val ctx = Context(snapshot, unit, planOptions, programList, programList.flatMap { p -> p.days.map { ProgramDayOption(ProgramDayRef(p.id, it.id), p.name, it.name) } })
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
                    programDay = existing.program, title = "Edit workout", programDays = ctx.dayOptions,
                )
                // Coming back to the running workout, whether from the resume bar, a relaunch or the same day's Start.
                stored != null && (programDay == null || stored.program == programDay) -> restored(ctx, stored)
                // Any other day opens ready to start; a different workout still running is asked about at Start.
                programDay != null -> planned(ctx, programDay).copy(timed = live)
                live -> fresh(ctx).copy(title = "Workout", timed = true)
                else -> fresh(ctx)
            }
            if (live) persistWhileRunning()
        }
    }

    private fun fresh(ctx: Context): EditorState {
        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        return EditorState(
            loading = false, isNew = true, date = now.date, time = LocalTime(now.hour, now.minute),
            unit = ctx.unit, catalogue = ctx.snapshot.exercises.sortedBy { it.name }, recent = ctx.snapshot.trainedExercises.take(12),
            programDays = ctx.dayOptions,
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
            programDay = ref, title = day.name,
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

    private fun update(f: (EditorState) -> EditorState) { _state.value = f(_state.value) }
    fun setDate(v: LocalDate) = update { it.copy(date = v) }
    fun setTime(v: LocalTime) = update { it.copy(time = v) }
    fun setDuration(v: Int?) = update { it.copy(durationMinutes = v?.takeIf { m -> m > 0 }) }

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
            else update { it.copy(startedAtMs = nowMs(), error = null) }
        }
    }

    fun addExercise(exercise: Exercise) = update { s ->
        if (s.exercises.any { it.exercise.id == exercise.id }) s
        else s.copy(exercises = s.exercises + ExerciseDraft(exercise, listOf(SetDraft())))
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

    fun removeExercise(index: Int) = update { it.copy(exercises = it.exercises.filterIndexed { i, _ -> i != index }) }

    /** Tag the workout with a program day, or none for a free workout. */
    fun setProgramDay(ref: ProgramDayRef?) = update { it.copy(programDay = ref) }

    /** Drop the weights borrowed from outside the slot's scheme; sets and reps stay as prescribed. Warm-ups built on those weights go too. */
    fun startBlank(index: Int) = update { s ->
        s.copy(
            exercises = s.exercises.mapIndexed { i, e -> if (i != index) e else e.copy(sets = e.workSets.map { it.copy(weight = "") }) },
            seeded = s.seeded - s.exercises[index].exercise.id,
        )
    }

    fun toggleWarmups(index: Int) = update { s ->
        val id = s.exercises[index].exercise.id
        s.copy(collapsedWarmups = if (id in s.collapsedWarmups) s.collapsedWarmups - id else s.collapsedWarmups + id)
    }

    fun removeWarmups(index: Int) = update { s ->
        s.copy(exercises = s.exercises.mapIndexed { i, e -> if (i != index) e else e.copy(sets = e.workSets) })
    }

    /**
     * Tick a set off, or un-tick it. Ticking starts the rest timer at the tier's short end (warm-ups get
     * the warm-up rest, exercises with no tier a default); un-ticking leaves the timer alone. An empty
     * set cannot be ticked: there is nothing to record. A timed workout ticks only once it has started.
     */
    fun toggleDone(exerciseIndex: Int, setIndex: Int) = update { s ->
        if (s.timed && !s.isRunning) return@update s
        val exercise = s.exercises[exerciseIndex]
        val set = exercise.sets[setIndex]
        if (!set.done && !set.hasValues) return@update s
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
    fun setNote(index: Int, note: String) = update { it.copy(exercises = it.exercises.mapIndexed { i, e -> if (i == index) e.copy(note = note) else e }) }

    /** Adds a work set like the last one (never a copy of a warm-up). */
    fun addSet(index: Int) = update { s ->
        s.copy(exercises = s.exercises.mapIndexed { i, e ->
            if (i != index) e else e.copy(sets = e.sets + (e.workSets.lastOrNull()?.copy(done = false) ?: SetDraft()))
        })
    }

    /** Clearing every field of a ticked set un-ticks it: an empty set cannot count as done. */
    fun updateSet(exerciseIndex: Int, setIndex: Int, draft: SetDraft) = update { s ->
        val next = if (draft.done && !draft.hasValues) draft.copy(done = false) else draft
        s.copy(exercises = s.exercises.mapIndexed { i, e ->
            if (i != exerciseIndex) e else e.copy(sets = e.sets.mapIndexed { j, d -> if (j == setIndex) next else d })
        })
    }

    fun removeSet(exerciseIndex: Int, setIndex: Int) = update { s ->
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
        if (entries.isEmpty()) { update { it.copy(error = "Add at least one exercise with a set.") }; return null }
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
        )
        scope.launch {
            // Ids are minute-precision timestamps; two workouts saved in the same minute must not replace each other.
            val id = s.id ?: uniqueId(timestamp.toString(), sessions.ids())
            sessions.upsert(session.copy(id = id))
            update { it.copy(saved = true, error = null) }
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
        if (LiveSession.isLong(nowMs() - started)) update { it.copy(longSessionMinutes = minutes, error = null) }
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
            }
            update { it.copy(saved = true, error = null) }
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

@Composable
fun SessionEditorScreen(sessionId: String?, programDay: ProgramDayRef? = null, live: Boolean = false, onDone: () -> Unit) {
    val model = rememberScreenModel(sessionId, programDay, live) { SessionEditorModel(sessionId, programDay, live) }
    val state by model.state.collectAsState()
    val palette = GainsColors.palette
    var pickerOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var dayPickerOpen by remember { mutableStateOf(false) }
    var datePickerOpen by remember { mutableStateOf(false) }
    var timePickerOpen by remember { mutableStateOf(false) }
    var durationPickerOpen by remember { mutableStateOf(false) }
    /** The set whose weight chooser is open: exercise index to set index. */
    var weightTarget by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    if (state.loading) return
    if (state.saved) { onDone(); return }
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
                            Pill(tag?.label ?: "Not part of a program", if (tag != null) palette.volt else MaterialTheme.colorScheme.onSurfaceVariant, onClick = { dayPickerOpen = true })
                        }
                    }
                    if (!state.isNew) TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = palette.coral) }
                }
                Spacer(Modifier.height(12.dp))
                when {
                    startedAt != null -> {
                        val started = Instant.fromEpochMilliseconds(startedAt).toLocalDateTime(TimeZone.currentSystemDefault())
                        Text(
                            "Started ${clock(started.hour, started.minute)}. Tick a set off when it is done to start the rest timer.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    state.timed -> Text(
                        "Look over the sets and change anything, then press Start. The clock runs from then, and ticking a set off starts the rest timer.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> WhenCard(
                        state.date, state.time, state.durationMinutes,
                        onDate = { datePickerOpen = true }, onTime = { timePickerOpen = true }, onDuration = { durationPickerOpen = true },
                    )
                }
                SectionHeader("Exercises", action = { TextButton(onClick = { pickerOpen = true }) { Text("+ Add exercise", color = palette.volt) } })
                if (state.exercises.isEmpty()) {
                    Text("Add an exercise to start logging sets. Weights are in ${state.unit.label}; leave weight empty for bodyweight, use seconds for holds and km for cardio. Tick a set off when it's done to start the rest timer.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    canTick = !state.timed || state.isRunning,
                    onPickWeight = { setIndex -> weightTarget = exerciseIndex to setIndex },
                )
            }
            item {
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp)) }
                Spacer(Modifier.height(12.dp))
                when {
                    startedAt != null -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SecondaryButton("Discard", { confirmDiscard = true }, Modifier.weight(1f))
                        PrimaryButton("End session", { model.endSession() }, Modifier.weight(1f))
                    }
                    // Not started: Start stays pinned above the list, so nothing is needed down here.
                    state.timed -> {}
                    else -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SecondaryButton("Cancel", onDone, Modifier.weight(1f))
                        PrimaryButton(if (state.isNew) "Save workout" else "Save changes", { model.save() }, Modifier.weight(1f))
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
            value = set.weight, unit = state.unit, title = draft.exercise.name,
            subtitle = (if (set.isWarmup) "Warm-up " else "Set ") + draft.labels[setIndex].trimStart('W') + if (draft.exercise.isDumbbell) " · per dumbbell" else "",
            onPick = { model.updateSet(exerciseIndex, setIndex, set.copy(weight = it)) },
            onDismiss = { weightTarget = null },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            shape = MaterialTheme.shapes.large,
            title = { Text("Delete this workout?") },
            text = { Text("It will be removed from history and every analysis.") },
            confirmButton = { PrimaryButton("Delete", onClick = { model.delete(); confirmDelete = false }) },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            shape = MaterialTheme.shapes.large,
            title = { Text("Discard this workout?") },
            text = { Text("Nothing from it will be saved. Leaving with Back keeps it running instead.") },
            confirmButton = { PrimaryButton("Discard", onClick = { model.discardLive(); confirmDiscard = false }) },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep going") } },
        )
    }
    state.untickedOnSave?.let { count ->
        // Liftoff drops unfinished sets on finish; here the lifter chooses, since a set may have been done and just not ticked.
        AlertDialog(
            onDismissRequest = model::dismissSavePrompt,
            shape = MaterialTheme.shapes.large,
            title = { Text(if (count == 1) "1 set isn't ticked off" else "$count sets aren't ticked off") },
            text = { Text("Leave ${if (count == 1) "it" else "them"} out of the workout, or save ${if (count == 1) "it" else "them"} as done too?") },
            confirmButton = { PrimaryButton("Leave out", onClick = { model.save(includeUnticked = false) }) },
            dismissButton = { TextButton(onClick = { model.save(includeUnticked = true) }) { Text("Save all", color = palette.volt) } },
        )
    }
    state.conflict?.let { running ->
        val elapsed = LiveSession.durationMinutes(running.elapsedMs(nowMs()))
        AlertDialog(
            onDismissRequest = model::dismissConflict,
            shape = MaterialTheme.shapes.large,
            title = { Text("Workout in progress") },
            text = { Text("${running.title} was started ${Format.minutes(elapsed)} ago and has not been ended. Resume it, or discard it and start ${state.title}?") },
            confirmButton = { PrimaryButton("Resume ${running.title}", onClick = model::resumeStored) },
            dismissButton = { TextButton(onClick = model::discardStoredAndStart) { Text("Discard and start ${state.title}", color = palette.coral) } },
        )
    }
    state.longSessionMinutes?.let { timed -> LongSessionDialog(timed, onConfirm = model::confirmEnd, onCancel = model::cancelEnd) }
}

/** "18:05" */
private fun clock(hour: Int, minute: Int) = "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"

/** "Today", "Yesterday", otherwise "Wed 17 Sep", with the year once it is not this one. */
private fun dateLabel(date: LocalDate): String {
    val today = Dates.today()
    return when (Dates.daysBetween(date, today)) {
        0 -> "Today"
        1 -> "Yesterday"
        else -> "${Dates.dayLabel(date.dayOfWeek)} ${Dates.contextual(date, today)}"
    }
}

/**
 * When a logged workout was and how long it took, as three rows that open a chooser each: a
 * calendar for the date, wheels for the time and the duration. Nothing is typed.
 */
@Composable
private fun WhenCard(date: LocalDate?, time: LocalTime?, durationMinutes: Int?, onDate: () -> Unit, onTime: () -> Unit, onDuration: () -> Unit) {
    val hairline = MaterialTheme.colorScheme.outlineVariant
    GainsCard(Modifier.fillMaxWidth(), contentPadding = Dp16.Tight) {
        ChooserRow("Date", date?.let(::dateLabel) ?: "", onClick = onDate)
        HorizontalDivider(color = hairline)
        ChooserRow("Time", time?.let { clock(it.hour, it.minute) } ?: "", onClick = onTime)
        HorizontalDivider(color = hairline)
        ChooserRow("Duration", durationMinutes?.let(Format::minutes) ?: "Not timed", onClick = onDuration, muted = durationMinutes == null)
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
        title = { Text("Long session") },
        text = {
            Column {
                Text("The timer ran for ${Format.minutes(timedMinutes)}. Was that how long you trained? Change the duration below if not.")
                Spacer(Modifier.height(12.dp))
                // The dialog's own surface, so the wheel's ends fade into it.
                DurationWheels(minutes, onChange = { minutes = it }, fadeColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                Spacer(Modifier.height(8.dp))
                Text(
                    when {
                        minutes <= 0 -> "Turn the wheels to the time you trained."
                        minutes == timedMinutes -> "Kept as timed."
                        else -> "Stored as ${Format.minutes(minutes)}."
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { PrimaryButton("Save session", onClick = { onConfirm(minutes) }, enabled = minutes > 0) },
        dismissButton = { TextButton(onClick = onCancel) { Text("Back to workout") } },
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
                Text("READY", style = MaterialTheme.typography.labelSmall, color = muted)
                Text(
                    if (exercises.isEmpty()) "Nothing planned yet" else "${Format.plural(exercises.size, "exercise")} · ${Format.plural(workSets, "set")}",
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
            Button(
                onClick = onStart, shape = CircleShape, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
                modifier = Modifier.semantics { contentDescription = "Start workout" },
            ) { Text("Start", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold) }
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
                Text("TOTAL", style = MaterialTheme.typography.labelSmall, color = muted)
                Text(Format.clock((now - startedAtMs) / 1000), style = MaterialTheme.typography.headlineSmall, color = palette.volt)
            }
            Column(Modifier.weight(1f)) {
                Text("REST", style = MaterialTheme.typography.labelSmall, color = muted)
                Text(
                    when {
                        remaining == null -> "–"
                        remaining > 0 -> Format.clock(remaining.toLong())
                        else -> "Done"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    color = when { remaining == null -> muted; remaining > 0 -> palette.cyan; else -> palette.volt },
                )
            }
            if (remaining != null) {
                TextButton(onClick = onSkipRest) { Text(if (remaining > 0) "Skip" else "OK", color = muted) }
            }
            Button(
                onClick = onEnd, shape = CircleShape, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
            ) { Text("End", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold) }
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
        title = { Text("Program day") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "A workout tagged with a day counts towards that day's progression. Leave it untagged for free training.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                DayChoice("None (free workout)", selected == null, palette.volt) { onSelect(null) }
                for ((programName, days) in options.groupBy { it.programName }) {
                    Spacer(Modifier.height(8.dp))
                    Text(programName.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    for (day in days) DayChoice(day.dayName, day.ref == selected, palette.volt) { onSelect(day.ref) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
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
    target: String? = null, hint: String? = null, programNote: String? = null, seeded: Boolean = false,
    source: Progression.Source? = null, tier: Gzclp.Tier? = null, warmupsCollapsed: Boolean = false,
    /** False while a timed workout has not started: the checks wait for Start. */
    canTick: Boolean = true,
    onPickWeight: (setIndex: Int) -> Unit,
) {
    val palette = GainsColors.palette
    val modality = draft.exercise.modality
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val warmups = draft.warmups
    val labels = draft.labels
    GainsCard(Modifier.fillMaxWidth().padding(bottom = 10.dp), contentPadding = Dp16.Tight) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(draft.exercise.name, style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (target != null) Pill(target, palette.volt)
                    Pill(modality.name.lowercase().replaceFirstChar { it.uppercase() }, palette.cyan)
                    if (draft.exercise.isDumbbell) Pill("Per dumbbell", palette.amber)
                }
            }
            TextButton(onClick = { model.removeExercise(exerciseIndex) }) { Text("Remove", color = palette.coral) }
        }
        if (hint != null) {
            Spacer(Modifier.height(4.dp))
            Text(hint, style = MaterialTheme.typography.bodySmall, color = palette.volt)
        }
        if (seeded) {
            // The weights came from a session that never attempted this scheme: one tap declines them.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (source) {
                        Progression.Source.DIFFERENT_SCHEME -> "Weights estimated from another scheme of this program."
                        else -> "Weights estimated from your free sessions."
                    },
                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = muted,
                )
                TextButton(onClick = { model.startBlank(exerciseIndex) }) { Text("Start blank", color = palette.volt) }
            }
        }
        if (programNote != null) {
            Spacer(Modifier.height(2.dp))
            Text(programNote, style = MaterialTheme.typography.bodySmall, color = muted)
        }
        if (tier != null) {
            Text(
                "Rest ${tier.restLabel} between sets" + (if (warmups.isNotEmpty()) " · warm-ups ${Gzclp.WARMUP_REST_LABEL}" else "") + ".",
                style = MaterialTheme.typography.bodySmall, color = muted,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().padding(bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(CELL_GAP), verticalAlignment = Alignment.CenterVertically) {
            Text("SET", Modifier.width(LABEL_WIDTH), style = MaterialTheme.typography.labelSmall, color = muted)
            when (modality) {
                Modality.WEIGHTED, Modality.BODYWEIGHT -> { Header(unit.label.uppercase()); Header("REPS") }
                Modality.ISOMETRIC -> { Header("SECONDS"); Header(unit.label.uppercase()) }
                Modality.CARDIO -> { Header("KM"); Header("SECONDS") }
            }
            // Room for the check and the remove control on each row, so the column heads sit over the cells.
            Spacer(Modifier.width(CHECK_HIT + REMOVE_HIT))
        }
        if (warmups.isNotEmpty()) {
            // Warm-ups are numbered W1, W2… and drawn muted, so the work sets stay 1–5 and read as the workout.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Pill("Warm-up", muted)
                Spacer(Modifier.width(8.dp))
                Text(if (warmupsCollapsed) "${warmups.size} hidden" else "${warmups.size} sets", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = muted)
                TextButton(onClick = { model.toggleWarmups(exerciseIndex) }) { Text(if (warmupsCollapsed) "Show" else "Hide", color = muted) }
                TextButton(onClick = { model.removeWarmups(exerciseIndex) }) { Text("Remove", color = muted) }
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
                // The keyboard's action key moves from the first field to the second, then closes the keyboard.
                when (modality) {
                    Modality.WEIGHTED, Modality.BODYWEIGHT -> {
                        WeightCell(set.weight, done, set.isWarmup, Modifier.weight(1f), label) { onPickWeight(setIndex) }
                        SetCell(set.reps, { model.updateSet(exerciseIndex, setIndex, set.copy(reps = it)) }, Modifier.weight(1f), done, set.isWarmup, KeyboardType.Number)
                    }
                    Modality.ISOMETRIC -> {
                        SetCell(set.seconds, { model.updateSet(exerciseIndex, setIndex, set.copy(seconds = it)) }, Modifier.weight(1f), done, set.isWarmup, KeyboardType.Number)
                        WeightCell(set.weight, done, set.isWarmup, Modifier.weight(1f), label) { onPickWeight(setIndex) }
                    }
                    Modality.CARDIO -> {
                        SetCell(set.distanceKm, { model.updateSet(exerciseIndex, setIndex, set.copy(distanceKm = it)) }, Modifier.weight(1f), done, set.isWarmup, KeyboardType.Decimal, ImeAction.Next)
                        SetCell(set.seconds, { model.updateSet(exerciseIndex, setIndex, set.copy(seconds = it)) }, Modifier.weight(1f), done, set.isWarmup, KeyboardType.Number)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DoneCheck(
                        done = done, enabled = canTick && (done || set.hasValues), label = "Set $label",
                        onClick = { model.toggleDone(exerciseIndex, setIndex) },
                    )
                    RemoveSetButton(label) { model.removeSet(exerciseIndex, setIndex) }
                }
            }
        }
        TextButton(onClick = { model.addSet(exerciseIndex) }) { Text("+ Add set", color = palette.volt) }
        OutlinedTextField(draft.note, { model.setNote(exerciseIndex, it) }, placeholder = { Text("Note") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = fieldColors, shape = MaterialTheme.shapes.medium)
    }
}

/* The set table: a 28 dp number column, two equal cells, then a check and a remove control of fixed width. */
private val LABEL_WIDTH = 28.dp
private val CELL_GAP = 8.dp
private val CELL_HEIGHT = 44.dp
private val CellShape = RoundedCornerShape(12.dp)
/** Tap targets of the two controls at the end of a row; what is drawn inside is smaller. */
private val CHECK_HIT = 36.dp
private val REMOVE_HIT = 32.dp
private val CHECK_SIZE = 28.dp

/** The fill of a set cell: a quiet container, tinted with the accent once the set is ticked. */
@Composable
private fun cellFill(done: Boolean): Color =
    if (done) GainsColors.palette.volt.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainerHighest

/**
 * One typed cell of a set row: reps, seconds or distance. A compact filled box rather than an
 * outlined text field, so five rows fit a screen and the columns line up with their headings.
 */
@Composable
private fun SetCell(
    value: String, onChange: (String) -> Unit, modifier: Modifier, done: Boolean, muted: Boolean,
    keyboardType: KeyboardType, imeAction: ImeAction = ImeAction.Done,
) {
    val palette = GainsColors.palette
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val textColor = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    BasicTextField(
        value, onChange, modifier = modifier, singleLine = true, interactionSource = interaction,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        textStyle = MaterialTheme.typography.titleMedium.copy(color = textColor, textAlign = TextAlign.Center),
        cursorBrush = SolidColor(palette.volt),
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().height(CELL_HEIGHT).clip(CellShape).background(cellFill(done))
                    .border(1.5.dp, if (focused) palette.volt else Color.Transparent, CellShape)
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                // The dash stands in for an empty cell until the caret takes its place.
                if (value.isEmpty() && !focused) CellPlaceholder()
                inner()
            }
        },
    )
}

/** The weight cell: the same box as a typed cell, but tapping it opens the weight chooser. */
@Composable
private fun WeightCell(value: String, done: Boolean, muted: Boolean, modifier: Modifier, setLabel: String, onClick: () -> Unit) {
    val description = if (value.isEmpty()) "Weight for set $setLabel, none" else "Weight for set $setLabel, $value"
    Box(
        modifier.height(CELL_HEIGHT).clip(CellShape).background(cellFill(done)).clickable(onClick = onClick)
            .semantics { role = Role.Button; contentDescription = description }
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (value.isEmpty()) CellPlaceholder()
        else Text(
            value, style = MaterialTheme.typography.titleMedium, maxLines = 1,
            color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun CellPlaceholder() {
    Text("–", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
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
    val description = if (done) "$label done" else "$label not done"
    Box(
        Modifier.size(CHECK_HIT, CELL_HEIGHT).clip(CellShape).clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(CHECK_SIZE).clip(CircleShape)
                .background(if (done) palette.volt else Color.Transparent)
                .border(1.5.dp, if (done) palette.volt else outline.copy(alpha = if (enabled) 1f else 0.35f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (done) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
        }
    }
}

/** A quiet × that removes the row, the same height as the check so the two sit on one line. */
@Composable
private fun RemoveSetButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(REMOVE_HIT, CELL_HEIGHT).clip(CellShape).clickable(onClick = onClick)
            .semantics { contentDescription = "Remove set $label" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.Close, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Header(text: String) {
    Text(text, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
}
