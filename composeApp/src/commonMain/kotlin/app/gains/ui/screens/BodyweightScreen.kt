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
import androidx.compose.material3.HorizontalDivider
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
import app.gains.ui.components.ChooserRow
import app.gains.ui.components.DatePickerSheet
import app.gains.ui.components.Dp16
import app.gains.ui.components.EmptyState
import app.gains.ui.components.GainsCard
import app.gains.ui.components.MetricTile
import app.gains.ui.components.PrimaryButton
import app.gains.ui.components.ScreenTitle
import app.gains.ui.components.SecondaryButton
import app.gains.ui.components.SectionHeader
import app.gains.ui.components.WeightPickerSheet
import app.gains.ui.components.WheelWeight
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
import kotlinx.datetime.LocalDate

internal data class BodyweightState(
    val loading: Boolean = true,
    val unit: WeightUnit = WeightUnit.KG,
    val points: List<BodyweightPoint> = emptyList(),
    val weightedExercises: List<Exercise> = emptyList(),
    val overlayExercise: Exercise? = null,
    /** e1RM per session for the overlay exercise, in kg. */
    val overlayPoints: List<Pair<LocalDate, Double>> = emptyList(),
)

internal class BodyweightModel(
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
internal fun BodyweightScreen() {
    val model = rememberScreenModel { BodyweightModel() }
    val state by model.state.collectAsState()
    val strings = strings
    if (state.loading) return
    val today = Dates.today()
    val unit = state.unit
    val palette = GainsColors.palette
    var showEntry by remember { mutableStateOf(state.points.isEmpty()) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
        item {
            ScreenTitle(strings.bodyweightTitle, subtitle = strings.bodyweightSubtitle, trailing = {
                TextButton(onClick = { showEntry = !showEntry }) { Text(if (showEntry) strings.hide else strings.plusAdd, color = palette.volt) }
            })
            if (showEntry) GainsCard(Modifier.fillMaxWidth().padding(bottom = 12.dp), contentPadding = Dp16.Tight) {
                EntryForm(unit, today, lastWeightKg = state.points.lastOrNull()?.weightKg, onAdd = { d, kg -> model.add(d, kg); showEntry = false })
            }
        }
        if (state.points.isEmpty()) {
            item { EmptyState(strings.noBodyweightEntries, strings.noBodyweightEntriesBody, emoji = "♡") }
            return@LazyColumn
        }
        item {
            val last = state.points.last()
            val first = state.points.first()
            val change = last.rollingAverageKg - first.rollingAverageKg
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricTile(strings.latest, Format.weightValue(last.weightKg, unit), Modifier.weight(1f), caption = "${strings.unit(unit)} · ${strings.dateContextual(last.date, today)}", accent = palette.volt)
                MetricTile(strings.sevenDayAvg, Format.weightValue(last.rollingAverageKg, unit), Modifier.weight(1f), caption = strings.unit(unit))
                MetricTile(strings.changeLabel, (if (change >= 0) "+" else "") + Format.weightValue(change, unit), Modifier.weight(1f), caption = strings.sinceDate(strings.dateContextual(first.date, today)), accent = if (change == 0.0) null else if (change > 0) palette.amber else palette.cyan)
            }
        }
        item {
            SectionHeader(strings.trend)
            GainsCard(Modifier.fillMaxWidth(), contentPadding = Dp16.Tight) {
                if (state.points.size == 1) {
                    Text(strings.oneEntrySoFar, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    val daily = LineSeries(state.points.map { ChartPoint(it.date.x(), Units.display(it.weightKg, unit)) }, palette.muted, strings.daily, showDots = true, dashed = true, smooth = false)
                    val avg = LineSeries(state.points.map { ChartPoint(it.date.x(), Units.display(it.rollingAverageKg, unit)) }, palette.volt, strings.sevenDayAvg, showDots = false, fill = true)
                    val overlay = state.overlayExercise?.let { ex ->
                        LineSeries(state.overlayPoints.map { (d, v) -> ChartPoint(d.x(), Units.display(v, unit)) }, palette.amber, strings.e1rmOf(strings.exerciseName(ex)), showDots = true, secondaryAxis = true)
                    }
                    LineChart(listOfNotNull(avg, daily, overlay), yLabel = { Format.number(it, 1) }, secondaryLabel = { Format.number(it, 0) })
                }
            }
            SectionHeader(strings.overlayALift)
            OverlayPicker(state.weightedExercises, state.overlayExercise, onPick = { model.setOverlay(it?.id) })
        }
        item { SectionHeader(strings.entries) }
        items(state.points.asReversed(), key = { it.date.toString() }) { p ->
            GainsCard(Modifier.fillMaxWidth().padding(bottom = 6.dp), contentPadding = Dp16.Tight) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(strings.dateContextual(p.date, today), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    Text(strings.weight(p.weightKg, unit, 1), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { model.delete(p.date) }) { Text(strings.delete, color = palette.coral) }
                }
            }
        }
    }
}

/**
 * A date and a weight, as two rows that open a chooser each: a calendar for the day and wheels for
 * the weight, starting from the last entry so a typical day is a small nudge. Nothing is typed.
 */
@Composable
private fun EntryForm(unit: WeightUnit, today: LocalDate, lastWeightKg: Double?, onAdd: (LocalDate, Double) -> Unit) {
    var date by remember { mutableStateOf(today) }
    var weightText by remember { mutableStateOf(lastWeightKg?.let { Format.number(Units.display(it, unit), 2) } ?: "") }
    var datePickerOpen by remember { mutableStateOf(false) }
    var weightPickerOpen by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val strings = strings
    val weight = WheelWeight.parse(weightText).value
    val hairline = MaterialTheme.colorScheme.outlineVariant
    Column(Modifier.fillMaxWidth()) {
        ChooserRow(strings.date, if (date == today) strings.today else strings.dateContextual(date, today), onClick = { datePickerOpen = true })
        HorizontalDivider(color = hairline)
        ChooserRow(strings.weightLabel, if (weight > 0) Format.number(weight, 2) + " " + strings.unit(unit) else strings.notSet, onClick = { weightPickerOpen = true }, muted = weight <= 0)
        if (error) Text(strings.chooseAWeight, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(12.dp))
        PrimaryButton(strings.saveEntry, onClick = {
            if (weight <= 0) error = true
            else { error = false; onAdd(date, Units.fromDisplay(weight, unit)) }
        })
    }
    if (datePickerOpen) DatePickerSheet(date, onPick = { date = it }, onDismiss = { datePickerOpen = false })
    if (weightPickerOpen) WeightPickerSheet(
        value = weightText, unit = unit, title = strings.bodyweightTitle, subtitle = if (date == today) strings.today else strings.dateContextual(date, today),
        onPick = { weightText = it; error = false }, onDismiss = { weightPickerOpen = false },
        clearable = false, steps = WheelWeight.bodyweightSteps(unit),
    )
}

@Composable
private fun OverlayPicker(exercises: List<Exercise>, selected: Exercise?, onPick: (Exercise?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val strings = strings
    if (exercises.isEmpty()) {
        Text(strings.importToOverlay, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Column {
        SecondaryButton(selected?.let(strings::exerciseName) ?: strings.chooseALift, onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = MaterialTheme.shapes.medium) {
            DropdownMenuItem(text = { Text(strings.none) }, onClick = { onPick(null); open = false })
            for (e in exercises) DropdownMenuItem(text = { Text(strings.exerciseName(e)) }, onClick = { onPick(e); open = false })
        }
        if (selected != null) {
            Text(strings.rightAxisBlurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
    }
}
