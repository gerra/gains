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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import app.gains.ui.charts.formatAxis
import app.gains.ui.components.ChipRow
import app.gains.ui.components.DeltaBadge
import app.gains.ui.components.Dp16
import app.gains.ui.components.EmptyState
import app.gains.ui.components.GainsCard
import app.gains.ui.components.MetricTile
import app.gains.ui.components.Pill
import app.gains.ui.components.SectionHeader
import app.gains.ui.i18n.strings
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

internal enum class Window(val days: Int?) { M3(90), M6(180), Y1(365), ALL(null) }

internal data class ExerciseDetailState(
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

internal class ExerciseDetailModel(
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
internal fun ExerciseDetailScreen(exerciseId: String, onOpenSession: (String) -> Unit = {}) {
    val model = rememberScreenModel(exerciseId) { ExerciseDetailModel(exerciseId) }
    val state by model.state.collectAsState()
    val exercise = state.exercise
    val strings = strings
    if (state.loading) return
    if (exercise == null) {
        EmptyState(strings.unknownExercise, strings.unknownExerciseBody, emoji = "?")
        return
    }
    val today = Dates.today()
    val unit = state.unit
    val summary = state.summary
    val palette = GainsColors.palette

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
        item {
            Text(strings.exerciseName(exercise), style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(strings.modality(exercise.modality), palette.cyan)
                if (exercise.isDumbbell) Pill(strings.perDumbbell, palette.amber)
            }
            if (exercise.muscleGroups.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(exercise.muscleGroups.joinToString(" · ") { strings.muscleGroup(it.group) }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (state.allPoints.isEmpty()) {
            item { EmptyState(strings.noSessions, strings.noSessionsForExercise) }
            return@LazyColumn
        }
        item {
            Spacer(Modifier.height(16.dp))
            val allTime = summary?.allTimeBest
            val current = summary?.currentBest
            val gap = summary?.gapFraction
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricTile(
                    strings.currentBest,
                    current?.best?.describe(exercise.modality, unit, strings) ?: "-",
                    Modifier.weight(1f),
                    caption = current?.let { strings.dateContextual(it.date, today) + metricCaption(it, exercise.modality, unit, strings) },
                    accent = if (gap != null && gap > 0.05) palette.regression else palette.volt,
                    onClick = current?.let { p -> { onOpenSession(p.sessionId) } },
                )
                MetricTile(
                    strings.allTimeBest,
                    allTime?.best?.describe(exercise.modality, unit, strings) ?: "-",
                    Modifier.weight(1f),
                    caption = allTime?.let { strings.dateContextual(it.date, today) + metricCaption(it, exercise.modality, unit, strings) },
                    onClick = allTime?.let { p -> { onOpenSession(p.sessionId) } },
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    gap == null -> {}
                    gap <= 0.0 -> Pill(strings.atYourBest, palette.volt, filled = true)
                    else -> { DeltaBadge(-gap); Spacer(Modifier.padding(4.dp)); Text(strings.fromAllTimeBest, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        item {
            SectionHeader(strings.window)
            ChipRow(Window.entries, state.window, { strings.windowLabel(it.days) }, { model.setWindow(it) })
        }
        if (state.points.isEmpty()) {
            item { EmptyState(strings.nothingInWindow, strings.pickALongerWindow(state.allPoints.size)) }
            return@LazyColumn
        }
        if (state.points.size == 1) {
            item {
                Text(strings.oneSessionInWindow, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        }
        item {
            when (exercise.modality) {
                Modality.WEIGHTED -> {
                    SectionHeader(strings.e1rmSection)
                    val pts = state.points.mapNotNull { p -> p.bestE1rm?.let { ChartPoint(p.date.x(), Units.display(it.value, unit)) } }
                    ChartCard {
                        if (pts.isEmpty()) Text(strings.noWeightedSetsInWindow, style = MaterialTheme.typography.bodySmall)
                        else LineChart(listOf(LineSeries(pts, palette.volt, strings.e1rm, fill = true)), yLabel = { formatAxis(it) })
                    }
                    SectionHeader(strings.topSetWeight)
                    val top = state.points.mapNotNull { p -> p.topSetWeightKg?.let { ChartPoint(p.date.x(), Units.display(it, unit)) } }
                    if (top.isNotEmpty()) ChartCard { LineChart(listOf(LineSeries(top, palette.cyan, strings.topSet, fill = true, smooth = false)), height = 160.dp, yLabel = { formatAxis(it) }) }
                    SectionHeader(strings.volumePerSession)
                    val vol = state.points.map { ChartPoint(it.date.x(), Units.display(it.totalVolumeKg, unit)) }
                    ChartCard { LineChart(listOf(LineSeries(vol, palette.violet, strings.totalVolume, showDots = false, fill = true)), height = 160.dp, yMinZero = true, yLabel = { Format.number(it, 0) }) }
                }
                else -> {
                    SectionHeader(strings.bestMetricPerSession(exercise.modality))
                    val pts = state.points.mapNotNull { p -> p.best?.let { ChartPoint(p.date.x(), it.value) } }
                    if (pts.isNotEmpty()) ChartCard { LineChart(listOf(LineSeries(pts, palette.volt, strings.metricLabel(exercise.modality), fill = true)), yMinZero = true) }
                }
            }
        }
        if (exercise.modality == Modality.WEIGHTED) {
            item {
                SectionHeader(strings.workingSetRule)
                GainsCard(Modifier.fillMaxWidth()) {
                    WorkingSetRuleEditor(state.workingSetRatio, state.hasOverride, onChange = { model.setWorkingSetRatio(it) })
                }
            }
        }
        item { SectionHeader(strings.sessionsTapToOpen) }
        items(state.points.asReversed(), key = { it.sessionId }) { p ->
            GainsCard(Modifier.fillMaxWidth().padding(bottom = 8.dp), onClick = { onOpenSession(p.sessionId) }, contentPadding = Dp16.Tight) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(strings.dateContextual(p.date, today), style = MaterialTheme.typography.titleSmall)
                    Text(p.best?.describe(exercise.modality, unit, strings) ?: "-", style = MaterialTheme.typography.titleSmall, color = palette.volt)
                }
                val details = buildList {
                    if (exercise.modality == Modality.WEIGHTED) {
                        p.bestE1rm?.let { add(strings.e1rmValue(strings.weight(it.value, unit, 1))) }
                        p.bestSetVolumeKg?.let { add(strings.bestSetVolume(strings.weight(it, unit, 0))) }
                        add(strings.volumeValue(strings.weight(p.totalVolumeKg, unit, 0)))
                    }
                    add(strings.workingSetsOf(p.workingSetCount, p.setCount))
                }
                Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                p.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
            }
        }
    }
}

@Composable
internal fun ChartCard(content: @Composable () -> Unit) {
    GainsCard(Modifier.fillMaxWidth(), contentPadding = Dp16.Tight) { content() }
}

private fun metricCaption(p: ExerciseSessionPoint, modality: Modality, unit: WeightUnit, strings: app.gains.i18n.Strings): String {
    val e1rm = p.bestE1rm ?: return ""
    return if (modality == Modality.WEIGHTED) " · " + strings.e1rmValue(strings.weight(e1rm.value, unit, 1)) else ""
}

@Composable
private fun WorkingSetRuleEditor(ratio: Double, hasOverride: Boolean, onChange: (Double?) -> Unit) {
    var value by remember(ratio) { mutableStateOf(ratio.toFloat()) }
    val palette = GainsColors.palette
    val strings = strings
    Text(
        strings.workingSetRuleBlurb(Format.percent(value.toDouble()), if (hasOverride) null else Format.percent(WorkingSets.DEFAULT_RATIO)),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Slider(
        value = value,
        onValueChange = { value = it },
        onValueChangeFinished = { onChange(((value * 20).toInt() / 20.0)) },
        valueRange = 0.5f..1f,
        steps = 9,
        colors = SliderDefaults.colors(thumbColor = palette.volt, activeTrackColor = palette.volt, inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    )
    if (hasOverride) TextButton(onClick = { onChange(null) }) { Text(strings.resetToDefault) }
}
