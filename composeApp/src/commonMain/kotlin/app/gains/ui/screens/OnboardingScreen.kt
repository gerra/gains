package app.gains.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
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
import app.gains.ui.components.PrimaryButton
import app.gains.ui.components.SecondaryButton
import app.gains.resources.Res
import app.gains.resources.*
import app.gains.ui.i18n.*
import org.jetbrains.compose.resources.stringResource
import app.gains.ui.inject
import app.gains.ui.rememberScreenModel
import app.gains.ui.theme.GainsColors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal class OnboardingModel(private val programs: ProgramRepository = inject()) : ScreenModel() {
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
        scope.launch {
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

    fun skip() = scope.launch { programs.markOnboardingDone(); done = true }

    /** Saves the answers and, when given, activates a program. */
    fun finish(programId: String?) = scope.launch {
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
    val model = rememberScreenModel { OnboardingModel() }
    val palette = GainsColors.palette
    if (model.done) { onDone(); return }

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GainsLogo(size = 36.dp)
            Spacer(Modifier.width(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (i in 0..3) Box(Modifier.size(8.dp).clip(CircleShape).background(if (i <= model.step) palette.volt else MaterialTheme.colorScheme.surfaceContainerHighest))
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { model.skip() }) { Text(stringResource(Res.string.skip_for_now), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Spacer(Modifier.height(16.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            when (model.step) {
                0 -> {
                    Text(stringResource(Res.string.onboarding_goal_title), style = MaterialTheme.typography.displaySmall)
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(Res.string.onboarding_goal_blurb), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(18.dp))
                    for (g in Goal.entries) {
                        OptionCard(g.label(), g.blurb(), selected = model.goal == g) { model.goal = g }
                    }
                }
                1 -> {
                    Text(stringResource(Res.string.onboarding_experience_title), style = MaterialTheme.typography.displaySmall)
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(Res.string.onboarding_experience_blurb), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(18.dp))
                    for (e in Experience.entries) {
                        OptionCard(e.label(), e.blurb(), selected = model.experience == e) { model.experience = e }
                    }
                }
                2 -> {
                    Text(stringResource(Res.string.onboarding_days_title), style = MaterialTheme.typography.displaySmall)
                    Spacer(Modifier.height(6.dp))
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
                    Spacer(Modifier.height(6.dp))
                    model.profile?.let {
                        Text(stringResource(Res.string.profile_summary, it.goal.label(), it.experience.label(), daysAWeekText(it.daysPerWeek)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(18.dp))
                    for ((index, program) in model.suggestions.withIndex()) {
                        GainsCard(Modifier.fillMaxWidth().padding(bottom = 10.dp), contentPadding = Dp16.Tight) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(program.displayName(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                if (index == 0) Pill(stringResource(Res.string.best_match), palette.volt, filled = true)
                            }
                            Spacer(Modifier.height(4.dp))
                            ProgramTags(program)
                            Spacer(Modifier.height(6.dp))
                            Text(program.displayDescription(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(10.dp))
                            SecondaryButton(stringResource(Res.string.use_this_program), onClick = { model.finish(program.id) }, Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (model.step > 0) SecondaryButton(stringResource(Res.string.back), onClick = { model.back() }, Modifier.weight(1f))
            when (model.step) {
                3 -> PrimaryButton(stringResource(Res.string.just_save_my_goal), onClick = { model.finish(null) }, Modifier.weight(1f))
                else -> PrimaryButton(stringResource(Res.string.next), onClick = { model.next() }, Modifier.weight(1f), enabled = model.canContinue)
            }
        }
    }
}

@Composable
private fun OptionCard(title: String, blurb: String, selected: Boolean, onClick: () -> Unit) {
    val palette = GainsColors.palette
    GainsCard(Modifier.fillMaxWidth().padding(bottom = 10.dp), onClick = onClick, contentPadding = Dp16.Tight) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = if (selected) palette.volt else MaterialTheme.colorScheme.onSurface)
                Text(blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier.size(22.dp).clip(CircleShape).background(if (selected) palette.volt else MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) { if (selected) Text("✓", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary) }
        }
    }
}

/**
 * "3d/wk · Beginner · Get stronger" as pills. A FlowRow rather than a Row: when the tags do not
 * fit on one line the last pill wraps whole onto a second line instead of being squeezed until
 * its label breaks across two lines.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProgramTags(program: Program, active: Boolean = false) {
    val palette = GainsColors.palette
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (active) Pill(stringResource(Res.string.active), palette.volt, filled = true)
        Pill(stringResource(Res.string.days_per_week_tag, program.daysPerWeek), palette.cyan)
        Pill(program.level.label(), palette.violet)
        for (g in program.goals.take(2)) Pill(g.label(), palette.amber)
    }
}
