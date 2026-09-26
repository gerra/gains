package app.gains.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gains.analysis.AchievementStatus
import app.gains.analysis.AchievementTrack
import app.gains.analysis.Achievements
import app.gains.analysis.Dates
import app.gains.analysis.Level
import app.gains.analysis.Record
import app.gains.analysis.Records
import app.gains.analysis.Scoring
import app.gains.analysis.TrainingData
import app.gains.data.ProgramRepository
import app.gains.data.SettingsRepository
import app.gains.domain.Exercise
import app.gains.domain.WeightUnit
import app.gains.resources.Res
import app.gains.resources.*
import app.gains.ui.ScreenModel
import app.gains.ui.components.Dp16
import app.gains.ui.components.EmptyState
import app.gains.ui.components.GainsCard
import app.gains.ui.components.Meter
import app.gains.ui.components.Pill
import app.gains.ui.components.RollingText
import app.gains.ui.components.RoundedIconBox
import app.gains.ui.components.ScreenTitle
import app.gains.ui.components.SectionHeader
import app.gains.ui.i18n.*
import app.gains.ui.inject
import app.gains.ui.rememberScreenModel
import app.gains.ui.theme.GainsColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

/** One track's ladder: its rungs in order, for the card that draws them. */
internal data class Ladder(val track: AchievementTrack, val exerciseId: String?, val rungs: List<AchievementStatus>) {
    val earned: Int get() = rungs.count { it.earned }
    /** The rungs worth drawing: every one earned, the next one ahead, and how many are further off. */
    val shown: List<AchievementStatus> get() = rungs.filter { it.earned } + rungs.firstOrNull { !it.earned }.let { listOfNotNull(it) }
    val further: Int get() = rungs.size - shown.size
}

internal data class TrophiesState(
    val loading: Boolean = true,
    val unit: WeightUnit = WeightUnit.KG,
    val level: Level = Scoring.level(0),
    val ladders: List<Ladder> = emptyList(),
    val earned: Int = 0,
    val total: Int = 0,
    /** Newest first. */
    val recent: List<Record> = emptyList(),
    val exercisesById: Map<String, Exercise> = emptyMap(),
)

internal class TrophiesModel(
    trainingData: TrainingData = inject(),
    settings: SettingsRepository = inject(),
    programs: ProgramRepository = inject(),
) : ScreenModel() {
    val state: StateFlow<TrophiesState> = combine(trainingData.snapshot, settings.observeUnit(), programs.observeState()) { snapshot, unit, programState ->
        withContext(Dispatchers.Default) {
            val statuses = Achievements.evaluate(snapshot.sessions, snapshot.exercisesById, unit, programState.weeklyGoal)
            val ladders = statuses.groupBy { it.achievement.track to it.achievement.exerciseId }
                .map { (key, rungs) -> Ladder(key.first, key.second, rungs.sortedBy { it.achievement.tier }) }
            val timeline = Records.timeline(snapshot.sessions, snapshot.exercisesById)
            TrophiesState(
                loading = false,
                unit = unit,
                level = Scoring.level(Scoring.total(Scoring.sessions(snapshot.sessions, snapshot.exercisesById).values)),
                ladders = ladders,
                earned = statuses.count { it.earned },
                total = statuses.size,
                recent = timeline.asReversed().take(RECENT),
                exercisesById = snapshot.exercisesById,
            )
        }
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), TrophiesState())

    companion object {
        const val RECENT = 30
    }
}

/**
 * Everything earned in one place: the level with what it takes to reach the next, every ladder
 * with the rungs climbed and the one ahead, and the records lately set. Nothing here is a
 * leaderboard; the only lifter compared with is the one from last month.
 */
