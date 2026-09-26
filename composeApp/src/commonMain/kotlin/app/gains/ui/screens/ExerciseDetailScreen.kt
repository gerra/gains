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
import app.gains.analysis.RecordHolder
import app.gains.analysis.RecordKind
import app.gains.analysis.Records
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
import app.gains.ui.components.KeyValueRow
import app.gains.ui.components.MetricTile
import app.gains.ui.components.Pill
import app.gains.ui.components.SectionHeader
import app.gains.ui.demo.ExerciseDemoCard
import app.gains.resources.Res
import app.gains.resources.*
import app.gains.ui.i18n.*
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
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
    /** The standing records, in the order the lift keeps them. */
    val records: List<RecordHolder> = emptyList(),
    /** The heaviest set for at least 1, 2, 3, 5… reps, for a weighted lift. */
    val repMaxes: Map<Int, RecordHolder> = emptyMap(),
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
                records = Records.standing(snapshot.sessions, snapshot.exercisesById)[exerciseId]?.let { standing -> Records.kinds(exercise.modality).mapNotNull { standing[it] } }.orEmpty(),
                repMaxes = if (exercise.modality == Modality.WEIGHTED) Records.repMaxes(snapshot.sessions, exerciseId) else emptyMap(),
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
    if (state.loading) return
    if (exercise == null) {
        EmptyState(stringResource(Res.string.unknown_exercise), stringResource(Res.string.unknown_exercise_body), emoji = "?")
        return
    }
    val today = Dates.today()
    val unit = state.unit
    val labels = unitLabels()
    val summary = state.summary
    val palette = GainsColors.palette

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
        item {
            Text(exercise.displayName(), style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(exercise.modality.label(), palette.cyan)
                if (exercise.isDumbbell) Pill(stringResource(Res.string.per_dumbbell), palette.amber)
            }
            if (exercise.muscleGroups.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(exercise.muscleGroups.map { it.group.label() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            SectionHeader(stringResource(Res.string.how_to_do_it))
            ExerciseDemoCard(exercise)
        }
        if (state.allPoints.isEmpty()) {
            item { EmptyState(stringResource(Res.string.no_sessions), stringResource(Res.string.no_sessions_for_exercise)) }
            return@LazyColumn
        }
        item {
            Spacer(Modifier.height(16.dp))
            val allTime = summary?.allTimeBest
            val current = summary?.currentBest
            val gap = summary?.gapFraction
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricTile(
                    stringResource(Res.string.current_best),
                    current?.best?.describe(exercise.modality, unit, labels) ?: "-",
                    Modifier.weight(1f),
                    caption = current?.let { dateContextual(it.date, today) + metricCaption(it, exercise.modality, unit) },
                    accent = if (gap != null && gap > 0.05) palette.regression else palette.volt,
                    onClick = current?.let { p -> { onOpenSession(p.sessionId) } },
                )
                MetricTile(
                    stringResource(Res.string.all_time_best),
                    allTime?.best?.describe(exercise.modality, unit, labels) ?: "-",
                    Modifier.weight(1f),
                    caption = allTime?.let { dateContextual(it.date, today) + metricCaption(it, exercise.modality, unit) },
                    onClick = allTime?.let { p -> { onOpenSession(p.sessionId) } },
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    gap == null -> {}
                    gap <= 0.0 -> Pill(stringResource(Res.string.at_your_best), palette.volt, filled = true)
                    else -> { DeltaBadge(-gap); Spacer(Modifier.padding(4.dp)); Text(stringResource(Res.string.from_all_time_best), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        if (state.records.isNotEmpty()) {
            item {
                SectionHeader(stringResource(Res.string.records_section))
                GainsCard(Modifier.fillMaxWidth(), contentPadding = Dp16.Tight) {
                    for (holder in state.records) {
                        KeyValueRow(
                            holder.kind.label(),
                            stringResource(Res.string.record_since, recordText(holder, exercise.modality, unit), dateContextual(holder.date, today)),
                            Modifier.clickable { onOpenSession(holder.sessionId) },
                            valueColor = palette.volt,
                        )
                    }
                }
                if (state.repMaxes.isNotEmpty()) {
                    SectionHeader(stringResource(Res.string.rep_maxes))
                    GainsCard(Modifier.fillMaxWidth(), contentPadding = Dp16.Tight) {
                        Text(stringResource(Res.string.rep_maxes_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        for ((reps, holder) in state.repMaxes.entries.sortedBy { it.key }) {
                            KeyValueRow(
                                stringResource(Res.string.rep_max_label, reps),
                                stringResource(Res.string.record_since, weightText(holder.value, unit), dateContextual(holder.date, today)),
                                Modifier.clickable { onOpenSession(holder.sessionId) },
                            )
                        }
                    }
                }
            }
        }
        item {
            SectionHeader(stringResource(Res.string.window))
            ChipRow(Window.entries, state.window, { windowLabel(it.days) }, { model.setWindow(it) })
        }
        if (state.points.isEmpty()) {
            item { EmptyState(stringResource(Res.string.nothing_in_window), pluralStringResource(Res.plurals.pick_a_longer_window, state.allPoints.size, state.allPoints.size)) }
            return@LazyColumn
        }
        if (state.points.size == 1) {
            item {
                Text(stringResource(Res.string.one_session_in_window), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        }
        item {
            when (exercise.modality) {
                Modality.WEIGHTED -> {
                    SectionHeader(stringResource(Res.string.e1rm_section))
                    val pts = state.points.mapNotNull { p -> p.bestE1rm?.let { ChartPoint(p.date.x(), Units.display(it.value, unit)) } }
                    ChartCard {
                        if (pts.isEmpty()) Text(stringResource(Res.string.no_weighted_sets_in_window), style = MaterialTheme.typography.bodySmall)
                        else LineChart(listOf(LineSeries(pts, palette.volt, stringResource(Res.string.e1rm), fill = true)), yLabel = { formatAxis(it) })
                    }
                    SectionHeader(stringResource(Res.string.top_set_weight))
                    val top = state.points.mapNotNull { p -> p.topSetWeightKg?.let { ChartPoint(p.date.x(), Units.display(it, unit)) } }
                    if (top.isNotEmpty()) ChartCard { LineChart(listOf(LineSeries(top, palette.cyan, stringResource(Res.string.top_set), fill = true, smooth = false)), height = 160.dp, yLabel = { formatAxis(it) }) }
                    SectionHeader(stringResource(Res.string.volume_per_session))
                    val vol = state.points.map { ChartPoint(it.date.x(), Units.display(it.totalVolumeKg, unit)) }
                    ChartCard { LineChart(listOf(LineSeries(vol, palette.violet, stringResource(Res.string.total_volume), showDots = false, fill = true)), height = 160.dp, yMinZero = true, yLabel = { Format.number(it, 0) }) }
                }
                else -> {
                    SectionHeader(exercise.modality.bestMetricHeading())
                    val pts = state.points.mapNotNull { p -> p.best?.let { ChartPoint(p.date.x(), it.value) } }
                    if (pts.isNotEmpty()) ChartCard { LineChart(listOf(LineSeries(pts, palette.volt, exercise.modality.metricLabel(), fill = true)), yMinZero = true) }
                }
            }
        }
        if (exercise.modality == Modality.WEIGHTED) {
            item {
                SectionHeader(stringResource(Res.string.working_set_rule))
                GainsCard(Modifier.fillMaxWidth()) {
                    WorkingSetRuleEditor(state.workingSetRatio, state.hasOverride, onChange = { model.setWorkingSetRatio(it) })
                }
            }
        }
        item { SectionHeader(stringResource(Res.string.sessions_tap_to_open)) }
        items(state.points.asReversed(), key = { it.sessionId }) { p ->
            GainsCard(Modifier.fillMaxWidth().padding(bottom = 8.dp), onClick = { onOpenSession(p.sessionId) }, contentPadding = Dp16.Tight) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(dateContextual(p.date, today), style = MaterialTheme.typography.titleSmall)
                    Text(p.best?.describe(exercise.modality, unit, labels) ?: "-", style = MaterialTheme.typography.titleSmall, color = palette.volt)
                }
                val details = buildList {
                    if (exercise.modality == Modality.WEIGHTED) {
                        p.bestE1rm?.let { add(stringResource(Res.string.e1rm_value, weightText(it.value, unit, 1))) }
                        p.bestSetVolumeKg?.let { add(stringResource(Res.string.best_set_volume, weightText(it, unit, 0))) }
                        add(stringResource(Res.string.volume_value, weightText(p.totalVolumeKg, unit, 0)))
                    }
                    add(stringResource(Res.string.working_sets_of, p.workingSetCount, p.setCount))
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

@Composable
private fun metricCaption(p: ExerciseSessionPoint, modality: Modality, unit: WeightUnit): String {
    val e1rm = p.bestE1rm ?: return ""
    return if (modality == Modality.WEIGHTED) " · " + stringResource(Res.string.e1rm_value, weightText(e1rm.value, unit, 1)) else ""
}

@Composable
private fun WorkingSetRuleEditor(ratio: Double, hasOverride: Boolean, onChange: (Double?) -> Unit) {
    var value by remember(ratio) { mutableStateOf(ratio.toFloat()) }
    val palette = GainsColors.palette
    Text(
        workingSetRuleBlurb(Format.percent(value.toDouble()), if (hasOverride) null else Format.percent(WorkingSets.DEFAULT_RATIO)),
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
    if (hasOverride) TextButton(onClick = { onChange(null) }) { Text(stringResource(Res.string.reset_to_default)) }
}
