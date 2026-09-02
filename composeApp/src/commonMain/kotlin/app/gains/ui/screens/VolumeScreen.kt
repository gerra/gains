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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.gains.analysis.Dates
import app.gains.analysis.Dates.minusDays
import app.gains.analysis.Format
import app.gains.analysis.TrainingData
import app.gains.analysis.VolumeAnalyzer
import app.gains.analysis.VolumeStatus
import app.gains.analysis.WeekVolume
import app.gains.domain.MuscleGroup
import app.gains.ui.ScreenModel
import app.gains.ui.charts.Legend
import app.gains.ui.charts.StackedBar
import app.gains.ui.charts.StackedBarChart
import app.gains.ui.components.ChipRow
import app.gains.ui.components.Dot
import app.gains.ui.components.Dp16
import app.gains.ui.components.EmptyState
import app.gains.ui.components.GainsCard
import app.gains.ui.components.Meter
import app.gains.ui.components.MetricTile
import app.gains.ui.components.Pill
import app.gains.ui.components.ScreenTitle
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
import kotlinx.coroutines.withContext

data class VolumeState(
    val loading: Boolean = true,
    val hasData: Boolean = false,
    val weeks: List<WeekVolume> = emptyList(),
    val currentWeek: WeekVolume? = null,
    val span: Int = 12,
)

class VolumeModel(trainingData: TrainingData = inject()) : ScreenModel() {
    private val span = MutableStateFlow(12)
    val state: StateFlow<VolumeState> = combine(trainingData.snapshot, span) { snapshot, span ->
        withContext(Dispatchers.Default) {
            val today = Dates.today()
            if (snapshot.isEmpty) VolumeState(loading = false, hasData = false, span = span)
            else VolumeState(
                loading = false,
                hasData = true,
                weeks = VolumeAnalyzer.weekly(snapshot.sessions, snapshot.exercisesById, Dates.weekStart(today).minusDays((span - 1) * 7), today),
                currentWeek = VolumeAnalyzer.currentWeek(snapshot.sessions, snapshot.exercisesById, today),
                span = span,
            )
        }
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), VolumeState())

    fun setSpan(weeks: Int) { span.value = weeks }
}

@Composable
fun MuscleGroup.color(): Color = GainsColors.palette.series[ordinal % GainsColors.palette.series.size]

@Composable
fun VolumeStatus.color(): Color {
    val p = GainsColors.palette
    return when (this) {
        VolumeStatus.LOW -> p.amber
        VolumeStatus.HIGH -> p.coral
        VolumeStatus.OK -> p.volt
        VolumeStatus.NONE -> p.muted
    }
}

@Composable
fun VolumeScreen() {
    val model = rememberScreenModel { VolumeModel() }
    val state by model.state.collectAsState()
    if (state.loading) return
    if (!state.hasData) {
        EmptyState("No volume yet", "Weekly working sets per muscle group appear here once you import sessions.", emoji = "▮")
        return
    }
    val palette = GainsColors.palette
    val groupsUsed = MuscleGroup.entries.filter { g -> state.weeks.any { (it.sets[g] ?: 0.0) > 0 } }
    val current = state.currentWeek
    val lastFull = state.weeks.dropLast(1).lastOrNull()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
        item {
            ScreenTitle("Volume", subtitle = "Working sets per muscle group, per week")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricTile("This week", Format.number(current?.total ?: 0.0, 0), Modifier.weight(1f), caption = "sets so far", accent = palette.volt)
                MetricTile("Last week", Format.number(lastFull?.total ?: 0.0, 0), Modifier.weight(1f), caption = lastFull?.let { "w/c ${Dates.short(it.weekStart)}" })
                MetricTile("Avg", Format.number(state.weeks.dropLast(1).map { it.total }.average().takeIf { !it.isNaN() } ?: 0.0, 0), Modifier.weight(1f), caption = "${state.span}-week")
            }
        }
        item {
            SectionHeader("Trend", action = { ChipRow(listOf(8, 12, 26, 52), state.span, { "${it}w" }, { model.setSpan(it) }) })
            GainsCard(Modifier.fillMaxWidth(), contentPadding = Dp16.Tight) {
                val bars = state.weeks.map { w ->
                    StackedBar(Dates.short(w.weekStart), groupsUsed.map { g -> g.color() to (w.sets[g] ?: 0.0) })
                }
                StackedBarChart(bars, labelEvery = maxOf(1, bars.size / 5))
                Legend(groupsUsed.map { it.displayName to it.color() })
            }
            Text(
                "Each set counts fully for its primary muscles and half for secondary ones. Warm-ups and cardio don't count.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
            )
        }
        item {
            SectionHeader("This week" + (current?.let { " · from ${Dates.short(it.weekStart)}" } ?: ""))
        }
        if (current != null) {
            items(MuscleGroup.entries.sortedByDescending { current.sets[it] ?: 0.0 }) { g ->
                val sets = current.sets[g] ?: 0.0
                val status = VolumeAnalyzer.status(sets)
                GainsCard(Modifier.fillMaxWidth().padding(bottom = 6.dp), contentPadding = Dp16.Tight) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dot(g.color())
                        Spacer(Modifier.width(10.dp))
                        Text(g.displayName, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        Text(Format.number(sets, 1), style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.width(10.dp))
                        Pill(
                            when (status) {
                                VolumeStatus.NONE -> "none"
                                VolumeStatus.LOW -> "under ${VolumeAnalyzer.MAINTENANCE_SETS.toInt()}"
                                VolumeStatus.OK -> "on target"
                                VolumeStatus.HIGH -> "over ${VolumeAnalyzer.JUNK_SETS.toInt()}"
                            },
                            status.color(),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Meter((sets / VolumeAnalyzer.JUNK_SETS).toFloat(), status.color(), Modifier.fillMaxWidth(), marker = (VolumeAnalyzer.MAINTENANCE_SETS / VolumeAnalyzer.JUNK_SETS).toFloat())
                }
            }
            item {
                Text(
                    "Bars run to ${VolumeAnalyzer.JUNK_SETS.toInt()} sets; the tick marks ${VolumeAnalyzer.MAINTENANCE_SETS.toInt()}. Under ${VolumeAnalyzer.MAINTENANCE_SETS.toInt()} sets/week is maintenance territory; over ${VolumeAnalyzer.JUNK_SETS.toInt()} is likely junk volume.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
    }
}
