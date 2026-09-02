package app.gains.ui.screens

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.gains.analysis.Dates
import app.gains.analysis.Format
import app.gains.analysis.TrainingData
import app.gains.catalogue.ExerciseCatalogue
import app.gains.data.ExerciseRepository
import app.gains.data.SessionRepository
import app.gains.data.SettingsRepository
import app.gains.domain.Exercise
import app.gains.domain.ExerciseEntry
import app.gains.domain.Modality
import app.gains.domain.Session
import app.gains.domain.SetEntry
import app.gains.domain.SetType
import app.gains.domain.Units
import app.gains.domain.WeightUnit
import app.gains.importer.ExerciseResolver
import app.gains.ui.ScreenModel
import app.gains.ui.components.Dp16
import app.gains.ui.components.GainsCard
import app.gains.ui.components.Pill
import app.gains.ui.components.PrimaryButton
import app.gains.ui.components.SecondaryButton
import app.gains.ui.components.SectionHeader
import app.gains.ui.inject
import app.gains.ui.rememberScreenModel
import app.gains.ui.theme.GainsColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/** A set being edited: text fields so partial input is allowed. */
data class SetDraft(
    val weight: String = "",
    val reps: String = "",
    val seconds: String = "",
    val distanceKm: String = "",
) {
    fun toSet(order: Int, unit: WeightUnit): SetEntry? {
        val w = weight.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }?.let { Units.roundToQuarter(Units.fromDisplay(it, unit)) }
        val r = reps.toIntOrNull()?.takeIf { it > 0 }
        val s = seconds.toIntOrNull()?.takeIf { it > 0 }
        val d = distanceKm.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }
        if (w == null && r == null && s == null && d == null) return null
        val type = when {
            d != null -> SetType.CARDIO
            r != null && w != null -> SetType.WEIGHTED
            r != null -> SetType.BODYWEIGHT
            s != null -> SetType.ISOMETRIC
            else -> SetType.WEIGHTED
        }
        return SetEntry(order, type, w, r, s, d)
    }

    companion object {
        fun from(set: SetEntry, unit: WeightUnit) = SetDraft(
            weight = set.weightKg?.let { Format.weightValue(it, unit) } ?: "",
            reps = set.reps?.toString() ?: "",
            seconds = set.seconds?.toString() ?: "",
            distanceKm = set.distanceKm?.let { Format.number(it, 2) } ?: "",
        )
    }
}

data class ExerciseDraft(val exercise: Exercise, val sets: List<SetDraft>, val note: String = "")

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
    val error: String? = null,
    val saved: Boolean = false,
)

