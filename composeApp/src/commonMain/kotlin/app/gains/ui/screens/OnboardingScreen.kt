package app.gains.ui.screens

import app.gains.ui.theme.screenSlide
import app.gains.ui.theme.popIn
import app.gains.ui.theme.enterOnce
import app.gains.ui.theme.Motion
import app.gains.ui.theme.LocalReduceMotion
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.gains.ErrorReporter
import app.gains.catalogue.ProgramCatalogue
import app.gains.data.ProgramRepository
import app.gains.domain.Experience
import app.gains.domain.Goal
import app.gains.domain.GoalProfile
import app.gains.domain.Program
import app.gains.program.ProgramSuggester
import app.gains.ui.ScreenModel
import app.gains.ui.components.ChipRow
import app.gains.ui.components.Dp16
import app.gains.ui.components.GainsCard
import app.gains.ui.components.GainsLogo
import app.gains.ui.components.Pill
import app.gains.ui.components.Tag
import app.gains.ui.components.PrimaryButton
import app.gains.ui.components.SecondaryButton
import app.gains.resources.Res
import app.gains.resources.*
import app.gains.ui.i18n.*
import org.jetbrains.compose.resources.stringResource
import app.gains.ui.inject
import app.gains.ui.launchAction
import app.gains.ui.rememberScreenModel
import app.gains.ui.theme.GainsColors
import kotlinx.coroutines.flow.first

internal class OnboardingModel(private val programs: ProgramRepository, reporter: ErrorReporter) : ScreenModel(reporter) {
    var step by mutableStateOf(0)
        private set
    var goal by mutableStateOf<Goal?>(null)
    var experience by mutableStateOf<Experience?>(null)
    var days by mutableStateOf(3)
    var suggestions by mutableStateOf<List<Program>>(emptyList())
        private set
    var done by mutableStateOf(false)
        private set

    init {
        scope.launchAction {
            programs.observeProfile().first()?.let { goal = it.goal; experience = it.experience; days = it.daysPerWeek }
        }
    }

    val profile: GoalProfile? get() {
        val g = goal ?: return null
        val e = experience ?: return null
        return GoalProfile(g, e, days)
    }

    val canContinue: Boolean get() = when (step) { 0 -> goal != null; 1 -> experience != null; else -> true }

    fun next() {
        if (!canContinue) return
        if (step == 2) suggestions = profile?.let { ProgramSuggester.suggest(it, ProgramCatalogue.builtIn) } ?: emptyList()
        step = (step + 1).coerceAtMost(3)
    }

    fun back() { step = (step - 1).coerceAtLeast(0) }

    fun skip() = scope.launchAction { programs.markOnboardingDone(); done = true }

    /** Saves the answers and, when given, activates a program. */
    fun finish(programId: String?) = scope.launchAction {
        profile?.let { programs.setProfile(it) }
        if (programId != null) programs.setActive(programId)
        programs.markOnboardingDone()
        done = true
    }
}

/**
 * Three questions and a suggestion, the way Hevy and Boostcamp start: goal, experience, days a
 * week. Every step can be skipped; the answers can be changed later in Settings.
 */
