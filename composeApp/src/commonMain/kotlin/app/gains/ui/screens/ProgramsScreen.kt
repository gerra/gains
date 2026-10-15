package app.gains.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gains.data.ProgramRepository
import app.gains.catalogue.ProgramCatalogue
import app.gains.domain.GoalProfile
import app.gains.domain.Program
import app.gains.program.ProgramSuggester
import app.gains.ErrorReporter
import app.gains.ui.ScreenModel
import app.gains.ui.components.Dp16
import app.gains.ui.components.GainsCard
import app.gains.ui.components.ScreenTitle
import app.gains.ui.components.SectionHeader
import app.gains.resources.Res
import app.gains.resources.*
import app.gains.ui.i18n.*
import org.jetbrains.compose.resources.stringResource
import app.gains.ui.inject
import app.gains.ui.rememberScreenModel
import app.gains.ui.theme.GainsColors
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

internal data class ProgramsState(
    val loading: Boolean = true,
    val profile: GoalProfile? = null,
    val activeId: String? = null,
    val custom: List<Program> = emptyList(),
    /** Built-ins put first: the best matches for the profile and the active one; every built-in while there is no profile. */
    val builtIn: List<Program> = emptyList(),
    /** The other built-ins, folded under "More programs", in order of fit. */
    val more: List<Program> = emptyList(),
)

internal class ProgramsModel(programs: ProgramRepository, reporter: ErrorReporter) : ScreenModel(reporter) {
    val state: StateFlow<ProgramsState> = programs.observeState().map { s ->
        // Ranked as written (a heavy program is known by its own sets), shown as fitted to the person.
        val fitted = s.programs.filter { it.isBuiltIn }.associateBy { it.id }
        val list = s.profile?.let { ProgramSuggester.shortlist(it, ProgramCatalogue.builtIn, s.cautions, keep = s.activeProgramId) }
        ProgramsState(
            loading = false,
            profile = s.profile,
            activeId = s.activeProgramId,
            custom = s.programs.filter { !it.isBuiltIn },
            builtIn = list?.suggested?.mapNotNull { fitted[it.id] } ?: fitted.values.toList(),
            more = list?.more?.mapNotNull { fitted[it.id] }.orEmpty(),
        )
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), ProgramsState())
}

@Composable
internal fun ProgramsScreen(onOpen: (String) -> Unit, onNew: () -> Unit) {
    val model = rememberScreenModel { ProgramsModel(inject(), inject()) }
    val state by model.state.collectAsState()
    val palette = GainsColors.palette
    var showMore by rememberSaveable { mutableStateOf(false) }
    if (state.loading) return

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
        item {
            ScreenTitle(
                stringResource(Res.string.programs_title),
                subtitle = state.profile?.let { stringResource(Res.string.profile_summary, it.goal.label(), it.experience.label(), daysAWeekText(it.daysPerWeek)) } ?: stringResource(Res.string.set_a_goal_to_sort),
                trailing = { TextButton(onClick = onNew) { Text(stringResource(Res.string.plus_new), color = palette.volt) } },
            )
        }
        if (state.custom.isNotEmpty()) {
            item { SectionHeader(stringResource(Res.string.your_programs)) }
            items(state.custom, key = { it.id }) { ProgramRow(it, it.id == state.activeId, onClick = { onOpen(it.id) }, Modifier.animateItem()) }
        }
        item { SectionHeader(if (state.profile != null) stringResource(Res.string.suggested_for_you) else stringResource(Res.string.built_in)) }
        items(state.builtIn, key = { it.id }) { ProgramRow(it, it.id == state.activeId, onClick = { onOpen(it.id) }, Modifier.animateItem()) }
        // The rest wait, folded, so a newcomer reads two programs rather than eight.
        if (state.more.isNotEmpty()) {
            item(key = "more") {
                MoreProgramsRow(state.more.size, allAbove = state.profile?.let { p -> state.more.all { it.level > p.experience } } == true, open = showMore, onToggle = { showMore = !showMore }, Modifier.animateItem())
            }
            if (showMore) items(state.more, key = { it.id }) { ProgramRow(it, it.id == state.activeId, onClick = { onOpen(it.id) }, Modifier.animateItem()) }
        }
        item {
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(Res.string.built_in_note),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProgramRow(program: Program, active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    GainsCard(modifier.fillMaxWidth().padding(bottom = 12.dp), onClick = onClick, contentPadding = Dp16.Normal) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(program.displayName(), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                ProgramTags(program, active)
                Spacer(Modifier.height(8.dp))
                // What a built-in is like, in a sentence; the user's own are known by their days.
                if (program.isBuiltIn) Text(program.displayShort(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else Text(
                    program.days.map { it.displayName() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * "More programs (6)" over who they are for, and a chevron: the folded built-ins. When every one of
 * them is above the person's level they are said to be for experienced lifters; otherwise just a
 * less close fit, since a beginner after strength alone folds the beginner-friendly ones too.
 */
@Composable
internal fun MoreProgramsRow(count: Int, allAbove: Boolean, open: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    GainsCard(modifier.fillMaxWidth().padding(bottom = 12.dp), onClick = onToggle, contentPadding = Dp16.Normal) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(Res.string.more_programs, count), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(if (allAbove) Res.string.more_programs_blurb_beginner else Res.string.more_programs_blurb),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(if (open) "⌃" else "›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
