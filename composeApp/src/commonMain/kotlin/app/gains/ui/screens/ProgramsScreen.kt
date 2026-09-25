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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gains.data.ProgramRepository
import app.gains.domain.GoalProfile
import app.gains.domain.Program
import app.gains.program.ProgramSuggester
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
    /** Built-ins, best match for the profile first. */
    val builtIn: List<Program> = emptyList(),
)

internal class ProgramsModel(programs: ProgramRepository = inject()) : ScreenModel() {
    val state: StateFlow<ProgramsState> = programs.observeState().map { s ->
        val builtIn = s.programs.filter { it.isBuiltIn }
        ProgramsState(
            loading = false,
            profile = s.profile,
            activeId = s.activeProgramId,
            custom = s.programs.filter { !it.isBuiltIn },
            builtIn = s.profile?.let { ProgramSuggester.suggest(it, builtIn) } ?: builtIn,
        )
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), ProgramsState())
}

@Composable
internal fun ProgramsScreen(onOpen: (String) -> Unit, onNew: () -> Unit) {
    val model = rememberScreenModel { ProgramsModel() }
    val state by model.state.collectAsState()
    val palette = GainsColors.palette
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
        item { SectionHeader(if (state.profile != null) stringResource(Res.string.built_in_best_fit) else stringResource(Res.string.built_in)) }
        items(state.builtIn, key = { it.id }) { ProgramRow(it, it.id == state.activeId, onClick = { onOpen(it.id) }, Modifier.animateItem()) }
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
    GainsCard(modifier.fillMaxWidth().padding(bottom = 8.dp), onClick = onClick, contentPadding = Dp16.Tight) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(program.displayName(), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                ProgramTags(program, active)
                Spacer(Modifier.height(6.dp))
                Text(
                    program.days.map { it.displayName() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