@Composable
internal fun TrophiesScreen(onOpenExercise: (String) -> Unit, onOpenSession: (String) -> Unit) {
    val model = rememberScreenModel { TrophiesModel() }
    val state by model.state.collectAsState()
    if (state.loading) return
    val palette = GainsColors.palette
    val today = Dates.today()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
        item {
            ScreenTitle(stringResource(Res.string.trophies_title), subtitle = stringResource(Res.string.trophies_subtitle, state.level.level, pointsText(state.level.points)))
            LevelCard(state.level)
        }
        item {
            SectionHeader(stringResource(Res.string.achievements_section)) {
                Text(stringResource(Res.string.achievements_of, state.earned, state.total), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(state.ladders, key = { "${it.track}-${it.exerciseId}" }) { ladder ->
            LadderCard(ladder, state.unit, state.exercisesById, today, onOpenSession)
            Spacer(Modifier.height(10.dp))
        }
        item { SectionHeader(stringResource(Res.string.recent_records)) }
        if (state.recent.isEmpty()) {
            item { Text(stringResource(Res.string.no_records_yet), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(state.recent, key = { "${it.sessionId}-${it.exerciseId}-${it.kind}" }) { record ->
            val exercise = state.exercisesById[record.exerciseId]
            GainsCard(Modifier.fillMaxWidth().padding(bottom = 8.dp), onClick = { onOpenExercise(record.exerciseId) }, contentPadding = Dp16.Tight) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(exercise?.displayName() ?: record.exerciseId, style = MaterialTheme.typography.titleSmall)
                        Text(
                            record.kind.label() + " · " + (exercise?.let { recordText(record, it.modality, state.unit) } ?: "") +
                                " · " + stringResource(Res.string.record_was, recordValueText(record.kind, record.previous, state.unit)),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Pill(dateContextual(record.date, today), palette.volt, onClick = { onOpenSession(record.sessionId) })
                }
            }
        }
    }
}

/** The level, large, with the meter to the next one and how the points come about. */
@Composable
internal fun LevelCard(level: Level, modifier: Modifier = Modifier) {
    val palette = GainsColors.palette
    GainsCard(modifier.fillMaxWidth(), brush = palette.heroBrush(), contentPadding = Dp16.Loose) {
        Row(verticalAlignment = Alignment.Bottom) {
            RollingText(level.level.toString(), MaterialTheme.typography.displayLarge, palette.volt)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(Res.string.level_word).uppercase(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
            Spacer(Modifier.weight(1f))
            Text(stringResource(Res.string.points_in_total, pointsText(level.points)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
        }
        Spacer(Modifier.height(12.dp))
        Meter(level.fraction.toFloat(), palette.volt, Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        Text(stringResource(Res.string.level_to_next, pointsText(level.toNext), level.level + 1), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(Res.string.score_note, Scoring.SHOW_UP, Scoring.PER_SET, Scoring.MAX_WORK, Scoring.PER_RECORD, Scoring.MAX_RECORDS),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One ladder: the track's badge and name, then its rungs, earned ones lit and the next one ahead. */
@Composable
private fun LadderCard(ladder: Ladder, unit: WeightUnit, exercisesById: Map<String, Exercise>, today: kotlinx.datetime.LocalDate, onOpenSession: (String) -> Unit) {
    val palette = GainsColors.palette
    val first = ladder.rungs.first().achievement
    val lit = ladder.earned > 0
    GainsCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Badge(ladder.track, lit, size = 40.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(achievementTitle(first, exercisesById), style = MaterialTheme.typography.titleMedium)
                Text("${ladder.earned} / ${ladder.rungs.size}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        for (status in ladder.shown) {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp).alpha(if (status.earned) 1f else 0.7f), verticalAlignment = Alignment.CenterVertically) {
                Rung(status.earned)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(achievementTier(status.achievement, unit), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val earnedIn = status.earnedIn
                    val line = if (earnedIn != null) stringResource(Res.string.achievement_earned_on, dateContextual(earnedIn.date, today)) else achievementToGo(status, unit)
                    if (line != null) {
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (status.earned) palette.volt else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = if (earnedIn != null) Modifier.clip(CircleShape).clickable { onOpenSession(earnedIn.id) } else Modifier,
                        )
                    }
                    if (!status.earned && status.achievement.track != AchievementTrack.COMEBACK) {
                        Spacer(Modifier.height(4.dp))
                        Meter(status.fraction.toFloat(), palette.volt, Modifier.fillMaxWidth())
                    }
                }
            }
        }
        if (ladder.further > 0) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(Res.string.more_rungs, ladder.further), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A rung: a filled dot once climbed, a ring while ahead. */
@Composable
private fun Rung(earned: Boolean) {
    val palette = GainsColors.palette
    Box(
        Modifier.size(12.dp).clip(CircleShape)
            .then(if (earned) Modifier.background(palette.volt) else Modifier.border(1.5.dp, MaterialTheme.colorScheme.onSurfaceVariant, CircleShape)),
    )
}

/** The track's badge: its glyph in the accent when something on the ladder is earned, muted until then. */
@Composable
internal fun Badge(track: AchievementTrack, lit: Boolean, size: androidx.compose.ui.unit.Dp = 40.dp, color: Color? = null) {
    val palette = GainsColors.palette
    val tint = color ?: if (lit) palette.volt else MaterialTheme.colorScheme.onSurfaceVariant
    RoundedIconBox(tint, Modifier.size(size)) { Text(track.glyph(), style = MaterialTheme.typography.titleLarge, color = tint) }
}
