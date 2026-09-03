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
import app.gains.analysis.TrainingData
import app.gains.data.ExerciseRepository
import app.gains.data.ProgramCodec
import app.gains.data.ProgramRepository
import app.gains.domain.Exercise
import app.gains.domain.Experience
import app.gains.domain.ExerciseSlot
import app.gains.domain.Goal
import app.gains.domain.GoalProfile
import app.gains.domain.Program
import app.gains.domain.ProgramDay
import app.gains.domain.ProgressionRule
import app.gains.domain.RepTarget
import app.gains.importer.ExerciseResolver
import app.gains.ui.ScreenModel
import app.gains.ui.components.ChipRow
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** The progression choices a user can set on a slot. Ladders from duplicated built-ins are kept as-is. */
enum class ProgressionChoice(val label: String) {
    NONE("None"),
    SMALL("+2.5 kg"),
    BIG("+5 kg"),
    DOUBLE("Reps then weight");

    companion object {
        fun of(rule: ProgressionRule): ProgressionChoice = when (rule) {
            ProgressionRule.None -> NONE
            is ProgressionRule.Linear -> if (rule.stepKg >= 5.0) BIG else SMALL
            is ProgressionRule.DoubleProgression -> DOUBLE
            is ProgressionRule.StageLadder -> NONE
        }
    }
}

data class SlotDraft(
    val exercise: Exercise,
    val sets: String = "3",
    val reps: String = "8-12",
    val choice: ProgressionChoice = ProgressionChoice.DOUBLE,
    /** A ladder rule carried over from a duplicated built-in; kept unless the choice is changed. */
    val ladder: ProgressionRule.StageLadder? = null,
    val note: String = "",
) {
    fun toSlot(): ExerciseSlot? {
        val n = sets.trim().toIntOrNull()?.takeIf { it in 1..20 } ?: return null
        val target = ProgramCodec.decodeReps(reps) ?: return null
        val rule: ProgressionRule = ladder ?: when (choice) {
            ProgressionChoice.NONE -> ProgressionRule.None
            ProgressionChoice.SMALL -> ProgressionRule.Linear(2.5, 5.0)
            ProgressionChoice.BIG -> ProgressionRule.Linear(5.0, 10.0)
            ProgressionChoice.DOUBLE -> when (target) {
                is RepTarget.Range -> ProgressionRule.DoubleProgression(target.min, target.max, 2.5, 5.0)
                else -> ProgressionRule.Linear(2.5, 5.0)
            }
        }
        return ExerciseSlot(exercise.id, n, target, progression = rule, note = note.ifBlank { null })
    }

    companion object {
        fun from(slot: ExerciseSlot, exercise: Exercise) = SlotDraft(
            exercise, slot.sets.toString(), ProgramCodec.encodeReps(slot.reps), ProgressionChoice.of(slot.progression),
            ladder = slot.progression as? ProgressionRule.StageLadder, note = slot.note ?: "",
        )
    }
}

data class DayDraft(val id: String, val name: String, val slots: List<SlotDraft>)

data class ProgramEditorState(
    val loading: Boolean = true,
    val id: String? = null,
    val name: String = "",
    val description: String = "",
    val days: List<DayDraft> = emptyList(),
    val catalogue: List<Exercise> = emptyList(),
    val recent: List<Exercise> = emptyList(),
    val profile: GoalProfile? = null,
    val error: String? = null,
    val saved: Boolean = false,
)