@Composable
internal fun OnboardingScreen(onDone: () -> Unit) {
    val model = rememberScreenModel { OnboardingModel(inject(), inject()) }
    val palette = GainsColors.palette
    if (model.done) { onDone(); return }
    val reduce = LocalReduceMotion.current

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GainsLogo(size = 28.dp)
            Spacer(Modifier.width(14.dp))
            // Four segments, one a question: the ones answered and the current one are filled in.
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (i in 0..3) {
                    val color by animateColorAsState(if (i <= model.step) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceContainerHighest, Motion.standard(), label = "step")
                    Box(Modifier.weight(1f).height(4.dp).clip(CircleShape).background(color))
                }
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { model.skip() }) {
                Text(stringResource(Res.string.skip_for_now), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(20.dp))
        // Each question slides in the way the lifter is going, as screens do: Next from the right, Back from the left.
        AnimatedContent(
            model.step,
            Modifier.weight(1f),
            transitionSpec = { screenSlide(forward = targetState > initialState, reduce = reduce) },
            label = "onboarding",
        ) { step ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            when (step) {
                0 -> {
                    Text(stringResource(Res.string.onboarding_goal_title), style = MaterialTheme.typography.displaySmall)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(Res.string.onboarding_goal_blurb), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(18.dp))
                    for (g in Goal.entries) {
                        OptionCard(g.label(), g.blurb(), selected = model.goal == g) { model.goal = g }
                    }
                }
                1 -> {
                    Text(stringResource(Res.string.onboarding_experience_title), style = MaterialTheme.typography.displaySmall)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(Res.string.onboarding_experience_blurb), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(18.dp))
                    for (e in Experience.entries) {
                        OptionCard(e.label(), e.blurb(), selected = model.experience == e) { model.experience = e }
                    }
                }
                2 -> {
                    Text(stringResource(Res.string.onboarding_days_title), style = MaterialTheme.typography.displaySmall)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(Res.string.onboarding_days_blurb), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(18.dp))
                    ChipRow((GoalProfile.MIN_DAYS..GoalProfile.MAX_DAYS).toList(), model.days, { it.toString() }, { model.days = it }, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    Text(
                        onboardingDaysNote(model.days),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> {
                    Text(stringResource(Res.string.programs_that_fit), style = MaterialTheme.typography.displaySmall)
                    Spacer(Modifier.height(8.dp))
                    model.profile?.let {
                        Text(stringResource(Res.string.profile_summary, it.goal.label(), it.experience.label(), daysAWeekText(it.daysPerWeek)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(18.dp))
                    for ((index, program) in model.suggestions.withIndex()) {
                        GainsCard(Modifier.enterOnce(index).fillMaxWidth().padding(bottom = 12.dp), contentPadding = Dp16.Normal) {
                            if (index == 0) {
                                Pill(stringResource(Res.string.best_match), palette.volt)
                                Spacer(Modifier.height(10.dp))
                            }
                            Text(program.displayName(), style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.height(8.dp))
                            ProgramTags(program)
                            Spacer(Modifier.height(10.dp))
                            Text(program.displayDescription(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(14.dp))
                            // The best match's button is the filled one: it is the answer to the questions.
                            if (index == 0) PrimaryButton(stringResource(Res.string.use_this_program), onClick = { model.finish(program.id) }, compact = true)
                            else SecondaryButton(stringResource(Res.string.use_this_program), onClick = { model.finish(program.id) }, compact = true)
                        }
                    }
                }
            }
        }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (model.step > 0) SecondaryButton(stringResource(Res.string.back), onClick = { model.back() }, Modifier.weight(1f))
            when (model.step) {
                // On the programs step the cards carry the main action, so finishing without one is the quiet button.
                3 -> SecondaryButton(stringResource(Res.string.just_save_my_goal), onClick = { model.finish(null) }, Modifier.weight(2f))
                else -> PrimaryButton(stringResource(Res.string.next), onClick = { model.next() }, Modifier.weight(2f), enabled = model.canContinue)
            }
        }
    }
}

@Composable
private fun OptionCard(title: String, blurb: String, selected: Boolean, onClick: () -> Unit) {
    val palette = GainsColors.palette
    val reduce = LocalReduceMotion.current
    // An answer picked lights up and its tick pops in; the one it replaced eases back.
    val ring by animateColorAsState(if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent, Motion.standard(), label = "option")
    val tickFill by animateColorAsState(if (selected) palette.volt else MaterialTheme.colorScheme.surfaceContainerHighest, Motion.standard(), label = "option-tick")
    GainsCard(
        Modifier.fillMaxWidth().padding(bottom = 10.dp).border(1.5.dp, ring, MaterialTheme.shapes.large),
        onClick = onClick, contentPadding = Dp16.Normal,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(2.dp))
                Text(blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier.size(24.dp).clip(CircleShape).background(tickFill),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.animation.AnimatedVisibility(selected, enter = popIn(reduce, from = 0.4f), exit = if (reduce) ExitTransition.None else fadeOut(tween(Motion.PRESS))) {
                    Text("✓", style = MaterialTheme.typography.labelMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = if (palette.isDark) MaterialTheme.colorScheme.onPrimary else Color.White)
                }
            }
        }
    }
}

/**
 * "3 days a week", "Beginner", "Get stronger" as quiet tags; only "Active" is coloured, since it is
 * the one that says something has happened. A FlowRow rather than a Row: when the tags do not
 * fit on one line the last pill wraps whole onto a second line instead of being squeezed until
 * its label breaks across two lines.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProgramTags(program: Program, active: Boolean = false) {
    val palette = GainsColors.palette
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val reduce = LocalReduceMotion.current
        // Activated from the program's page, the tag pops in beside the others.
        AnimatedVisibility(active, enter = popIn(reduce, from = 0.7f), exit = if (reduce) ExitTransition.None else fadeOut(tween(Motion.EXIT))) {
            Pill(stringResource(Res.string.active), palette.volt, filled = true)
        }
        Tag(daysAWeekText(program.daysPerWeek))
        Tag(program.level.label())
        for (g in program.goals.take(2)) Tag(g.label())
    }
}
