package app.gains.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
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
import app.gains.ui.components.Dp16
import app.gains.ui.components.GainsCard
import app.gains.ui.components.Pill
import app.gains.ui.components.PrimaryButton
import app.gains.ui.components.SecondaryButton
import app.gains.ui.components.SectionHeader
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
}

/** A program day the workout can be tagged with: "GZCLP · A1". */
data class ProgramDayOption(val ref: ProgramDayRef, val programName: String, val dayName: String) {
    val label: String get() = "$programName · $dayName"
}

data class EditorState(
    val loading: Boolean = true,
    val isNew: Boolean = true,
    val id: String? = null,
    val date: String = "",
    val time: String = "",
    val durationMinutes: String = "",
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
    /**
     * Set while a timed workout is running: when its clock started. The total time counts from it,
     * it becomes the session's timestamp, and the editor is stored to survive the app being killed.
     */
    val startedAtMs: Long? = null,
    /** A different workout is already in progress; the lifter picks which one to keep. */
    val conflict: LiveSession? = null,
    /** The timer ran past three hours: the minutes it measured, awaiting confirmation before the session is stored. */
    val longSessionMinutes: Int? = null,
) {
    val programDayOption: ProgramDayOption? get() = programDay?.let { ref -> programDays.firstOrNull { it.ref == ref } }
    val isLive: Boolean get() = startedAtMs != null

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
    /** Start (or resume) a timed workout rather than log a past one. */
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
                    loading = false, isNew = false, id = existing.id, date = existing.date.toString(),
                    time = "${existing.timestamp.hour.toString().padStart(2, '0')}:${existing.timestamp.minute.toString().padStart(2, '0')}",
                    durationMinutes = existing.durationMinutes?.toString() ?: "",
                    exercises = existing.exercises.mapNotNull { entry ->
                        snapshot.exercisesById[entry.exerciseId]?.let { ex -> ExerciseDraft(ex, entry.sets.map { SetDraft.from(it, unit) }, entry.note ?: "") }
                    },
                    unit = unit, catalogue = snapshot.exercises.sortedBy { it.name }, recent = snapshot.trainedExercises.take(12),
                    programDay = existing.program, title = "Edit workout", programDays = ctx.dayOptions,
                )
                // Coming back to the running workout, whether from the resume bar, a relaunch or the same day's Start.
                stored != null && (programDay == null || stored.program == programDay) -> restored(ctx, stored)
                // Another day was started while one is running: ask before either is lost.
                stored != null -> planned(ctx, programDay).copy(conflict = stored)
                programDay != null -> planned(ctx, programDay).let { if (live) it.copy(startedAtMs = nowMs()) else it }
                live -> fresh(ctx).copy(title = "Workout", startedAtMs = nowMs())
                else -> fresh(ctx)
            }
            if (live) persistWhileRunning()
        }
    }

    private fun fresh(ctx: Context): EditorState {
        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        return EditorState(
            loading = false, isNew = true, date = now.date.toString(),
            time = "${now.hour.toString().padStart(2, '0')}:${now.minute.toString().padStart(2, '0')}",
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
        programDay = stored.program, title = stored.title, startedAtMs = stored.startedAtMs, restTimer = stored.rest,
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
    fun setDate(v: String) = update { it.copy(date = v) }
    fun setTime(v: String) = update { it.copy(time = v) }
    fun setDuration(v: String) = update { it.copy(durationMinutes = v) }

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
     * Tick a set off. Ticking starts the rest timer at the tier's short end (warm-ups get the warm-up
     * rest, exercises with no tier a default); un-ticking leaves the timer alone.
     */
    fun toggleDone(exerciseIndex: Int, setIndex: Int) = update { s ->
        val exercise = s.exercises[exerciseIndex]
        val set = exercise.sets[setIndex]
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
    fun setNote(index: Int, note: String) = update { it.copy(exercises = it.exercises.mapIndexed { i, e -> if (i == index) e.copy(note = note) else e }) }

    /** Adds a work set like the last one (never a copy of a warm-up). */
    fun addSet(index: Int) = update { s ->
        s.copy(exercises = s.exercises.mapIndexed { i, e ->
            if (i != index) e else e.copy(sets = e.sets + (e.workSets.lastOrNull()?.copy(done = false) ?: SetDraft()))
        })
    }

    fun updateSet(exerciseIndex: Int, setIndex: Int, draft: SetDraft) = update { s ->
        s.copy(exercises = s.exercises.mapIndexed { i, e ->
            if (i != exerciseIndex) e else e.copy(sets = e.sets.mapIndexed { j, d -> if (j == setIndex) draft else d })
        })
    }

    fun removeSet(exerciseIndex: Int, setIndex: Int) = update { s ->
        s.copy(exercises = s.exercises.mapIndexed { i, e ->
            if (i != exerciseIndex) e else e.copy(sets = e.sets.filterIndexed { j, _ -> j != setIndex })
        })
    }

    /** The sets worth storing, or null (with the error shown) when there are none. */
    private fun entries(s: EditorState): List<ExerciseEntry>? {
        val entries = s.exercises.mapNotNull { draft ->
            val sets = draft.sets.mapIndexedNotNull { i, d -> d.toSet(i, s.unit) }.mapIndexed { i, set -> set.copy(order = i) }
            if (sets.isEmpty()) null else ExerciseEntry(draft.exercise.id, sets, draft.note.ifBlank { null })
        }
        if (entries.isEmpty()) { update { it.copy(error = "Add at least one exercise with a set.") }; return null }
        return entries
    }

    /** Stores a logged or edited workout from the date, time and duration fields. */
    fun save() {
        val s = _state.value
        if (s.isLive) return endSession()
        val date = runCatching { LocalDate.parse(s.date.trim()) }.getOrNull()
        val time = Regex("^(\\d{1,2}):(\\d{2})$").find(s.time.trim())?.let { m ->
            runCatching { LocalTime(m.groupValues[1].toInt(), m.groupValues[2].toInt()) }.getOrNull()
        }
        if (date == null) { update { it.copy(error = "Enter the date as YYYY-MM-DD.") }; return }
        if (time == null) { update { it.copy(error = "Enter the time as HH:MM.") }; return }
        val entries = entries(s) ?: return
        val timestamp = LocalDateTime(date, time)
        val session = Session(
            id = s.id ?: timestamp.toString(),
            timestamp = timestamp,
            durationMinutes = s.durationMinutes.toIntOrNull()?.takeIf { it > 0 },
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
     * Past three hours the lifter is asked first, since a timer that long was probably left running.
     */
    fun endSession() {
        val s = _state.value
        val started = s.startedAtMs ?: return
        if (entries(s) == null) return
        val minutes = LiveSession.durationMinutes(nowMs() - started)
        if (LiveSession.isLong(nowMs() - started)) update { it.copy(longSessionMinutes = minutes, error = null) }
        else finish(minutes)
    }

    /** The answer to the long-session question: the duration to store. */
    fun confirmEnd(minutes: Int) { update { it.copy(longSessionMinutes = null) }; finish(minutes) }
    fun cancelEnd() = update { it.copy(longSessionMinutes = null) }

    private fun finish(minutes: Int) {
        val s = _state.value
        val started = s.startedAtMs ?: return
        val entries = entries(s) ?: return
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
    if (state.loading) return
    if (state.saved) { onDone(); return }
    val fieldColors = OutlinedTextFieldDefaults.colors(focusedBorderColor = palette.volt, unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant)
    val startedAt = state.startedAtMs

    Column(Modifier.fillMaxSize()) {
        // The clock stays in view however far the list is scrolled.
        if (startedAt != null) SessionClock(startedAt, state.restTimer, onSkipRest = model::dismissRest, onEnd = model::endSession)
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
                if (startedAt != null) {
                    val started = Instant.fromEpochMilliseconds(startedAt).toLocalDateTime(TimeZone.currentSystemDefault())
                    Text(
                        "Started ${started.hour.toString().padStart(2, '0')}:${started.minute.toString().padStart(2, '0')}. Tap a set's number when it is done to start the rest timer.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(state.date, model::setDate, label = { Text("Date") }, singleLine = true, modifier = Modifier.weight(1.2f), colors = fieldColors, shape = MaterialTheme.shapes.medium)
                        OutlinedTextField(state.time, model::setTime, label = { Text("Time") }, singleLine = true, modifier = Modifier.weight(0.8f), colors = fieldColors, shape = MaterialTheme.shapes.medium)
                        OutlinedTextField(state.durationMinutes, model::setDuration, label = { Text("Min") }, singleLine = true, modifier = Modifier.weight(0.7f), colors = fieldColors, shape = MaterialTheme.shapes.medium, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                }
                SectionHeader("Exercises", action = { TextButton(onClick = { pickerOpen = true }) { Text("+ Add exercise", color = palette.volt) } })
                if (state.exercises.isEmpty()) {
                    Text("Add an exercise to start logging sets. Weights are in ${state.unit.label}; leave weight empty for bodyweight, use seconds for holds and km for cardio.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    restTimer = state.restTimer?.takeIf { it.exerciseId == draft.exercise.id },
                )
            }
            item {
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp)) }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (startedAt != null) {
                        SecondaryButton("Discard", { confirmDiscard = true }, Modifier.weight(1f))
                        PrimaryButton("End session", { model.endSession() }, Modifier.weight(1f))
                    } else {
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
    state.conflict?.let { running ->
        val elapsed = LiveSession.durationMinutes(running.elapsedMs(nowMs()))
        AlertDialog(
            onDismissRequest = model::resumeStored,
            shape = MaterialTheme.shapes.large,
            title = { Text("Workout in progress") },
            text = { Text("${running.title} was started ${Format.minutes(elapsed)} ago and has not been ended. Resume it, or discard it and start ${state.title}?") },
            confirmButton = { PrimaryButton("Resume ${running.title}", onClick = model::resumeStored) },
            dismissButton = { TextButton(onClick = model::discardStoredAndStart) { Text("Discard and start ${state.title}", color = palette.coral) } },
        )
    }
    state.longSessionMinutes?.let { timed -> LongSessionDialog(timed, onConfirm = model::confirmEnd, onCancel = model::cancelEnd, fieldColors) }
}

/**
 * The timer ran past three hours, which usually means it was left running. The measured time is
 * offered as the duration and can be replaced before the session is stored.
 */
@Composable
private fun LongSessionDialog(timedMinutes: Int, onConfirm: (Int) -> Unit, onCancel: () -> Unit, fieldColors: androidx.compose.material3.TextFieldColors) {
    var minutes by remember(timedMinutes) { mutableStateOf(timedMinutes.toString()) }
    val entered = minutes.trim().toIntOrNull()?.takeIf { it > 0 }
    AlertDialog(
        onDismissRequest = onCancel,
        shape = MaterialTheme.shapes.large,
        title = { Text("Long session") },
        text = {
            Column {
                Text("The timer ran for ${Format.minutes(timedMinutes)}. Was that how long you trained? Change the duration below if not.")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    minutes, { minutes = it }, label = { Text("Duration in minutes") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    colors = fieldColors, shape = MaterialTheme.shapes.medium, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = { Text(if (entered == null) "Enter a number of minutes." else if (entered == timedMinutes) "Kept as timed." else "Stored as ${Format.minutes(entered)}.") },
                )
            }
        },
        confirmButton = { PrimaryButton("Save session", onClick = { onConfirm(entered ?: timedMinutes) }, enabled = entered != null) },
        dismissButton = { TextButton(onClick = onCancel) { Text("Back to workout") } },
    )
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
private fun DayChoice(label: String, selected: Boolean, accent: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = if (selected) accent else MaterialTheme.colorScheme.onSurface)
        if (selected) Text("✓", color = accent, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ExerciseCard(
    exerciseIndex: Int, draft: ExerciseDraft, unit: WeightUnit, model: SessionEditorModel, fieldColors: androidx.compose.material3.TextFieldColors,
    target: String? = null, hint: String? = null, programNote: String? = null, seeded: Boolean = false,
    source: Progression.Source? = null, tier: Gzclp.Tier? = null, warmupsCollapsed: Boolean = false, restTimer: RestTimer? = null,
) {
    val palette = GainsColors.palette
    val modality = draft.exercise.modality
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val warmups = draft.warmups
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
        if (restTimer != null) RestTimerRow(restTimer, onDismiss = model::dismissRest)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("SET", Modifier.width(28.dp), style = MaterialTheme.typography.labelSmall, color = muted)
            when (modality) {
                Modality.WEIGHTED, Modality.BODYWEIGHT -> { Header(unit.label.uppercase()); Header("REPS") }
                Modality.ISOMETRIC -> { Header("SECONDS"); Header(unit.label.uppercase()) }
                Modality.CARDIO -> { Header("KM"); Header("SECONDS") }
            }
            Spacer(Modifier.width(44.dp))
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
        var warmupNumber = 0
        var workNumber = 0
        for ((setIndex, set) in draft.sets.withIndex()) {
            val label = if (set.isWarmup) "W${++warmupNumber}" else (++workNumber).toString()
            if (set.isWarmup && warmupsCollapsed) continue
            val rowColor = if (set.isWarmup) muted else MaterialTheme.colorScheme.onSurface
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                // Tapping the number ticks the set off and starts the rest timer.
                Text(
                    if (set.done) "✓" else label,
                    Modifier.width(28.dp).clickable { model.toggleDone(exerciseIndex, setIndex) },
                    style = if (set.isWarmup) MaterialTheme.typography.labelMedium else MaterialTheme.typography.titleSmall,
                    color = if (set.done) palette.volt else rowColor,
                )
                when (modality) {
                    Modality.WEIGHTED, Modality.BODYWEIGHT -> {
                        NumberField(set.weight, { model.updateSet(exerciseIndex, setIndex, set.copy(weight = it)) }, fieldColors, Modifier.weight(1f), muted = set.isWarmup)
                        NumberField(set.reps, { model.updateSet(exerciseIndex, setIndex, set.copy(reps = it)) }, fieldColors, Modifier.weight(1f), muted = set.isWarmup)
                    }
                    Modality.ISOMETRIC -> {
                        NumberField(set.seconds, { model.updateSet(exerciseIndex, setIndex, set.copy(seconds = it)) }, fieldColors, Modifier.weight(1f), muted = set.isWarmup)
                        NumberField(set.weight, { model.updateSet(exerciseIndex, setIndex, set.copy(weight = it)) }, fieldColors, Modifier.weight(1f), muted = set.isWarmup)
                    }
                    Modality.CARDIO -> {
                        NumberField(set.distanceKm, { model.updateSet(exerciseIndex, setIndex, set.copy(distanceKm = it)) }, fieldColors, Modifier.weight(1f), muted = set.isWarmup)
                        NumberField(set.seconds, { model.updateSet(exerciseIndex, setIndex, set.copy(seconds = it)) }, fieldColors, Modifier.weight(1f), muted = set.isWarmup)
                    }
                }
                TextButton(onClick = { model.removeSet(exerciseIndex, setIndex) }, modifier = Modifier.width(44.dp)) { Text("×", color = muted) }
            }
        }
        TextButton(onClick = { model.addSet(exerciseIndex) }) { Text("+ Add set", color = palette.volt) }
        OutlinedTextField(draft.note, { model.setNote(exerciseIndex, it) }, placeholder = { Text("Note") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = fieldColors, shape = MaterialTheme.shapes.medium)
    }
}

/** "Rest 2:47" counting down, then "Rest done" until dismissed or the next set is ticked. */
@Composable
private fun RestTimerRow(timer: RestTimer, onDismiss: () -> Unit) {
    val palette = GainsColors.palette
    var now by remember(timer) { mutableStateOf(nowMs()) }
    LaunchedEffect(timer) {
        while (now < timer.endsAtMs) { delay(250); now = nowMs() }
    }
    val remaining = timer.remainingSeconds(now)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (remaining > 0) "Rest ${Format.seconds(remaining)}" else "Rest done: next set",
            Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = if (remaining > 0) palette.cyan else palette.volt,
        )
        TextButton(onClick = onDismiss) { Text(if (remaining > 0) "Skip" else "OK", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Header(text: String) {
    Text(text, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun NumberField(value: String, onChange: (String) -> Unit, colors: androidx.compose.material3.TextFieldColors, modifier: Modifier, muted: Boolean = false) {
    OutlinedTextField(
        value, onChange, singleLine = true, modifier = modifier, colors = colors, shape = MaterialTheme.shapes.small,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        textStyle = if (muted) MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant) else MaterialTheme.typography.bodyLarge,
    )
}