class ProgramEditorModel(
    private val programId: String?,
    private val programs: ProgramRepository = inject(),
    private val exercises: ExerciseRepository = inject(),
    trainingData: TrainingData = inject(),
) : ScreenModel() {
    private val _state = MutableStateFlow(ProgramEditorState())
    val state: StateFlow<ProgramEditorState> = _state

    init {
        scope.launch {
            val snapshot = trainingData.snapshot.first()
            val programState = programs.observeState().first()
            val existing = programId?.let { id -> programState.programs.firstOrNull { it.id == id && !it.isBuiltIn } }
            val base = ProgramEditorState(
                loading = false, catalogue = snapshot.exercises.sortedBy { it.name }, recent = snapshot.trainedExercises.take(12), profile = programState.profile,
            )
            _state.value = if (existing == null) base.copy(days = listOf(DayDraft(ProgramRepository.newDayId("new", 0), "Day 1", emptyList())))
            else base.copy(
                id = existing.id, name = existing.name, description = existing.description,
                days = existing.days.map { d -> DayDraft(d.id, d.name, d.slots.mapNotNull { s -> snapshot.exercisesById[s.exerciseId]?.let { SlotDraft.from(s, it) } }) },
            )
        }
    }

    private fun update(f: (ProgramEditorState) -> ProgramEditorState) { _state.value = f(_state.value) }
    private fun updateDay(index: Int, f: (DayDraft) -> DayDraft) = update { s -> s.copy(days = s.days.mapIndexed { i, d -> if (i == index) f(d) else d }) }

    fun setName(v: String) = update { it.copy(name = v) }
    fun setDescription(v: String) = update { it.copy(description = v) }
    fun addDay() = update { s -> s.copy(days = s.days + DayDraft(ProgramRepository.newDayId(s.id ?: "new", s.days.size), "Day ${s.days.size + 1}", emptyList())) }
    fun renameDay(index: Int, name: String) = updateDay(index) { it.copy(name = name) }
    fun removeDay(index: Int) = update { s -> s.copy(days = s.days.filterIndexed { i, _ -> i != index }) }
    fun moveDay(index: Int, delta: Int) = update { s ->
        val target = index + delta
        if (target !in s.days.indices) s else s.copy(days = s.days.toMutableList().apply { add(target, removeAt(index)) })
    }

    fun addExercises(dayIndex: Int, picked: List<Exercise>) = updateDay(dayIndex) { d ->
        val known = d.slots.map { it.exercise.id }.toSet()
        d.copy(slots = d.slots + picked.filter { it.id !in known }.map { SlotDraft(it) })
    }

    fun createExercise(name: String): Exercise {
        val exercise = ExerciseResolver(_state.value.catalogue, emptyMap()).resolve(name, emptyList())
        scope.launch { exercises.insertIfMissing(listOf(exercise)) }
        update { it.copy(catalogue = (it.catalogue + exercise).distinctBy { e -> e.id }.sortedBy { e -> e.name }) }
        return exercise
    }

    fun updateSlot(dayIndex: Int, slotIndex: Int, draft: SlotDraft) = updateDay(dayIndex) { d -> d.copy(slots = d.slots.mapIndexed { i, s -> if (i == slotIndex) draft else s }) }
    fun removeSlot(dayIndex: Int, slotIndex: Int) = updateDay(dayIndex) { d -> d.copy(slots = d.slots.filterIndexed { i, _ -> i != slotIndex }) }
    fun moveSlot(dayIndex: Int, slotIndex: Int, delta: Int) = updateDay(dayIndex) { d ->
        val target = slotIndex + delta
        if (target !in d.slots.indices) d else d.copy(slots = d.slots.toMutableList().apply { add(target, removeAt(slotIndex)) })
    }

    fun save() {
        val s = _state.value
        val name = s.name.trim()
        if (name.isEmpty()) { update { it.copy(error = "Give the program a name.") }; return }
        if (s.days.isEmpty()) { update { it.copy(error = "Add at least one day.") }; return }
        val id = s.id ?: ProgramRepository.newProgramId()
        val days = ArrayList<ProgramDay>()
        for ((di, day) in s.days.withIndex()) {
            if (day.slots.isEmpty()) { update { it.copy(error = "${day.name.ifBlank { "Day ${di + 1}" }} has no exercises.") }; return }
            val slots = ArrayList<ExerciseSlot>()
            for (slot in day.slots) {
                slots.add(slot.toSlot() ?: run {
                    update { it.copy(error = "${slot.exercise.name}: sets must be 1–20 and reps look like 5, 8-12 or 5+.") }
                    return
                })
            }
            val dayId = if (day.id.startsWith("new/")) ProgramRepository.newDayId(id, di) else day.id
            days.add(ProgramDay(dayId, day.name.trim().ifBlank { "Day ${di + 1}" }, slots))
        }
        val program = Program(
            id = id, name = name, description = s.description.trim(),
            goals = setOf(s.profile?.goal ?: Goal.GENERAL_FITNESS), level = s.profile?.experience ?: Experience.BEGINNER,
            daysPerWeek = days.size.coerceIn(GoalProfile.MIN_DAYS, GoalProfile.MAX_DAYS), days = days, isBuiltIn = false,
        )
        scope.launch { programs.upsert(program); update { it.copy(saved = true, error = null) } }
    }
}

