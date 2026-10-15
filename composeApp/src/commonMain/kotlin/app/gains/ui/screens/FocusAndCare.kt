package app.gains.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gains.ErrorReporter
import app.gains.data.ProgramRepository
import app.gains.catalogue.ExerciseCatalogue
import app.gains.domain.Caution
import app.gains.domain.Exercise
import app.gains.domain.Program
import app.gains.domain.SlotReason
import app.gains.program.Tailor
import app.gains.domain.FocusArea
import app.gains.resources.Res
import app.gains.resources.*
import app.gains.ui.ScreenModel
import app.gains.ui.components.Dp16
import app.gains.ui.components.GainsCard
import app.gains.ui.components.ScreenTitle
import app.gains.ui.components.SectionHeader
import app.gains.ui.i18n.*
import app.gains.ui.inject
import app.gains.ui.launchAction
import app.gains.ui.rememberScreenModel
import app.gains.ui.theme.GainsColors
import app.gains.ui.theme.LocalReduceMotion
import app.gains.ui.theme.Motion
import app.gains.ui.theme.popIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.jetbrains.compose.resources.stringResource

/*
 * The two questions that fit a built-in program to the person: what to work on more (focus areas)
 * and what to be careful with (cautions). Asked in onboarding and changed later on their own screen,
 * reached from Home's plan line, a program's page and Settings. See app.gains.program.Tailor.
 */

/** The toggling rules both places share: "whole body" is no area; a third area is refused, and says so. */
internal object FocusChoice {
    /** The new set, or null when [area] would be a third pick. */
    fun toggle(current: Set<FocusArea>, area: FocusArea): Set<FocusArea>? = when {
        area in current -> current - area
        current.size >= FocusArea.MAX -> null
        else -> current + area
    }
}

/**
 * Whole body (preselected while nothing is picked), then the areas, one to a row like onboarding's
 * other answers, each with two of the exercises it adds so the choice says what it means. A refused
 * third pick is said by [CapNote], which the caller pins where the thumb is.
 */
@Composable
internal fun FocusPicker(focus: Set<FocusArea>, onWholeBody: () -> Unit, onToggle: (FocusArea) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SelectTile(stringResource(Res.string.focus_whole_body), stringResource(Res.string.focus_whole_body_blurb), focus.isEmpty(), onWholeBody, Modifier.fillMaxWidth())
        for (area in FocusArea.entries) SelectTile(area.label(), area.blurb(), area in focus, { onToggle(area) }, Modifier.fillMaxWidth())
    }
}

