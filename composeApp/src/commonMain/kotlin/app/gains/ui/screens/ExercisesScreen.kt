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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
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
import app.gains.analysis.ExerciseAnalysis
import app.gains.analysis.Format
import app.gains.analysis.TrainingData
import app.gains.data.SettingsRepository
import app.gains.domain.Exercise
import app.gains.domain.Modality
import app.gains.domain.WeightUnit
import app.gains.ui.ScreenModel
import app.gains.ui.charts.Sparkline
import app.gains.ui.components.Dp16
import app.gains.ui.components.EmptyState
import app.gains.ui.components.GainsCard
import app.gains.ui.components.ScreenTitle
import app.gains.ui.inject
import app.gains.ui.rememberScreenModel
import app.gains.ui.theme.GainsColors
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
    /** Recent best-performance values for the sparkline. */
    val trend: List<Double>,
    /** Fraction change from first to last of [trend]. */
    val trendDelta: Double?,
)

data class ExercisesState(val loading: Boolean = true, val rows: List<ExerciseRow> = emptyList())

class ExercisesModel(trainingData: TrainingData = inject(), settings: SettingsRepository = inject()) : ScreenModel() {
    val state: StateFlow<ExercisesState> = combine(trainingData.snapshot, settings.observeUnit()) { s, u -> s to u }
        .mapLatest { (snapshot, unit) ->
            withContext(Dispatchers.Default) {
                val rows = snapshot.trainedExercises.map { exercise ->
                    val history = ExerciseAnalysis.history(snapshot.sessions, exercise)
                    val best = history.mapNotNull { it.best }.maxByOrNull { it.value }
                    val trend = history.mapNotNull { it.best?.value }.takeLast(12)
                    ExerciseRow(
                        exercise = exercise,
                        sessions = history.size,
                        lastTrained = history.last().date,
                        bestText = best?.describe(exercise.modality, unit) ?: "-",
                        trend = trend,
                        trendDelta = if (trend.size >= 2 && trend.first() > 0) (trend.last() - trend.first()) / trend.first() else null,
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
    val palette = GainsColors.palette

    if (!state.loading && state.rows.isEmpty()) {
        EmptyState("No lifts yet", "Import a Liftoff export and every exercise you've logged will be listed here.", emoji = "≡")
        return
    }
    val filtered = state.rows.filter { query.isBlank() || it.exercise.name.contains(query, ignoreCase = true) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
        item {
            ScreenTitle("Lifts", subtitle = "${state.rows.size} exercises, most recent first")
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                placeholder = { Text("Search lifts") }, singleLine = true,
                shape = CircleShape,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = palette.volt,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            )
        }
        items(filtered, key = { it.exercise.id }) { row ->
            val up = (row.trendDelta ?: 0.0) >= 0
            val trendColor = when {
                row.trendDelta == null || kotlin.math.abs(row.trendDelta) < 0.02 -> palette.muted
                up -> palette.progress
                else -> palette.regression
            }
            GainsCard(Modifier.fillMaxWidth().padding(bottom = 8.dp), onClick = { onOpen(row.exercise.id) }, contentPadding = Dp16.Tight) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(row.exercise.name, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "${Format.plural(row.sessions, "session")} · ${Dates.contextual(row.lastTrained, today)}" +
                                if (row.exercise.modality == Modality.WEIGHTED && row.exercise.isDumbbell) " · per dumbbell" else "",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Sparkline(row.trend, trendColor)
                    Spacer(Modifier.width(12.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(row.bestText, style = MaterialTheme.typography.titleSmall)
                        row.trendDelta?.let {
                            Text((if (it >= 0) "+" else "−") + Format.percent(kotlin.math.abs(it)), style = MaterialTheme.typography.labelMedium, color = trendColor)
                        }
                    }
                }
            }
        }
    }
}