@Composable
fun ProgramEditorScreen(programId: String?, onDone: () -> Unit) {
    val model = rememberScreenModel(programId) { ProgramEditorModel(programId) }
    val state by model.state.collectAsState()
    val palette = GainsColors.palette
    var pickerFor by remember { mutableStateOf<Int?>(null) }
    if (state.loading) return
    if (state.saved) { onDone(); return }
    val fieldColors = OutlinedTextFieldDefaults.colors(focusedBorderColor = palette.volt, unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
        item {
            Text(if (state.id == null) "New program" else "Edit program", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(state.name, model::setName, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = fieldColors, shape = MaterialTheme.shapes.medium)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(state.description, model::setDescription, label = { Text("Description (optional)") }, modifier = Modifier.fillMaxWidth(), colors = fieldColors, shape = MaterialTheme.shapes.medium, maxLines = 3)
            SectionHeader("Days", action = { TextButton(onClick = { model.addDay() }) { Text("+ Add day", color = palette.volt) } })
            Text("Each day is one session. Days rotate in this order whenever you finish one.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
        }
        itemsIndexed(state.days, key = { _, d -> d.id }) { dayIndex, day ->
            GainsCard(Modifier.fillMaxWidth().padding(bottom = 10.dp), contentPadding = Dp16.Tight) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(day.name, { model.renameDay(dayIndex, it) }, label = { Text("Day name") }, singleLine = true, modifier = Modifier.weight(1f), colors = fieldColors, shape = MaterialTheme.shapes.medium)
                    TextButton(onClick = { model.moveDay(dayIndex, -1) }, enabled = dayIndex > 0) { Text("↑") }
                    TextButton(onClick = { model.moveDay(dayIndex, 1) }, enabled = dayIndex < state.days.lastIndex) { Text("↓") }
                    TextButton(onClick = { model.removeDay(dayIndex) }) { Text("×", color = palette.coral) }
                }
                Spacer(Modifier.height(6.dp))
                for ((slotIndex, slot) in day.slots.withIndex()) {
                    SlotRow(slot, slotIndex, day.slots.size, fieldColors,
                        onChange = { model.updateSlot(dayIndex, slotIndex, it) },
                        onMove = { model.moveSlot(dayIndex, slotIndex, it) },
                        onRemove = { model.removeSlot(dayIndex, slotIndex) })
                }
                TextButton(onClick = { pickerFor = dayIndex }) { Text("+ Add exercise", color = palette.volt) }
            }
        }
        item {
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp)) }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Cancel", onDone, Modifier.weight(1f))
                PrimaryButton("Save program", { model.save() }, Modifier.weight(1f))
            }
        }
    }

    pickerFor?.let { dayIndex ->
        ExercisePickerSheet(
            catalogue = state.catalogue,
            recent = state.recent,
            alreadyAdded = state.days.getOrNull(dayIndex)?.slots?.map { it.exercise.id }?.toSet() ?: emptySet(),
            onAdd = { model.addExercises(dayIndex, it) },
            onCreate = model::createExercise,
            onDismiss = { pickerFor = null },
        )
    }
}

@Composable
private fun SlotRow(
    slot: SlotDraft, index: Int, count: Int, fieldColors: androidx.compose.material3.TextFieldColors,
    onChange: (SlotDraft) -> Unit, onMove: (Int) -> Unit, onRemove: () -> Unit,
) {
    val palette = GainsColors.palette
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(slot.exercise.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { onMove(-1) }, enabled = index > 0) { Text("↑") }
            TextButton(onClick = { onMove(1) }, enabled = index < count - 1) { Text("↓") }
            TextButton(onClick = onRemove) { Text("×", color = palette.coral) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(slot.sets, { onChange(slot.copy(sets = it)) }, label = { Text("Sets") }, singleLine = true, modifier = Modifier.width(80.dp), colors = fieldColors, shape = MaterialTheme.shapes.small, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(slot.reps, { onChange(slot.copy(reps = it)) }, label = { Text("Reps") }, singleLine = true, modifier = Modifier.width(110.dp), colors = fieldColors, shape = MaterialTheme.shapes.small, placeholder = { Text("8-12") })
            OutlinedTextField(slot.note, { onChange(slot.copy(note = it)) }, label = { Text("Note") }, singleLine = true, modifier = Modifier.weight(1f), colors = fieldColors, shape = MaterialTheme.shapes.small)
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (slot.ladder != null) {
                Pill("Program ladder kept", palette.violet)
                TextButton(onClick = { onChange(slot.copy(ladder = null)) }) { Text("Change", color = palette.volt) }
            } else {
                ChipRow(ProgressionChoice.entries, slot.choice, { it.label }, { onChange(slot.copy(choice = it)) })
            }
        }
    }
}