/** "Two at most…", over the bottom of the screen while the last tap asked for a third area. */
@Composable
internal fun CapNote(visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible, modifier) {
        Box(
            Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(stringResource(Res.string.focus_cap), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** "Nothing right now" (preselected while nothing is picked), then each caution, one to a row. */
@Composable
internal fun CautionPicker(cautions: Set<Caution>, onNothing: () -> Unit, onToggle: (Caution) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SelectTile(stringResource(Res.string.caution_none), null, cautions.isEmpty(), onNothing, Modifier.fillMaxWidth())
        for (c in Caution.entries) SelectTile(c.label(), null, c in cautions, { onToggle(c) }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        if (cautions.size >= 3) {
            Text(stringResource(Res.string.careful_many), style = MaterialTheme.typography.bodyMedium)
        }
        Text(stringResource(Res.string.careful_stays), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * What to do with each caution picked, then the rule for how much discomfort is fine, when to see
 * someone, and that this is not medical advice. Nothing at all while nothing is picked.
 */
@Composable
internal fun CautionGuide(cautions: Set<Caution>, modifier: Modifier = Modifier, inDialog: Boolean = false) {
    if (cautions.isEmpty()) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(if (inDialog) 16.dp else 8.dp)) {
        for (c in Caution.entries.filter { it in cautions }) {
            // One line each until opened, so a few of them do not bury the rules that apply to all.
            if (inDialog) GuideSection(c.guideTitle(), c.guide(), c.stopSigns())
            else GuideRow(c.guideTitle(), c.guide(), c.stopSigns())
        }
        // The pain rule is about joints; pregnancy, a new baby and blood pressure have stop signs of their own.
        if (cautions.any { it in setOf(Caution.KNEES, Caution.LOWER_BACK, Caution.SHOULDERS, Caution.WRISTS) }) {
            GuideSection(stringResource(Res.string.guide_pain_rule_title), stringResource(Res.string.guide_pain_rule), null, card = !inDialog)
        }
        GuideSection(stringResource(Res.string.guide_red_flags_title), stringResource(Res.string.guide_red_flags), null, card = !inDialog)
        Text(stringResource(Res.string.guide_not_advice), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A title and its text, on a card or plain (inside a dialog, which is a surface already). */
@Composable
private fun GuideSection(title: String, body: String, stop: String?, card: Boolean = false) {
    val content: @Composable () -> Unit = {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(4.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        if (stop != null) {
            Spacer(Modifier.height(8.dp))
            Text(stop, style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (card) GainsCard(Modifier.fillMaxWidth(), contentPadding = Dp16.Tight) { content() }
    else Column { content() }
}

/** A caution's guidance folded to its title with a chevron; a tap opens it where it stands. */
@Composable
private fun GuideRow(title: String, body: String, stop: String?) {
    var open by remember { mutableStateOf(false) }
    GainsCard(Modifier.fillMaxWidth(), onClick = { open = !open }, contentPadding = Dp16.Tight) {
        Row(Modifier.fillMaxWidth().heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Text(if (open) "⌃" else "›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AnimatedVisibility(open) {
            Column {
                Spacer(Modifier.height(4.dp))
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (stop != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(stop, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** The cautions' guidance in a dialog, opened from a workout. */
@Composable
internal fun CautionGuideDialog(cautions: Set<Caution>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.large,
        title = { Text(stringResource(Res.string.how_to_train_with_it)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { CautionGuide(cautions, inDialog = true) } },
        // The dialog's own text colour is the muted one, which reads as disabled for advice meant to be read.
        textContentColor = MaterialTheme.colorScheme.onSurface,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.done), color = GainsColors.palette.volt) } },
    )
}

/**
 * "Focus: glutes, abs and core" over "Careful with: knees", and Edit: the plan's fit, one tap
 * from changing it. On Home under the next day, and on a built-in program's page.
 */
@Composable
internal fun PlanFitLine(focus: Set<FocusArea>, cautions: Set<Caution>, onEdit: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(focusSummary(focus), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            cautionsSummary(cautions)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        TextButton(onClick = onEdit, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(Res.string.change), color = GainsColors.palette.volt) }
    }
}

/** A choice that toggles: a card that lights up with a tick, like onboarding's answers. */
@Composable
internal fun SelectTile(title: String, blurb: String?, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val palette = GainsColors.palette
    val reduce = LocalReduceMotion.current
    val ring by animateColorAsState(if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent, Motion.standard(), label = "tile")
    val tickFill by animateColorAsState(if (selected) palette.volt else MaterialTheme.colorScheme.surfaceContainerHighest, Motion.standard(), label = "tile-tick")
    GainsCard(
        modifier.heightIn(min = 56.dp).border(1.5.dp, ring, MaterialTheme.shapes.large),
        onClick = onClick, contentPadding = Dp16.Tight,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (blurb != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(24.dp).clip(CircleShape).background(tickFill), contentAlignment = Alignment.Center) {
                androidx.compose.animation.AnimatedVisibility(selected, enter = popIn(reduce, from = 0.4f), exit = if (reduce) ExitTransition.None else fadeOut(tween(Motion.PRESS))) {
                    Text("✓", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = if (palette.isDark) MaterialTheme.colorScheme.onPrimary else Color.White)
                }
            }
        }
    }
}

internal data class FocusAndCareState(val loading: Boolean = true, val focus: Set<FocusArea> = emptySet(), val cautions: Set<Caution> = emptySet())

internal class FocusAndCareModel(private val programs: ProgramRepository, reporter: ErrorReporter) : ScreenModel(reporter) {
    val state: StateFlow<FocusAndCareState> = combine(programs.observeFocus(), programs.observeCautions()) { focus, cautions ->
        FocusAndCareState(false, focus, cautions)
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), FocusAndCareState())

    var capHit by mutableStateOf(false)
        private set

    fun wholeBody() { capHit = false; scope.launchAction { programs.setFocus(emptySet()) } }
    fun toggle(area: FocusArea) {
        val next = FocusChoice.toggle(state.value.focus, area)
        capHit = next == null
        if (next != null) scope.launchAction { programs.setFocus(next) }
    }
    fun nothing() { scope.launchAction { programs.setCautions(emptySet()) } }
    fun toggle(caution: Caution) {
        val current = state.value.cautions
        scope.launchAction { programs.setCautions(if (caution in current) current - caution else current + caution) }
    }
}

/** Both questions on one page, each change kept as it is made: the built-in programs follow at once. */
@Composable
internal fun FocusAndCareScreen() {
    val model = rememberScreenModel { FocusAndCareModel(inject(), inject()) }
    val state by model.state.collectAsState()
    if (state.loading) return
    Box(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
        item {
            ScreenTitle(stringResource(Res.string.focus_and_health), stringResource(Res.string.focus_and_health_blurb))
            SectionHeader(stringResource(Res.string.onboarding_focus_title).replace('\n', ' '), Modifier.padding(top = 0.dp))
            Text(stringResource(Res.string.onboarding_focus_blurb), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(Res.string.focus_honest), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            FocusPicker(state.focus, model::wholeBody, model::toggle)
            SectionHeader(stringResource(Res.string.onboarding_careful_title).replace('\n', ' '))
            Text(stringResource(Res.string.onboarding_careful_blurb), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            CautionPicker(state.cautions, model::nothing, model::toggle)
            Spacer(Modifier.height(16.dp))
            CautionGuide(state.cautions)
        }
    }
    CapNote(model.capHit, Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp))
    }
}

/**
 * Why a slot differs from the program as written: "For your glutes", "Instead of Squat: often easier
 * on knees.". With [cue], as in a workout, the caution's how-to follows, and a kept exercise shows
 * just its cue; without, as in a program's day list, a kept exercise shows nothing.
 */
@Composable
internal fun slotReasonText(reason: SlotReason?, exerciseId: String, exercisesById: Map<String, Exercise>, cue: Boolean): String? = when (reason) {
    null -> null
    is SlotReason.Focus -> listOfNotNull(reason.area.forYour(), reason.careful?.takeIf { cue }?.cue(exerciseId)).joinToString(" · ")
    is SlotReason.Swapped -> {
        val from = (exercisesById[reason.fromExerciseId] ?: ExerciseCatalogue.byId(reason.fromExerciseId))?.displayName() ?: reason.fromExerciseId
        listOfNotNull(reason.caution.swappedText(from, reason.why), reason.caution.takeIf { cue }?.cue(exerciseId)).joinToString(" ")
    }
    is SlotReason.Careful -> if (cue) reason.caution.cue(exerciseId) else null
}

/**
 * How a built-in program was fitted, in two lines: "Added for you: Hip Thrust, Dead Bug…" and
 * "Gentler swaps: Box Squat…". Nothing when it was not changed.
 */
@Composable
internal fun FittedSummary(program: Program, modifier: Modifier = Modifier) {
    val slots = program.days.flatMap { it.slots }
    val added = slots.filter { it.reason is SlotReason.Focus }.map { it.exerciseId }.distinct()
    val swapped = slots.filter { it.reason is SlotReason.Swapped }.map { it.exerciseId }.distinct()
    if (added.isEmpty() && swapped.isEmpty()) return
    // Lower case: the names run on inside a sentence.
    @Composable fun names(ids: List<String>) = ids.mapNotNull { ExerciseCatalogue.byId(it)?.displayName()?.lowercase() }.joinToString(", ")
    Column(modifier) {
        if (added.isNotEmpty()) Text(stringResource(Res.string.fitted_adds, names(added)), style = MaterialTheme.typography.bodySmall)
        if (swapped.isNotEmpty()) Text(stringResource(Res.string.fitted_gentler, names(swapped)), style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * On a program built on heavy sets, for someone pregnant or with high blood pressure: a callout
 * saying what suits better, with the switch to it one tap away.
 */
@Composable
internal fun HeavyProgramNote(program: Program, cautions: Set<Caution>, onSwitch: () -> Unit, modifier: Modifier = Modifier) {
    if (!program.isBuiltIn || program.id == FULL_BODY_BASICS || cautions.none { it.easesHardSets } || !Tailor.isHeavy(program)) return
    Column(
        modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(16.dp),
    ) {
        Text(stringResource(Res.string.heavy_program_note), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        app.gains.ui.components.SecondaryButton(stringResource(Res.string.switch_to_full_body), onClick = onSwitch, compact = true)
    }
}

/** The program a newcomer, or anyone told to keep the sets moderate, is pointed to. */
internal const val FULL_BODY_BASICS = "full_body_basics"
