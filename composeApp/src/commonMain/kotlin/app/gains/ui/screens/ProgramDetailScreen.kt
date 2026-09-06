package app.gains.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import app.gains.analysis.Dates
import app.gains.analysis.Format
import app.gains.analysis.TrainingData
import app.gains.data.ProgramRepository
import app.gains.data.SessionRepository
import app.gains.data.SettingsRepository
import app.gains.domain.Exercise
import app.gains.domain.Program
import app.gains.domain.ProgramDay
import app.gains.domain.ProgramDayRef
import app.gains.domain.ProgressionRule
import app.gains.domain.WeightUnit
import app.gains.program.Progression
import app.gains.program.Rotation
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

data class ProgramDetailState(
    val loading: Boolean = true,
    val program: Program? = null,
    val isActive: Boolean = false,
    val upNextDayId: String? = null,
    val lastByDay: Map<String, LocalDate> = emptyMap(),
    val exercisesById: Map<String, Exercise> = emptyMap(),
    val unit: WeightUnit = WeightUnit.KG,
    /** The rotation from the next day up, one list per week; see [Rotation.cycle]. */
    val cycle: List<List<ProgramDay>> = emptyList(),
    /** Set after the program was deleted or duplicated, so the screen can navigate away. */
    val navigateTo: String? = null,
    val deleted: Boolean = false,
)

class ProgramDetailModel(
    private val programId: String,
    private val programs: ProgramRepository = inject(),
    sessions: SessionRepository = inject(),
    trainingData: TrainingData = inject(),
    settings: SettingsRepository = inject(),
) : ScreenModel() {
    private var navigateTo by mutableStateOf<String?>(null)
    private var deleted by mutableStateOf(false)

    val state: StateFlow<ProgramDetailState> = combine(programs.observeState(), sessions.observeProgramLinks(), trainingData.snapshot, settings.observeUnit()) { s, links, snapshot, unit ->
        val program = s.programs.firstOrNull { it.id == programId }
        ProgramDetailState(
            loading = false,
            program = program,
            isActive = s.activeProgramId == programId,
            upNextDayId = program?.let { Rotation.nextDay(it, links)?.id },
            lastByDay = program?.let { Rotation.lastCompletedByDay(it, links) } ?: emptyMap(),
            exercisesById = snapshot.exercisesById,
            unit = unit,
            cycle = program?.let { Rotation.cycle(it, links) } ?: emptyList(),
        )
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), ProgramDetailState())

    val pendingNavigation: String? get() = navigateTo
    val wasDeleted: Boolean get() = deleted

    fun activate() { scope.launch { programs.setActive(programId) } }
    fun deactivate() { scope.launch { programs.setActive(null) } }

    /** Copies the program so it can be edited; the caller opens the editor on the new id. */
    fun duplicate(source: Program) {
        scope.launch {
            val copy = programs.duplicate(source)
            programs.upsert(copy)
            navigateTo = copy.id
        }
    }

    fun delete() { scope.launch { programs.delete(programId); deleted = true } }
}