class SessionEditorModel(
    private val sessionId: String?,
    private val sessions: SessionRepository = inject(),
    private val exercises: ExerciseRepository = inject(),
    settings: SettingsRepository = inject(),
    trainingData: TrainingData = inject(),
) : ScreenModel() {
    private val _state = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _state

    init {
        scope.launch {
            val snapshot = trainingData.snapshot.first()
            val unit = settings.observeUnit().first()
            val existing = sessionId?.let { id -> snapshot.sessions.firstOrNull { it.id == id } }
            val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
            _state.value = if (existing == null) EditorState(
                loading = false, isNew = true, date = now.date.toString(),
                time = "${now.hour.toString().padStart(2, '0')}:${now.minute.toString().padStart(2, '0')}",
                unit = unit, catalogue = snapshot.exercises.sortedBy { it.name },
            ) else EditorState(
                loading = false, isNew = false, id = existing.id, date = existing.date.toString(),
                time = "${existing.timestamp.hour.toString().padStart(2, '0')}:${existing.timestamp.minute.toString().padStart(2, '0')}",
                durationMinutes = existing.durationMinutes?.toString() ?: "",
                exercises = existing.exercises.mapNotNull { entry ->
                    snapshot.exercisesById[entry.exerciseId]?.let { ex -> ExerciseDraft(ex, entry.sets.map { SetDraft.from(it, unit) }, entry.note ?: "") }
                },
                unit = unit, catalogue = snapshot.exercises.sortedBy { it.name },
            )
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

    /** A name not in the catalogue becomes a custom exercise with guessed muscles. */
    fun addExerciseByName(name: String) {
        val resolver = ExerciseResolver(_state.value.catalogue, emptyMap())
        val exercise = resolver.resolve(name, emptyList())
        scope.launch { exercises.insertIfMissing(listOf(exercise)) }
        update { it.copy(catalogue = (it.catalogue + exercise).distinctBy { e -> e.id }.sortedBy { e -> e.name }) }
        addExercise(exercise)
    }

    fun removeExercise(index: Int) = update { it.copy(exercises = it.exercises.filterIndexed { i, _ -> i != index }) }
    fun setNote(index: Int, note: String) = update { it.copy(exercises = it.exercises.mapIndexed { i, e -> if (i == index) e.copy(note = note) else e }) }

    fun addSet(index: Int) = update { s ->
        s.copy(exercises = s.exercises.mapIndexed { i, e ->
            if (i != index) e else e.copy(sets = e.sets + (e.sets.lastOrNull()?.copy() ?: SetDraft()))
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

    fun save() {
        val s = _state.value
        val date = runCatching { LocalDate.parse(s.date.trim()) }.getOrNull()
        val time = Regex("^(\\d{1,2}):(\\d{2})$").find(s.time.trim())?.let { m ->
            runCatching { LocalTime(m.groupValues[1].toInt(), m.groupValues[2].toInt()) }.getOrNull()
        }
        if (date == null) { update { it.copy(error = "Enter the date as YYYY-MM-DD.") }; return }
        if (time == null) { update { it.copy(error = "Enter the time as HH:MM.") }; return }
        val entries = s.exercises.mapNotNull { draft ->
            val sets = draft.sets.mapIndexedNotNull { i, d -> d.toSet(i, s.unit) }.mapIndexed { i, set -> set.copy(order = i) }
            if (sets.isEmpty()) null else ExerciseEntry(draft.exercise.id, sets, draft.note.ifBlank { null })
        }
        if (entries.isEmpty()) { update { it.copy(error = "Add at least one exercise with a set.") }; return }
        val timestamp = LocalDateTime(date, time)
        val session = Session(
            id = s.id ?: timestamp.toString(),
            timestamp = timestamp,
            durationMinutes = s.durationMinutes.toIntOrNull()?.takeIf { it > 0 },
            exercises = entries,
            source = Session.MANUAL,
        )
        scope.launch {
            // Moving an existing session to another time changes its id: drop the old row.
            if (s.id != null && s.id != session.id) sessions.deleteSession(s.id)
            sessions.upsert(if (s.id != null && s.id == session.id) session else session.copy(id = timestamp.toString()))
            update { it.copy(saved = true, error = null) }
        }
    }

    fun delete() {
        val id = _state.value.id ?: return
        scope.launch { sessions.deleteSession(id); update { it.copy(saved = true) } }
    }
}

@Composable
fun SessionEditorScreen(sessionId: String?, onDone: () -> Unit) {
    val model = rememberScreenModel(sessionId) { SessionEditorModel(sessionId) }
    val state by model.state.collectAsState()
    val palette = GainsColors.palette
    var pickerOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    if (state.loading) return
    if (state.saved) { onDone(); return }
    val fieldColors = OutlinedTextFieldDefaults.colors(focusedBorderColor = palette.volt, unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (state.isNew) "Log workout" else "Edit workout", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
                if (!state.isNew) TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = palette.coral) }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(state.date, model::setDate, label = { Text("Date") }, singleLine = true, modifier = Modifier.weight(1.2f), colors = fieldColors, shape = MaterialTheme.shapes.medium)
                OutlinedTextField(state.time, model::setTime, label = { Text("Time") }, singleLine = true, modifier = Modifier.weight(0.8f), colors = fieldColors, shape = MaterialTheme.shapes.medium)
                OutlinedTextField(state.durationMinutes, model::setDuration, label = { Text("Min") }, singleLine = true, modifier = Modifier.weight(0.7f), colors = fieldColors, shape = MaterialTheme.shapes.medium, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            SectionHeader("Exercises", action = { TextButton(onClick = { pickerOpen = true }) { Text("+ Add exercise", color = palette.volt) } })
            if (state.exercises.isEmpty()) {
                Text("Add an exercise to start logging sets. Weights are in ${state.unit.label}; leave weight empty for bodyweight, use seconds for holds and km for cardio.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        itemsIndexed(state.exercises, key = { _, e -> e.exercise.id }) { exerciseIndex, draft ->
            ExerciseCard(exerciseIndex, draft, state.unit, model, fieldColors)
        }
        item {
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp)) }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Cancel", onDone, Modifier.weight(1f))
                PrimaryButton(if (state.isNew) "Save workout" else "Save changes", { model.save() }, Modifier.weight(1f))
            }
        }
    }

    if (pickerOpen) ExercisePicker(state.catalogue, onPick = { model.addExercise(it); pickerOpen = false }, onCreate = { model.addExerciseByName(it); pickerOpen = false }, onDismiss = { pickerOpen = false })
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
}

@Composable
private fun ExerciseCard(exerciseIndex: Int, draft: ExerciseDraft, unit: WeightUnit, model: SessionEditorModel, fieldColors: androidx.compose.material3.TextFieldColors) {
    val palette = GainsColors.palette
    val modality = draft.exercise.modality
    GainsCard(Modifier.fillMaxWidth().padding(bottom = 10.dp), contentPadding = Dp16.Tight) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(draft.exercise.name, style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill(modality.name.lowercase().replaceFirstChar { it.uppercase() }, palette.cyan)
                    if (draft.exercise.isDumbbell) Pill("Per dumbbell", palette.amber)
                }
            }
            TextButton(onClick = { model.removeExercise(exerciseIndex) }) { Text("Remove", color = palette.coral) }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("SET", Modifier.width(28.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (modality) {
                Modality.WEIGHTED, Modality.BODYWEIGHT -> { Header(unit.label.uppercase()); Header("REPS") }
                Modality.ISOMETRIC -> { Header("SECONDS"); Header(unit.label.uppercase()) }
                Modality.CARDIO -> { Header("KM"); Header("SECONDS") }
            }
            Spacer(Modifier.width(44.dp))
        }
        for ((setIndex, set) in draft.sets.withIndex()) {
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text((setIndex + 1).toString(), Modifier.width(28.dp), style = MaterialTheme.typography.titleSmall)
                when (modality) {
                    Modality.WEIGHTED, Modality.BODYWEIGHT -> {
                        NumberField(set.weight, { model.updateSet(exerciseIndex, setIndex, set.copy(weight = it)) }, fieldColors, Modifier.weight(1f))
                        NumberField(set.reps, { model.updateSet(exerciseIndex, setIndex, set.copy(reps = it)) }, fieldColors, Modifier.weight(1f))
                    }
                    Modality.ISOMETRIC -> {
                        NumberField(set.seconds, { model.updateSet(exerciseIndex, setIndex, set.copy(seconds = it)) }, fieldColors, Modifier.weight(1f))
                        NumberField(set.weight, { model.updateSet(exerciseIndex, setIndex, set.copy(weight = it)) }, fieldColors, Modifier.weight(1f))
                    }
                    Modality.CARDIO -> {
                        NumberField(set.distanceKm, { model.updateSet(exerciseIndex, setIndex, set.copy(distanceKm = it)) }, fieldColors, Modifier.weight(1f))
                        NumberField(set.seconds, { model.updateSet(exerciseIndex, setIndex, set.copy(seconds = it)) }, fieldColors, Modifier.weight(1f))
                    }
                }
                TextButton(onClick = { model.removeSet(exerciseIndex, setIndex) }, modifier = Modifier.width(44.dp)) { Text("×", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        TextButton(onClick = { model.addSet(exerciseIndex) }) { Text("+ Add set", color = palette.volt) }
        OutlinedTextField(draft.note, { model.setNote(exerciseIndex, it) }, placeholder = { Text("Note") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = fieldColors, shape = MaterialTheme.shapes.medium)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Header(text: String) {
    Text(text, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun NumberField(value: String, onChange: (String) -> Unit, colors: androidx.compose.material3.TextFieldColors, modifier: Modifier) {
    OutlinedTextField(
        value, onChange, singleLine = true, modifier = modifier, colors = colors, shape = MaterialTheme.shapes.small,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        textStyle = MaterialTheme.typography.bodyLarge,
    )
}

@Composable
private fun ExercisePicker(catalogue: List<Exercise>, onPick: (Exercise) -> Unit, onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val palette = GainsColors.palette
    val matches = remember(query, catalogue) {
        val q = query.trim()
        if (q.isEmpty()) catalogue.take(40) else catalogue.filter { it.name.contains(q, ignoreCase = true) }.take(40)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.large,
        title = { Text("Add exercise") },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, placeholder = { Text("Search or type a new name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = palette.volt), shape = MaterialTheme.shapes.medium)
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.height(300.dp)) {
                    itemsIndexed(matches) { _, e ->
                        TextButton(onClick = { onPick(e) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(e.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                                Text(e.muscleGroups.joinToString { it.group.displayName }.ifEmpty { e.modality.name.lowercase() }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    // Creating a new exercise is offered last, so a partial search never creates one by accident.
                    if (query.isNotBlank() && matches.none { it.name.equals(query.trim(), ignoreCase = true) }) {
                        item { TextButton(onClick = { onCreate(query.trim()) }) { Text("Create \"${query.trim()}\" as a new exercise", color = palette.volt) } }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
