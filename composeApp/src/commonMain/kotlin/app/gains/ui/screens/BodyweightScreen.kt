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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.unit.dp
import app.gains.analysis.BodyweightAnalyzer
import app.gains.analysis.BodyweightPoint
import app.gains.analysis.Dates
import app.gains.analysis.ExerciseAnalysis
import app.gains.analysis.Format
import app.gains.analysis.TrainingData
import app.gains.data.BodyweightRepository
import app.gains.data.SettingsRepository
import app.gains.domain.BodyweightEntry
import app.gains.domain.Exercise
import app.gains.domain.Modality
import app.gains.domain.Units
import app.gains.domain.WeightUnit
import app.gains.ui.ScreenModel
import app.gains.ui.charts.ChartMath.x
import app.gains.ui.charts.ChartPoint
import app.gains.ui.charts.LineChart
import app.gains.ui.charts.LineSeries
import app.gains.ui.components.Dp16
import app.gains.ui.components.EmptyState
import app.gains.ui.components.GainsCard
import app.gains.ui.components.MetricTile
import app.gains.ui.components.PrimaryButton
import app.gains.ui.components.ScreenTitle
import app.gains.ui.components.SecondaryButton
import app.gains.ui.components.SectionHeader
import app.gains.ui.inject
import app.gains.ui.rememberScreenModel
import app.gains.ui.theme.GainsColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate

data class BodyweightState(
    val loading: Boolean = true,
    val unit: WeightUnit = WeightUnit.KG,
    val points: List<BodyweightPoint> = emptyList(),
    val weightedExercises: List<Exercise> = emptyList(),
    val overlayExercise: Exercise? = null,
    /** e1RM per session for the overlay exercise, in kg. */
    val overlayPoints: List<Pair<LocalDate, Double>> = emptyList(),
)

class BodyweightModel(
    private val repo: BodyweightRepository = inject(),
    trainingData: TrainingData = inject(),
    settings: SettingsRepository = inject(),
) : ScreenModel() {
    private val overlay = MutableStateFlow<String?>(null)

    val state: StateFlow<BodyweightState> = combine(repo.observe(), trainingData.snapshot, settings.observeUnit(), overlay) { entries, snapshot, unit, overlayId ->
        withContext(Dispatchers.Default) {
            val weighted = snapshot.trainedExercises.filter { it.modality == Modality.WEIGHTED }
            val exercise = weighted.firstOrNull { it.id == overlayId }
            BodyweightState(
                loading = false,
                unit = unit,
                points = BodyweightAnalyzer.withRollingAverage(entries),
                weightedExercises = weighted,
                overlayExercise = exercise,
                overlayPoints = exercise?.let { ex ->
                    ExerciseAnalysis.history(snapshot.sessions, ex).mapNotNull { p -> p.bestE1rm?.let { p.date to it.value } }
                } ?: emptyList(),
            )
        }
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), BodyweightState())

    fun setOverlay(exerciseId: String?) { overlay.value = exerciseId }

    fun add(date: LocalDate, weightKg: Double) { scope.launch { repo.upsert(BodyweightEntry(date, weightKg)) } }
    fun delete(date: LocalDate) { scope.launch { repo.delete(date) } }
}