@Composable
fun ProgramDetailScreen(programId: String, onStartDay: (ProgramDayRef) -> Unit, onEdit: (String) -> Unit, onDeleted: () -> Unit) {
    val model = rememberScreenModel(programId) { ProgramDetailModel(programId) }
    val state by model.state.collectAsState()
    val palette = GainsColors.palette
    var confirmDelete by remember { mutableStateOf(false) }
    if (model.wasDeleted) { onDeleted(); return }
    model.pendingNavigation?.let { onEdit(it); return }
    if (state.loading) return
    val program = state.program ?: run { onDeleted(); return }
    val today = Dates.today()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
        item {
            Text(program.name, style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(8.dp))
            ProgramTags(program, state.isActive)
            Spacer(Modifier.height(10.dp))
            Text(program.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.isActive) SecondaryButton("Deactivate", onClick = { model.deactivate() }, Modifier.weight(1f))
                else PrimaryButton("Activate", onClick = { model.activate() }, Modifier.weight(1f))
                if (program.isBuiltIn) SecondaryButton("Duplicate to edit", onClick = { model.duplicate(program) }, Modifier.weight(1f))
                else SecondaryButton("Edit", onClick = { onEdit(program.id) }, Modifier.weight(1f))
            }
            if (state.cycle.isNotEmpty()) {
                SectionHeader("Schedule", action = {
                    Text(
                        "${Format.plural(state.cycle.size, "week")} · ${program.daysPerWeek} days a week",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                })
                ScheduleCard(state.cycle, state.upNextDayId)
            }
            SectionHeader("Days", action = {
                Text("Tap any day to start it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            })
        }
        items(program.days, key = { it.id }) { day ->
            DayCard(day, program, upNext = day.id == state.upNextDayId, last = state.lastByDay[day.id], today, state.exercisesById) {
                onStartDay(ProgramDayRef(program.id, day.id))
            }
        }
        item {
            SectionHeader("How it progresses")
            ProgressionCard(program, state.exercisesById, state.unit)
        }
        if (!program.isBuiltIn) {
            item {
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = { confirmDelete = true }) { Text("Delete program", color = palette.coral) }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            shape = MaterialTheme.shapes.large,
            title = { Text("Delete ${program.name}?") },
            text = { Text("Workouts you logged from it are kept; they just lose the day label.") },
            confirmButton = { PrimaryButton("Delete", onClick = { model.delete(); confirmDelete = false }) },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DayCard(day: ProgramDay, program: Program, upNext: Boolean, last: LocalDate?, today: LocalDate, exercisesById: Map<String, Exercise>, onClick: () -> Unit) {
    val palette = GainsColors.palette
    GainsCard(Modifier.fillMaxWidth().padding(bottom = 8.dp), onClick = onClick, contentPadding = Dp16.Tight) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(day.name, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(8.dp))
                    if (upNext) Pill("Up next", palette.volt, filled = true)
                }
                Spacer(Modifier.height(4.dp))
                for (slot in day.slots) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(exercisesById[slot.exerciseId]?.name ?: slot.exerciseId, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        Text(slot.targetLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(Format.plural(day.slots.size, "exercise"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text(last?.let { "Last ${Dates.contextual(it, today)}" } ?: "Not done yet", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * One full cycle of the rotation, a row per week, so the whole program is visible at once rather
 * than just the next day. Only the very next session is highlighted: the rest is a projection that
 * shifts if a different day is started.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduleCard(cycle: List<List<ProgramDay>>, upNextDayId: String?) {
    val palette = GainsColors.palette
    GainsCard(Modifier.fillMaxWidth(), contentPadding = Dp16.Tight) {
        cycle.forEachIndexed { week, days ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Week ${week + 1}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(52.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    days.forEachIndexed { i, day ->
                        val next = week == 0 && i == 0 && day.id == upNextDayId
                        Pill(day.name, if (next) palette.volt else MaterialTheme.colorScheme.onSurfaceVariant, filled = next)
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Days come in this order as you finish them, whatever the weekday. Start a different day and the rest follow on from it.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A slot's scheme: the exercises sharing a note and a rule are described once. */
private data class Scheme(val note: String?, val rule: ProgressionRule)

/** Every distinct scheme in the program, in order of first appearance, with the lifts that use it. */
@Composable
private fun ProgressionCard(program: Program, exercisesById: Map<String, Exercise>, unit: WeightUnit) {
    val schemes = LinkedHashMap<Scheme, MutableList<String>>()
    for (day in program.days) for (slot in day.slots) {
        if (slot.note == null && slot.progression == ProgressionRule.None) continue
        val name = exercisesById[slot.exerciseId]?.name ?: slot.exerciseId
        val names = schemes.getOrPut(Scheme(slot.note, slot.progression)) { mutableListOf() }
        if (name !in names) names += name
    }
    GainsCard(Modifier.fillMaxWidth(), contentPadding = Dp16.Tight) {
        if (schemes.isEmpty()) {
            Text(
                "No automatic rule. Each day pre-fills the weights and reps from your last session of the exercise.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        schemes.entries.forEachIndexed { i, (scheme, names) ->
            if (i > 0) Spacer(Modifier.height(12.dp))
            Text(names.joinToString(", "), style = MaterialTheme.typography.titleSmall)
            scheme.note?.let { Spacer(Modifier.height(2.dp)); Text(it, style = MaterialTheme.typography.bodySmall) }
            Progression.describe(scheme.rule, unit)?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
