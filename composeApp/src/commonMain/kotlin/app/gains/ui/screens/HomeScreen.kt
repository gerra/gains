package app.gains.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
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
import app.gains.analysis.Insight
import app.gains.analysis.InsightEngine
import app.gains.analysis.InsightKind
import app.gains.analysis.TrainingData
import app.gains.data.SettingsRepository
import app.gains.domain.WeightUnit
import app.gains.ui.ScreenModel
import app.gains.ui.components.EmptyState
import app.gains.ui.components.SectionHeader
import app.gains.ui.components.StatCard
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

data class HomeState(
    val loading: Boolean = true,
    val sessionCount: Int = 0,
    val exerciseCount: Int = 0,
    val lastSession: LocalDate? = null,
    val insights: List<Insight> = emptyList(),
    val unit: WeightUnit = WeightUnit.KG,
)

class HomeModel(
    trainingData: TrainingData = inject(),
    settings: SettingsRepository = inject(),
) : ScreenModel() {
    val state: StateFlow<HomeState> = combine(trainingData.snapshot, settings.observeUnit()) { snapshot, unit -> snapshot to unit }
        .mapLatest { (snapshot, unit) ->
            withContext(Dispatchers.Default) {
                val today = Dates.today()
                HomeState(
                    loading = false,
                    sessionCount = snapshot.sessions.size,
                    exerciseCount = snapshot.trainedExercises.size,
                    lastSession = snapshot.sessions.maxOfOrNull { it.date },
                    insights = InsightEngine(unit = unit).generate(snapshot.sessions, snapshot.exercises, today),
                    unit = unit,
                )
            }
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), HomeState())
}

@Composable
fun HomeScreen(onImport: () -> Unit, onOpenExercise: (String) -> Unit, onOpenVolume: () -> Unit) {
    val model = rememberScreenModel { HomeModel() }
    val state by model.state.collectAsState()

    when {
        state.loading -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            CircularProgressIndicator()
        }
        state.sessionCount == 0 -> EmptyState(
            title = "No workouts yet",
            body = "Export a CSV from Liftoff and import it here. Everything stays on this device.",
            action = { Button(onClick = onImport) { Text("Import CSV") } },
        )
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCard("Sessions", state.sessionCount.toString(), Modifier.weight(1f))
                    StatCard("Lifts", state.exerciseCount.toString(), Modifier.weight(1f))
                    StatCard("Last", state.lastSession?.let { Dates.short(it) } ?: "-", Modifier.weight(1f))
                }
            }
            item { SectionHeader("What's moving") }
            if (state.insights.isEmpty()) {
                item {
                    Text(
                        if (state.sessionCount == 1) "One session imported. Insights need a few weeks of history to compare against."
                        else "Nothing to flag yet. Keep importing and the trends will show up here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(state.insights) { insight ->
                InsightCard(insight, onClick = {
                    when {
                        insight.exerciseId != null -> onOpenExercise(insight.exerciseId!!)
                        insight.muscleGroup != null -> onOpenVolume()
                    }
                })
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

fun InsightKind.color(): Color = when (this) {
    InsightKind.REGRESSION -> GainsColors.Regression
    InsightKind.STALL -> GainsColors.Stall
    InsightKind.NEGLECT -> GainsColors.Neglect
    InsightKind.CONSISTENCY -> GainsColors.Consistency
    InsightKind.PROGRESS -> GainsColors.Progress
}

@Composable
fun InsightCard(insight: Insight, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Spacer(Modifier.padding(top = 5.dp).size(10.dp).background(insight.kind.color(), CircleShape))
            Spacer(Modifier.width(10.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(insight.kind.label.uppercase(), style = MaterialTheme.typography.labelSmall, color = insight.kind.color(), fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    Text(insight.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
                Text(insight.detail, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
