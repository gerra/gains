package app.gains.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gains.analysis.Dates
import app.gains.analysis.ExerciseAnalysis
import app.gains.analysis.Format
import app.gains.analysis.TrainingData
import app.gains.data.SettingsRepository
import app.gains.domain.Exercise
import app.gains.domain.Modality
import app.gains.domain.WeightUnit
import app.gains.ui.ScreenModel
import app.gains.ui.components.EmptyState
import app.gains.ui.inject
import app.gains.ui.rememberScreenModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate

data class ExerciseRow(
    val exercise: Exercise,
    val sessions: Int,
    val lastTrained: LocalDate,
    val bestText: String,
)

data class ExercisesState(val loading: Boolean = true, val rows: List<ExerciseRow> = emptyList())

class ExercisesModel(trainingData: TrainingData = inject(), settings: SettingsRepository = inject()) : ScreenModel() {
    val state: StateFlow<ExercisesState> = combine(trainingData.snapshot, settings.observeUnit()) { s, u -> s to u }
        .mapLatest { (snapshot, unit) ->
            withContext(Dispatchers.Default) {
                val rows = snapshot.trainedExercises.map { exercise ->
                    val history = ExerciseAnalysis.history(snapshot.sessions, exercise)
                    val best = history.mapNotNull { it.best }.maxByOrNull { it.value }
                    ExerciseRow(
                        exercise = exercise,
                        sessions = history.size,
                        lastTrained = history.last().date,
                        bestText = best?.describe(exercise.modality, unit) ?: "-",
                    )
                }
                ExercisesState(false, rows)
            }
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), ExercisesState())
}

@Composable
fun ExercisesScreen(onOpen: (String) -> Unit) {
    val model = rememberScreenModel { ExercisesModel() }
    val state by model.state.collectAsState()
    var query by remember { mutableStateOf("") }
    val today = Dates.today()

    if (!state.loading && state.rows.isEmpty()) {
        EmptyState("No lifts yet", "Import a Liftoff export and every exercise you've logged will be listed here.")
        return
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            label = { Text("Search") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        val filtered = state.rows.filter { query.isBlank() || it.exercise.name.contains(query, ignoreCase = true) }
        LazyColumn(Modifier.fillMaxSize()) {
            items(filtered, key = { it.exercise.id }) { row ->
                Column(Modifier.fillMaxWidth().clickable { onOpen(row.exercise.id) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(row.exercise.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text(row.bestText, style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        "${Format.plural(row.sessions, "session")} · last ${Dates.contextual(row.lastTrained, today)}" +
                            if (row.exercise.modality == Modality.WEIGHTED && row.exercise.isDumbbell) " · per dumbbell" else "",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
            }
        }
    }
}
