package app.gains.ui.i18n

import androidx.compose.runtime.Composable
import app.gains.analysis.Format
import app.gains.analysis.Insight
import app.gains.analysis.InsightDetail
import app.gains.analysis.InsightSubject
import app.gains.analysis.Performance
import app.gains.analysis.Trend
import app.gains.domain.Modality
import app.gains.domain.ProgressionRule
import app.gains.domain.WeightUnit
import app.gains.program.Gzclp
import app.gains.program.Progression
import app.gains.resources.Res
import app.gains.resources.*
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/*
 * The sentences the shared module hands over as numbers: an insight's card, the progression hint
 * on an exercise card, and the plain-words description of a progression rule.
 */

/** The insight card's title: the lift, the muscle group, or what happened to the training frequency. */
@Composable
internal fun insightTitle(insight: Insight): String = when (val subject = insight.subject) {
    is InsightSubject.Lift -> subject.exercise.displayName()
    is InsightSubject.Muscle -> subject.group.label()
    is InsightSubject.Frequency -> subject.trend.frequencyTitle()
}

/** The insight card's sentence, with dates relative to [today] and weights in [unit]. */
@Composable
internal fun insightDetail(insight: Insight, unit: WeightUnit, today: LocalDate): String {
    val labels = unitLabels()
    val modality = (insight.subject as? InsightSubject.Lift)?.exercise?.modality ?: Modality.WEIGHTED
    fun Performance.text() = describe(modality, unit, labels)
    return when (val d = insight.detail) {
        is InsightDetail.Regression -> stringResource(Res.string.regression_detail, d.current.text(), d.best.text(), dateContextual(d.bestDate, today), Format.percent(d.drop))
        is InsightDetail.Stall -> stringResource(
            Res.string.stall_detail,
            d.topWeightKg?.let { Format.weight(it, unit, labels) } ?: d.best.text(), dateContextual(d.since, today), sessionsText(d.sessions), weeksAccusative(d.weeks),
        )
        is InsightDetail.NeglectedExercise -> stringResource(
            Res.string.neglected_exercise_detail,
            dateContextual(d.lastDate, today), weeksAccusative(d.weeksAgo), pluralStringResource(Res.plurals.sessions_genitive, d.priorSessions, d.priorSessions), weeksAccusative(d.lookbackWeeks),
        )
        is InsightDetail.NeglectedMuscle -> stringResource(
            Res.string.neglected_muscle_detail,
            Format.number(d.recentSetsPerWeek, 1), weeksAccusative(d.recentWeeks), Format.number(d.baselineSetsPerWeek, 1), weeksAccusative(d.baselineWeeks),
        )
        is InsightDetail.Consistency -> {
            val recent = stringResource(Res.string.consistency_recent, Format.number(d.recentPerWeek, 1), weeksAccusative(d.weeks))
            val previous = d.previousPerWeek?.let { Format.number(it, 1) }
            when {
                previous == null -> "$recent."
                d.trend == Trend.UP -> stringResource(Res.string.consistency_up, recent, previous, weeksAccusative(d.weeks))
                d.trend == Trend.DOWN -> stringResource(Res.string.consistency_down, recent, previous, weeksAccusative(d.weeks))
                else -> stringResource(Res.string.consistency_steady, recent, previous, weeksAccusative(d.weeks))
            }
        }
        is InsightDetail.Progress -> stringResource(
            Res.string.progress_detail,
            d.current.text(), dateContextual(d.currentDate, today), Format.percent(d.gain), d.previous.text(), dateContextual(d.previousDate, today),
        )
    }
}

/** One line for the exercise card: "Last: 60 kg × 5,5,5 → try 62.5 kg". */
@Composable
internal fun hintText(hint: Progression.Hint, unit: WeightUnit): String {
    val labels = unitLabels()
    fun w(kg: Double) = Format.weight(kg, unit, labels)
    val lastWeight = hint.last.weightKg?.let { (if (hint.last.addedLoad) "+" else "") + w(it) + " × " } ?: ""
    val last = stringResource(Res.string.last_label, lastWeight, hint.last.reps.joinToString(","))
    return when (hint) {
        is Progression.Hint.LastOnly -> last
        is Progression.Hint.Try -> stringResource(Res.string.hint_try, last, w(hint.weightKg))
        is Progression.Hint.Repeat -> stringResource(Res.string.hint_repeat, last, w(hint.weightKg))
        is Progression.Hint.MoveOn -> stringResource(Res.string.hint_move_on, last, hint.maxReps)
        is Progression.Hint.TryReps -> stringResource(Res.string.hint_try_reps, last, w(hint.weightKg), hint.reps)
        is Progression.Hint.Target -> stringResource(Res.string.hint_target, last, hint.weightKg?.let { w(it) + " × " } ?: "", hint.reps)
        is Progression.Hint.TryStage -> stringResource(Res.string.hint_try_stage, last, w(hint.weightKg), hint.stage.label)
        is Progression.Hint.Missed -> stringResource(Res.string.hint_missed, last, hint.stage.label, w(hint.weightKg))
        is Progression.Hint.Reset -> stringResource(Res.string.hint_reset, last, w(hint.weightKg), hint.stage.label)
        is Progression.Hint.Estimate -> {
            val plus = if (hint.addedLoad) "+" else ""
            val start = hint.startKg?.let { plus + w(it) }
                ?: stringResource(if (hint.addedLoad) Res.string.with_no_added_load else Res.string.as_light_as_you_can_load)
            val cap = hint.cappedBelowKg?.let { stringResource(Res.string.cap_kept_under, plus + w(it)) } ?: ""
            stringResource(Res.string.hint_estimate, last, plus + Format.weight(hint.e1rmKg, unit, labels, 0), hint.tier.label, start, cap)
        }
        is Progression.Hint.Start -> {
            val at = hint.weightKg?.let { stringResource(Res.string.at_weight, w(it)) } ?: ""
            stringResource(Res.string.hint_start, last, hint.source.label(), hint.target.label, at)
        }
    }
}

/**
 * The rule in plain words for the program overview: what happens after a good session, a missed
 * one, and where the ladder ends. Null for [ProgressionRule.None], which has nothing to say.
 */
@Composable
internal fun progressionDescription(rule: ProgressionRule, unit: WeightUnit): String? {
    val step = rule.step(unit)?.takeIf { it > 0.0 }?.let { "${Format.number(it, 2)} ${unit.label()}" }
    return when (rule) {
        ProgressionRule.None -> null
        is ProgressionRule.Linear -> stringResource(Res.string.describe_linear, step.toString())
        is ProgressionRule.DoubleProgression -> {
            val then = if (step == null) stringResource(Res.string.describe_double_then_harder) else stringResource(Res.string.describe_double_then_add, step, rule.min)
            stringResource(Res.string.describe_double, rule.min, rule.max, then)
        }
        is ProgressionRule.StageLadder -> {
            val stages = rule.stages.joinToString(" → ") { it.label }
            stringResource(Res.string.describe_ladder, stages, step.toString(), rule.stages.first().label)
        }
    }
}

/** "30–60 s or as needed": the rest after a warm-up set. */
@Composable
internal fun warmupRestText(): String = restRangeText(Gzclp.WARMUP_REST) + " " + stringResource(Res.string.warm_up_rest_or_as_needed)
