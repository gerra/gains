package app.gains.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import app.gains.analysis.Dates.minusDays
import app.gains.analysis.ExerciseAnalysis
import app.gains.analysis.ExerciseSessionPoint
import app.gains.analysis.ExerciseSummary
import app.gains.analysis.Format
import app.gains.analysis.TrainingData
import app.gains.analysis.WorkingSets
import app.gains.data.ExerciseRepository
import app.gains.data.SettingsRepository
import app.gains.domain.Exercise
import app.gains.domain.Modality
import app.gains.domain.Units
import app.gains.domain.WeightUnit
import app.gains.ui.ScreenModel
import app.gains.ui.charts.ChartMath.x
import app.gains.ui.charts.ChartPoint
import app.gains.ui.charts.LineChart
import app.gains.ui.charts.LineSeries
import app.gains.ui.components.ChipRow
import app.gains.ui.components.EmptyState
import app.gains.ui.components.SectionHeader
import app.gains.ui.components.StatCard
import app.gains.ui.inject
import app.gains.ui.rememberScreenModel
import app.gains.ui.theme.GainsColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Window(val label: String, val days: Int?) { M3("3M", 90), M6("6M", 180), Y1("1Y", 365), ALL("All", null) }

data class ExerciseDetailState(
    val loading: Boolean = true,
    val exercise: Exercise? = null,
    val unit: WeightUnit = WeightUnit.KG,
    val window: Window = Window.M6,
    /** Points inside the selected window. */
    val points: List<ExerciseSessionPoint> = emptyList(),
    val allPoints: List<ExerciseSessionPoint> = emptyList(),
    val summary: ExerciseSummary? = null,
    val workingSetRatio: Double = WorkingSets.DEFAULT_RATIO,
    val hasOverride: Boolean = false,
)

class ExerciseDetailModel(
    private val exerciseId: String,
    trainingData: TrainingData = inject(),
    private val exercises: ExerciseRepository = inject(),
    settings: SettingsRepository = inject(),
) : ScreenModel() {
    private val window = MutableStateFlow(Window.M6)

    val state: StateFlow<ExerciseDetailState> = combine(
        trainingData.snapshot, settings.observeUnit(), exercises.observeWorkingSetRatios(), window,
    ) { snapshot, unit, ratios, window ->
        withContext(Dispatchers.Default) {
            val exercise = snapshot.exercisesById[exerciseId] ?: return@withContext ExerciseDetailState(loading = false)
            val today = Dates.today()
            val all = ExerciseAnalysis.history(snapshot.sessions, exercise)
            val cutoff = window.days?.let { today.minusDays(it) }
            ExerciseDetailState(
                loading = false,
                exercise = exercise,
                unit = unit,
                window = window,
                points = if (cutoff == null) all else all.filter { it.date >= cutoff },
                allPoints = all,
                summary = ExerciseSummary.of(all, today),
                workingSetRatio = ratios[exerciseId] ?: WorkingSets.DEFAULT_RATIO,
                hasOverride = ratios.containsKey(exerciseId),
            )
        }
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), ExerciseDetailState())

    fun setWindow(w: Window) { window.value = w }

    fun setWorkingSetRatio(ratio: Double?) {
        scope.launch { exercises.setWorkingSetRatio(exerciseId, ratio) }
    }
}

