package app.gains.ui.i18n

import androidx.compose.runtime.Composable
import app.gains.analysis.Achievement
import app.gains.analysis.AchievementStatus
import app.gains.analysis.AchievementTrack
import app.gains.analysis.Format
import app.gains.analysis.Record
import app.gains.analysis.RecordHolder
import app.gains.analysis.RecordKind
import app.gains.domain.Exercise
import app.gains.domain.Modality
import app.gains.domain.SetEntry
import app.gains.domain.WeightUnit
import app.gains.resources.Res
import app.gains.resources.*
import org.jetbrains.compose.resources.stringResource
import kotlin.math.ceil

/*
 * Words for the records, the score and the achievements, which the shared module hands over as
 * kinds, tiers and numbers.
 */

@Composable internal fun RecordKind.label(): String = stringResource(when (this) {
    RecordKind.WEIGHT -> Res.string.record_kind_weight
    RecordKind.E1RM -> Res.string.record_kind_e1rm
    RecordKind.SET_VOLUME -> Res.string.record_kind_set_volume
    RecordKind.SESSION_VOLUME -> Res.string.record_kind_session_volume
    RecordKind.REPS -> Res.string.record_kind_reps
    RecordKind.HOLD -> Res.string.record_kind_hold
    RecordKind.DISTANCE -> Res.string.record_kind_distance
})

/** A record's number alone, as "97.5 kg", "106 kg", "12 reps", "1:30": what the old one was. */
@Composable
internal fun recordValueText(kind: RecordKind, value: Double, unit: WeightUnit): String {
    val labels = unitLabels()
    return when (kind) {
        RecordKind.WEIGHT -> Format.weight(value, unit, labels)
        RecordKind.E1RM -> Format.weight(value, unit, labels, 0)
        RecordKind.SET_VOLUME, RecordKind.SESSION_VOLUME -> Format.weight(value, unit, labels, 0)
        RecordKind.REPS -> "${value.toInt()} ${labels.reps}"
        RecordKind.HOLD -> Format.seconds(value.toInt(), labels)
        RecordKind.DISTANCE -> Format.km(value, labels)
    }
}

/**
 * The record with the set that made it: "100 kg × 3" for a weight record, "800 kg · 100 kg × 8"
 * for a set-volume one, "+10 kg × 5" for weighted pull-ups. A session-volume record has no set.
 */
@Composable
internal fun recordText(kind: RecordKind, value: Double, set: SetEntry?, modality: Modality, unit: WeightUnit): String {
    val labels = unitLabels()
    val setText = set?.let { s ->
        val w = s.weightKg
        val r = s.reps
        when {
            w != null && r != null && w > 0 -> (if (modality == Modality.BODYWEIGHT) "+" else "") + Format.set(w, r, unit, labels)
            r != null -> "$r ${labels.reps}"
            else -> null
        }
    }
    return when (kind) {
        RecordKind.WEIGHT -> setText ?: recordValueText(kind, value, unit)
        RecordKind.E1RM, RecordKind.SET_VOLUME -> recordValueText(kind, value, unit) + (setText?.let { " · $it" } ?: "")
        RecordKind.REPS -> setText ?: recordValueText(kind, value, unit)
        RecordKind.SESSION_VOLUME, RecordKind.HOLD, RecordKind.DISTANCE -> recordValueText(kind, value, unit)
    }
}

@Composable internal fun recordText(record: Record, modality: Modality, unit: WeightUnit): String = recordText(record.kind, record.value, record.set, modality, unit)
@Composable internal fun recordText(holder: RecordHolder, modality: Modality, unit: WeightUnit): String = recordText(holder.kind, holder.value, holder.set, modality, unit)

/** The track's name: "Showed up", "Tonnage", or the lift's for a plate ladder. */
@Composable
internal fun achievementTitle(achievement: Achievement, exercisesById: Map<String, Exercise>): String = when (achievement.track) {
    AchievementTrack.SESSIONS -> stringResource(Res.string.track_sessions)
    AchievementTrack.STREAK -> stringResource(Res.string.track_streak)
    AchievementTrack.TONNAGE -> stringResource(Res.string.track_tonnage)
    AchievementTrack.RECORDS -> stringResource(Res.string.track_records)
    AchievementTrack.PLATES -> exercisesById[achievement.exerciseId]?.displayName() ?: achievement.exerciseId.orEmpty()
    AchievementTrack.COMEBACK -> stringResource(Res.string.track_comeback)
}

/** What the rung is for: "50 sessions on record", "2 plates a side · 100 kg", "12 weeks in a row". */
@Composable
internal fun achievementTier(achievement: Achievement, unit: WeightUnit): String {
    val n = achievement.threshold
    return when (achievement.track) {
        AchievementTrack.SESSIONS -> stringResource(Res.string.track_sessions_tier, sessionsText(n.toInt()))
        AchievementTrack.STREAK -> stringResource(Res.string.track_streak_tier, weeksGenitive(n.toInt()))
        AchievementTrack.TONNAGE -> stringResource(Res.string.track_tonnage_tier, Format.tonnage(n, unit, unitLabels()))
        AchievementTrack.RECORDS -> stringResource(Res.string.track_records_tier, recordsText(n.toInt()))
        AchievementTrack.PLATES -> stringResource(Res.string.track_plates_tier, platesText(achievement.tier), Format.weight(n, unit, unitLabels(), 0))
        AchievementTrack.COMEBACK -> stringResource(Res.string.track_comeback_tier)
    }
}

/** How far a locked rung is: "12 sessions to go", "3 t to go", "2.5 kg to go". Null once it is earned. */
@Composable
internal fun achievementToGo(status: AchievementStatus, unit: WeightUnit): String? {
    if (status.earned) return null
    val left = status.achievement.threshold - status.progress
    val labels = unitLabels()
    val amount = when (status.achievement.track) {
        AchievementTrack.SESSIONS -> sessionsText(ceil(left).toInt())
        AchievementTrack.STREAK -> weeksText(ceil(left).toInt())
        AchievementTrack.TONNAGE -> Format.tonnage(left, unit, labels)
        AchievementTrack.RECORDS -> recordsText(ceil(left).toInt())
        AchievementTrack.PLATES -> Format.weight(left, unit, labels)
        AchievementTrack.COMEBACK -> return null
    }
    return stringResource(Res.string.achievement_to_go, amount)
}

/** The glyph on a track's badge; the app draws its badges with text, as it does its empty states. */
internal fun AchievementTrack.glyph(): String = when (this) {
    AchievementTrack.SESSIONS -> "✓"
    AchievementTrack.STREAK -> "◷"
    AchievementTrack.TONNAGE -> "▲"
    AchievementTrack.RECORDS -> "★"
    AchievementTrack.PLATES -> "●"
    AchievementTrack.COMEBACK -> "↩"
}
