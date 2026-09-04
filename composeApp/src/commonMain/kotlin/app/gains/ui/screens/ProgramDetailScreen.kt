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
import app.gains.domain.Exercise
import app.gains.domain.Program
import app.gains.domain.ProgramDay
import app.gains.domain.ProgramDayRef
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
    /** Set after the program was deleted or duplicated, so the screen can navigate away. */
    val navigateTo: String? = null,
    val deleted: Boolean = false,
)

class ProgramDetailModel(
    private val programId: String,
    private val programs: ProgramRepository = inject(),
    sessions: SessionRepository = inject(),
    trainingData: TrainingData = inject(),
) : ScreenModel() {
    private var navigateTo by mutableStateOf<String?>(null)
    private var deleted by mutableStateOf(false)

    val state: StateFlow<ProgramDetailState> = combine(programs.observeState(), sessions.observeProgramLinks(), trainingData.snapshot) { s, links, snapshot ->
        val program = s.programs.firstOrNull { it.id == programId }
        ProgramDetailState(
            loading = false,
            program = program,
            isActive = s.activeProgramId == programId,
            upNextDayId = program?.let { Rotation.nextDay(it, links)?.id },
            lastByDay = program?.let { Rotation.lastCompletedByDay(it, links) } ?: emptyMap(),
            exercisesById = snapshot.exercisesById,
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
            SectionHeader("Days", action = {
                Text("Tap any day to start it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            })
        }
        items(program.days, key = { it.id }) { day ->
            DayCard(day, program, upNext = day.id == state.upNextDayId, last = state.lastByDay[day.id], today, state.exercisesById) {
                onStartDay(ProgramDayRef(program.id, day.id))
            }
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