@Composable
fun ExerciseDetailScreen(exerciseId: String) {
    val model = rememberScreenModel(exerciseId) { ExerciseDetailModel(exerciseId) }
    val state by model.state.collectAsState()
    val exercise = state.exercise
    if (state.loading) return
    if (exercise == null) {
        EmptyState("Unknown exercise", "This exercise no longer exists. It may have been merged into another one.")
        return
    }
    val today = Dates.today()
    val unit = state.unit
    val summary = state.summary

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        item {
            Text(exercise.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            val subtitle = buildList {
                add(exercise.modality.name.lowercase().replaceFirstChar { it.uppercase() })
                if (exercise.isDumbbell) add("weights are per dumbbell")
                if (exercise.muscleGroups.isNotEmpty()) add(exercise.muscleGroups.joinToString { it.group.displayName })
            }.joinToString(" · ")
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.allPoints.isEmpty()) {
            item { EmptyState("No sessions", "This exercise has not been trained in any imported session.") }
            return@LazyColumn
        }
        item {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val allTime = summary?.allTimeBest
                val current = summary?.currentBest
                StatCard(
                    "All-time best",
                    allTime?.best?.describe(exercise.modality, unit) ?: "-",
                    Modifier.weight(1f),
                    caption = allTime?.let { Dates.contextual(it.date, today) + metricCaption(it, exercise.modality, unit) },
                )
                StatCard(
                    "Current best",
                    current?.best?.describe(exercise.modality, unit) ?: "-",
                    Modifier.weight(1f),
                    caption = current?.let { Dates.contextual(it.date, today) + metricCaption(it, exercise.modality, unit) },
                )
            }
            Spacer(Modifier.height(8.dp))
            val gap = summary?.gapFraction
            val gapText = when {
                gap == null -> "-"
                gap <= 0.0 -> "At your best"
                else -> "Down ${Format.percent(gap)} from all-time best"
            }
            Text(gapText, style = MaterialTheme.typography.bodyMedium, color = if (gap != null && gap > 0.05) GainsColors.Regression else GainsColors.Progress)
        }
        item {
            SectionHeader("Window")
            ChipRow(Window.entries, state.window, { it.label }, { model.setWindow(it) })
        }
        if (state.points.isEmpty()) {
            item { EmptyState("Nothing in this window", "Pick a longer window to see the ${state.allPoints.size} sessions on record.") }
            return@LazyColumn
        }
        if (state.points.size == 1) {
            item {
                Text("One session in this window. Charts need at least two points to show a trend.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        }
        item {
            when (exercise.modality) {
                Modality.WEIGHTED -> {
                    SectionHeader("Estimated 1RM (Epley, working sets)")
                    val pts = state.points.mapNotNull { p -> p.bestE1rm?.let { ChartPoint(p.date.x(), Units.display(it.value, unit)) } }
                    if (pts.isEmpty()) Text("No weighted working sets in this window.", style = MaterialTheme.typography.bodySmall)
                    else LineChart(listOf(LineSeries(pts, MaterialTheme.colorScheme.primary, "e1RM")), yLabel = { Format.number(it, 0) })
                    SectionHeader("Top set weight")
                    val top = state.points.mapNotNull { p -> p.topSetWeightKg?.let { ChartPoint(p.date.x(), Units.display(it, unit)) } }
                    if (top.isNotEmpty()) LineChart(listOf(LineSeries(top, GainsColors.Consistency, "Top set")), yLabel = { Format.number(it, 0) })
                    SectionHeader("Volume per session (Σ weight × reps, working sets)")
                    val vol = state.points.map { ChartPoint(it.date.x(), Units.display(it.totalVolumeKg, unit)) }
                    LineChart(listOf(LineSeries(vol, GainsColors.Stall, "Total volume", showDots = false)), yMinZero = true, yLabel = { Format.number(it, 0) })
                }
                else -> {
                    SectionHeader("Best ${ExerciseAnalysis.metricLabel(exercise.modality)} per session")
                    val pts = state.points.mapNotNull { p -> p.best?.let { ChartPoint(p.date.x(), it.value) } }
                    if (pts.isNotEmpty()) LineChart(listOf(LineSeries(pts, MaterialTheme.colorScheme.primary, ExerciseAnalysis.metricLabel(exercise.modality))), yMinZero = true)
                }
            }
        }
        if (exercise.modality == Modality.WEIGHTED) {
            item {
                SectionHeader("Working-set rule")
                WorkingSetRuleEditor(state.workingSetRatio, state.hasOverride, onChange = { model.setWorkingSetRatio(it) })
            }
        }
        item { SectionHeader("Sessions") }
        items(state.points.asReversed()) { p ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(Dates.contextual(p.date, today), fontWeight = FontWeight.Medium)
                        Text(p.best?.describe(exercise.modality, unit) ?: "-", style = MaterialTheme.typography.bodyMedium)
                    }
                    val details = buildList {
                        if (exercise.modality == Modality.WEIGHTED) {
                            p.bestE1rm?.let { add("e1RM ${Format.weight(it.value, unit, 1)}") }
                            p.bestSetVolumeKg?.let { add("best set ${Format.weight(it, unit, 0)}") }
                            add("volume ${Format.weight(p.totalVolumeKg, unit, 0)}")
                        }
                        add("${p.workingSetCount}/${p.setCount} working sets")
                    }
                    Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    p.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

private fun metricCaption(p: ExerciseSessionPoint, modality: Modality, unit: WeightUnit): String {
    val e1rm = p.bestE1rm ?: return ""
    return if (modality == Modality.WEIGHTED) " · e1RM ${Format.weight(e1rm.value, unit, 1)}" else ""
}

@Composable
private fun WorkingSetRuleEditor(ratio: Double, hasOverride: Boolean, onChange: (Double?) -> Unit) {
    var value by remember(ratio) { mutableStateOf(ratio.toFloat()) }
    Text(
        "Sets at or above ${Format.percent(value.toDouble())} of the session's top weight count as working sets." +
            if (hasOverride) "" else " (default ${Format.percent(WorkingSets.DEFAULT_RATIO)})",
        style = MaterialTheme.typography.bodySmall,
    )
    Slider(
        value = value,
        onValueChange = { value = it },
        onValueChangeFinished = { onChange(((value * 20).toInt() / 20.0)) },
        valueRange = 0.5f..1f,
        steps = 9,
    )
    if (hasOverride) TextButton(onClick = { onChange(null) }) { Text("Reset to default") }
}