@Composable
fun BodyweightScreen() {
    val model = rememberScreenModel { BodyweightModel() }
    val state by model.state.collectAsState()
    if (state.loading) return
    val today = Dates.today()
    val unit = state.unit
    val palette = GainsColors.palette
    var showEntry by remember { mutableStateOf(state.points.isEmpty()) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
        item {
            ScreenTitle("Bodyweight", subtitle = "Daily entries with a 7-day average", trailing = {
                TextButton(onClick = { showEntry = !showEntry }) { Text(if (showEntry) "Hide" else "+ Add", color = palette.volt) }
            })
            if (showEntry) GainsCard(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                EntryForm(unit, today, onAdd = { d, kg -> model.add(d, kg); showEntry = false })
            }
        }
        if (state.points.isEmpty()) {
            item { EmptyState("No bodyweight entries", "Log your weight here. A 7-day average smooths the daily noise, and you can overlay it on a lift's strength trend.", emoji = "♡") }
            return@LazyColumn
        }
        item {
            val last = state.points.last()
            val first = state.points.first()
            val change = last.rollingAverageKg - first.rollingAverageKg
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricTile("Latest", Format.weightValue(last.weightKg, unit), Modifier.weight(1f), caption = "${unit.label} · ${Dates.contextual(last.date, today)}", accent = palette.volt)
                MetricTile("7-day avg", Format.weightValue(last.rollingAverageKg, unit), Modifier.weight(1f), caption = unit.label)
                MetricTile("Change", (if (change >= 0) "+" else "") + Format.weightValue(change, unit), Modifier.weight(1f), caption = "since ${Dates.contextual(first.date, today)}", accent = if (change == 0.0) null else if (change > 0) palette.amber else palette.cyan)
            }
        }
        item {
            SectionHeader("Trend")
            GainsCard(Modifier.fillMaxWidth(), contentPadding = Dp16.Tight) {
                if (state.points.size == 1) {
                    Text("One entry so far. The chart appears after a second one.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    val daily = LineSeries(state.points.map { ChartPoint(it.date.x(), Units.display(it.weightKg, unit)) }, palette.muted, "Daily", showDots = true, dashed = true, smooth = false)
                    val avg = LineSeries(state.points.map { ChartPoint(it.date.x(), Units.display(it.rollingAverageKg, unit)) }, palette.volt, "7-day avg", showDots = false, fill = true)
                    val overlay = state.overlayExercise?.let { ex ->
                        LineSeries(state.overlayPoints.map { (d, v) -> ChartPoint(d.x(), Units.display(v, unit)) }, palette.amber, "${ex.name} e1RM", showDots = true, secondaryAxis = true)
                    }
                    LineChart(listOfNotNull(avg, daily, overlay), yLabel = { Format.number(it, 1) }, secondaryLabel = { Format.number(it, 0) })
                }
            }
            SectionHeader("Overlay a lift")
            OverlayPicker(state.weightedExercises, state.overlayExercise, onPick = { model.setOverlay(it?.id) })
        }
        item { SectionHeader("Entries") }
        items(state.points.asReversed(), key = { it.date.toString() }) { p ->
            GainsCard(Modifier.fillMaxWidth().padding(bottom = 6.dp), contentPadding = Dp16.Tight) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(Dates.contextual(p.date, today), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    Text(Format.weight(p.weightKg, unit, 1), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { model.delete(p.date) }) { Text("Delete", color = palette.coral) }
                }
            }
        }
    }
}

@Composable
private fun EntryForm(unit: WeightUnit, today: LocalDate, onAdd: (LocalDate, Double) -> Unit) {
    var dateText by remember { mutableStateOf(today.toString()) }
    var weightText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val palette = GainsColors.palette
    val colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = palette.volt, unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant)
    Column(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(dateText, { dateText = it }, label = { Text("Date (YYYY-MM-DD)") }, singleLine = true, modifier = Modifier.weight(1f), colors = colors, shape = MaterialTheme.shapes.medium)
            OutlinedTextField(weightText, { weightText = it }, label = { Text("Weight (${unit.label})") }, singleLine = true, modifier = Modifier.weight(1f), colors = colors, shape = MaterialTheme.shapes.medium)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
        Spacer(Modifier.height(12.dp))
        PrimaryButton("Save entry", onClick = {
            val date = runCatching { LocalDate.parse(dateText.trim()) }.getOrNull()
            val weight = weightText.trim().replace(',', '.').toDoubleOrNull()
            when {
                date == null -> error = "Enter the date as YYYY-MM-DD."
                weight == null || weight <= 0 -> error = "Enter a weight."
                else -> { error = null; onAdd(date, Units.fromDisplay(weight, unit)) }
            }
        })
    }
}

@Composable
private fun OverlayPicker(exercises: List<Exercise>, selected: Exercise?, onPick: (Exercise?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    if (exercises.isEmpty()) {
        Text("Import some sessions to overlay a lift.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Column {
        SecondaryButton(selected?.name ?: "Choose a lift", onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = MaterialTheme.shapes.medium) {
            DropdownMenuItem(text = { Text("None") }, onClick = { onPick(null); open = false })
            for (e in exercises) DropdownMenuItem(text = { Text(e.name) }, onClick = { onPick(e); open = false })
        }
        if (selected != null) {
            Text("Right axis: estimated 1RM per session (Epley: weight × (1 + reps/30)).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
    }
}
