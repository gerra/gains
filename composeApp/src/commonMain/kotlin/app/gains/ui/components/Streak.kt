package app.gains.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.gains.analysis.Streak
import app.gains.analysis.StreakStatus
import app.gains.resources.Res
import app.gains.resources.*
import app.gains.ui.i18n.dayShort
import app.gains.ui.i18n.restWeeksText
import app.gains.ui.i18n.sessionsText
import app.gains.ui.i18n.streakLine
import app.gains.ui.i18n.weekStreakLabel
import app.gains.ui.i18n.weeksText
import app.gains.ui.theme.GainsColors
import kotlinx.datetime.DayOfWeek
import org.jetbrains.compose.resources.stringResource

/**
 * What the streak is, where this week stands, and the single honest sentence about what is at
 * stake — the nudge itself. The number and the week strip say it at a glance; the sentence says it
 * in words; the colour escalates from neutral to amber only on the two days it would be true.
 * Nothing here shames a missed week: a rest week covering one is reported as the win it is.
 *
 * [onRemindMe] adds the offer of a reminder beside the sentence, for as long as the lifter has
 * neither taken it nor turned it down. [footer] hangs whatever else the screen wants under it.
 */
@Composable
internal fun StreakCard(
    streak: Streak,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onRemindMe: (() -> Unit)? = null,
    footer: @Composable ColumnScope.() -> Unit = {},
) {
    val palette = GainsColors.palette
    val accent = streak.accent()
    GainsCard(modifier, onClick = onClick, brush = palette.heroBrush(), contentPadding = Dp16.Loose) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    // A zero is not an achievement, so it is not dressed as one.
                    Text(
                        streak.weeks.toString(),
                        style = MaterialTheme.typography.displayLarge,
                        color = if (streak.weeks > 0) palette.volt else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        weekStreakLabel(streak.weeks),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                // The record only earns a line once there is one worth naming: a single week is not one.
                if (streak.best >= 2) {
                    val personalBest = streak.weeks >= streak.best
                    Text(
                        if (personalBest) stringResource(Res.string.streak_personal_best) else stringResource(Res.string.streak_best, weeksText(streak.best)),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (personalBest) palette.volt else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (streak.atMilestone) Pill(stringResource(Res.string.streak_milestone), palette.volt, filled = true)
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(stringResource(Res.string.this_week).uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(
                stringResource(Res.string.streak_goal_progress, streak.sessionsThisWeek, streak.goalPerWeek),
                style = MaterialTheme.typography.labelMedium,
                color = if (streak.fullWeek) palette.volt else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        WeekStrip(streak, accent)
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Dot(accent, size = 8.dp, modifier = Modifier.align(Alignment.Top).padding(top = 6.dp))
            Spacer(Modifier.width(8.dp))
            Text(streakLine(streak), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (onRemindMe != null) {
                Spacer(Modifier.width(10.dp))
                Pill(stringResource(Res.string.streak_remind_me), palette.volt, onClick = onRemindMe)
            }
        }
        // One secondary line at most: a rest week just spent outranks rest weeks merely in hand.
        val aside = when {
            streak.heldLastWeek -> stringResource(Res.string.streak_rest_week_used) to palette.cyan
            streak.restWeeksInHand > 0 -> stringResource(Res.string.streak_rest_in_hand, restWeeksText(streak.restWeeksInHand)) to MaterialTheme.colorScheme.onSurfaceVariant
            else -> null
        }
        if (aside != null) {
            Spacer(Modifier.height(6.dp))
            Row {
                Spacer(Modifier.width(16.dp))
                Text(aside.first, style = MaterialTheme.typography.bodySmall, color = aside.second)
            }
        }
        footer()
    }
}

/**
 * Monday to Sunday as seven cells: trained days filled, today ringed, the days still to come faded
 * so the week reads as something with room left in it. While the streak is at risk today's ring
 * breathes — the only moving thing on the screen, and only on the days it means something.
 */
@Composable
internal fun WeekStrip(streak: Streak, accent: Color, modifier: Modifier = Modifier) {
    val palette = GainsColors.palette
    val shape = RoundedCornerShape(12.dp)
    val todayIndex = 7 - streak.daysLeftInWeek
    val atRisk = streak.status == StreakStatus.AT_RISK || streak.status == StreakStatus.LAST_CHANCE
    // Started only while it would mean something: nothing on this screen animates on a quiet week.
    val pulse = if (!atRisk) 1f else {
        val alpha by rememberInfiniteTransition(label = "at-risk").animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
            label = "ring",
        )
        alpha
    }
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        streak.thisWeekSessions.forEachIndexed { index, count ->
            val today = index == todayIndex
            val future = index > todayIndex
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    dayShort(DayOfWeek.entries[index]),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (today) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.alpha(if (future) 0.5f else 1f),
                )
                Spacer(Modifier.height(5.dp))
                val description = dayCellDescription(DayOfWeek.entries[index], count)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(42.dp)
                        .clip(shape)
                        .background(
                            when {
                                count > 0 -> palette.volt
                                future -> MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.35f)
                                else -> MaterialTheme.colorScheme.surfaceContainerHighest
                            },
                        )
                        .then(if (today && count == 0) Modifier.border(1.5.dp, accent.copy(alpha = pulse), shape) else Modifier)
                        .semantics { contentDescription = description },
                    contentAlignment = Alignment.Center,
                ) {
                    // Two sessions in a day is worth saying; one is already clear from the fill.
                    if (count > 1) Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    }
}

/** "Wed, 1 session" / "Wed, no sessions": the week strip for screen readers. */
@Composable
private fun dayCellDescription(day: DayOfWeek, sessions: Int): String =
    dayShort(day) + ", " + if (sessions == 0) stringResource(Res.string.no_sessions_count) else sessionsText(sessions)

/** Neutral while there is room, amber once there is not, cyan when a rest week is what is at stake. */
@Composable
private fun Streak.accent(): Color {
    val palette = GainsColors.palette
    return when (status) {
        StreakStatus.SAFE -> palette.volt
        StreakStatus.AT_RISK, StreakStatus.LAST_CHANCE -> if (protectedByRestWeek) palette.cyan else palette.amber
        StreakStatus.OPEN, StreakStatus.NONE -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}
