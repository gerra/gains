package app.gains.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
import app.gains.ui.charts.ReferenceLine
import app.gains.ui.charts.StackedBar
import app.gains.ui.charts.StackedBarChart
import app.gains.ui.components.ChipRow
import app.gains.ui.components.EmptyState
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

fun MuscleGroup.color(): Color = GainsColors.Palette[ordinal % GainsColors.Palette.size]

fun VolumeStatus.color(): Color = when (this) {
    VolumeStatus.LOW -> GainsColors.Low
    VolumeStatus.HIGH -> GainsColors.High
    VolumeStatus.OK -> GainsColors.Ok
    VolumeStatus.NONE -> GainsColors.Muted
}

@Composable
fun VolumeScreen() {
    val model = rememberScreenModel { VolumeModel() }
    val state by model.state.collectAsState()
    if (state.loading) return
    if (!state.hasData) {
        EmptyState("No volume yet", "Weekly working sets per muscle group appear here once you import sessions.")
        return
    }
    val groupsUsed = MuscleGroup.entries.filter { g -> state.weeks.any { (it.sets[g] ?: 0.0) > 0 } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        item {
            SectionHeader("Working sets per week")
            ChipRow(listOf(8, 12, 26, 52), state.span, { "${it}w" }, { model.setSpan(it) })
            Spacer(Modifier.size(8.dp))
            val bars = state.weeks.map { w ->
                StackedBar(Dates.short(w.weekStart), groupsUsed.map { g -> g.color() to (w.sets[g] ?: 0.0) })
            }
            StackedBarChart(
                bars,
                references = emptyList(),
                labelEvery = maxOf(1, bars.size / 6),
            )
            Legend(groupsUsed.map { it.displayName to it.color() })
            Text(
                "Each set counts fully for its primary muscles and half for secondary ones. Warm-ups and cardio don't count.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
            )
        }
        item {
            val cw = state.currentWeek
            SectionHeader("This week" + (cw?.let { " (from ${Dates.short(it.weekStart)})" } ?: ""))
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text("Muscle group", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                Text("Sets", Modifier.width(56.dp), style = MaterialTheme.typography.labelMedium)
                Text("Status", Modifier.width(96.dp), style = MaterialTheme.typography.labelMedium)
            }
            HorizontalDivider()
        }
        val current = state.currentWeek
        if (current != null) {
            items(MuscleGroup.entries.sortedByDescending { current.sets[it] ?: 0.0 }) { g ->
                val sets = current.sets[g] ?: 0.0
                val status = VolumeAnalyzer.status(sets)
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.size(10.dp).background(g.color(), CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(g.displayName, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(Format.number(sets, 1), Modifier.width(56.dp), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(
                        when (status) {
                            VolumeStatus.NONE -> "none"
                            VolumeStatus.LOW -> "under ${VolumeAnalyzer.MAINTENANCE_SETS.toInt()}"
                            VolumeStatus.OK -> "ok"
                            VolumeStatus.HIGH -> "over ${VolumeAnalyzer.JUNK_SETS.toInt()}"
                        },
                        Modifier.width(96.dp), style = MaterialTheme.typography.bodyMedium, color = status.color(),
                    )
                }
            }
            item {
                Text(
                    "Under ${VolumeAnalyzer.MAINTENANCE_SETS.toInt()} sets/week is maintenance territory; over ${VolumeAnalyzer.JUNK_SETS.toInt()} is likely junk volume.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}
