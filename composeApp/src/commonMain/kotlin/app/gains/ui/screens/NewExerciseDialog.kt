package app.gains.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.gains.ErrorReporter
import app.gains.catalogue.ExerciseCatalogue
import app.gains.data.ExerciseRepository
import app.gains.domain.Exercise
import app.gains.domain.Modality
import app.gains.domain.MuscleGroup
import app.gains.importer.ExerciseResolver
import app.gains.resources.Res
import app.gains.resources.*
import app.gains.ui.ScreenModel
import app.gains.ui.components.PrimaryButton
import app.gains.ui.i18n.*
import app.gains.ui.inject
import app.gains.ui.launchAction
import app.gains.ui.rememberScreenModel
import app.gains.ui.theme.GainsColors
import app.gains.ui.theme.Motion
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.jetbrains.compose.resources.stringResource

/** How much a muscle chip credits: tapping one steps it through none, primary and secondary. */
private enum class Credit { NONE, PRIMARY, SECONDARY }

internal class NewExerciseModel(private val exercises: ExerciseRepository, reporter: ErrorReporter) : ScreenModel(reporter) {
    /** Matches a typed name against the catalogue, the lifter's own exercises and their merge mappings. */
    val resolver: StateFlow<ExerciseResolver?> = combine(exercises.observeExercises(), exercises.observeAliases()) { known, aliases -> ExerciseResolver(known, aliases) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    fun create(exercise: Exercise, onFailure: () -> Unit, onCreated: (String) -> Unit) {
        scope.launchAction(onFailure = { onFailure() }) {
            exercises.insertIfMissing(listOf(exercise))
            onCreated(exercise.id)
        }
    }
}

/**
 * Defines a custom exercise from scratch: its name, how its sets are logged and what it trains.
 * A name the catalogue or the lifter's own exercises already know is not made twice: the dialog
 * names the existing one and offers to open it instead. [onOpen] gets the id of the exercise made
 * or found.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NewExerciseDialog(onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    val model = rememberScreenModel { NewExerciseModel(inject(), inject()) }
    val resolver by model.resolver.collectAsState()
    val palette = GainsColors.palette
    var name by remember { mutableStateOf("") }
    var modality by remember { mutableStateOf(Modality.WEIGHTED) }
    var perDumbbell by remember { mutableStateOf(false) }
    var credits by remember { mutableStateOf(emptyMap<MuscleGroup, Credit>()) }
    var creating by remember { mutableStateOf(false) }

    val trimmed = name.trim()
    val existing = if (trimmed.isEmpty()) null else resolver?.find(trimmed)

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.large,
        title = { Text(stringResource(Res.string.new_exercise_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    placeholder = { Text(stringResource(Res.string.new_exercise_name_hint)) }, singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = palette.volt,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (existing != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(Res.string.new_exercise_exists, existing.displayName()),
                        style = MaterialTheme.typography.bodySmall, color = palette.amber,
                    )
                }

                Label(stringResource(Res.string.new_exercise_logged_as))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (m in Modality.entries) Chip(m.label(), if (modality == m) Credit.PRIMARY else Credit.NONE) { modality = m }
                }
                Spacer(Modifier.height(6.dp))
                Text(modality.loggingHint(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (modality == Modality.WEIGHTED) {
                    Spacer(Modifier.height(8.dp))
                    Chip(stringResource(Res.string.per_dumbbell), if (perDumbbell) Credit.PRIMARY else Credit.NONE) { perDumbbell = !perDumbbell }
                }

                Label(stringResource(Res.string.new_exercise_muscles))
                // Cardio keeps its muscles for the exercise's page; weekly volume counts strength work only.
                val hint = if (modality == Modality.CARDIO) Res.string.new_exercise_muscles_hint_cardio else Res.string.new_exercise_muscles_hint
                Text(stringResource(hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (g in MuscleGroup.entries) {
                        val credit = credits[g] ?: Credit.NONE
                        Chip(g.label(), credit) {
                            val next = when (credits[g] ?: Credit.NONE) { Credit.NONE -> Credit.PRIMARY; Credit.PRIMARY -> Credit.SECONDARY; Credit.SECONDARY -> Credit.NONE }
                            credits = if (next == Credit.NONE) credits - g else credits + (g to next)
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (existing != null) {
                PrimaryButton(stringResource(Res.string.new_exercise_open_existing), onClick = { onOpen(existing.id) })
            } else {
                PrimaryButton(
                    stringResource(Res.string.new_exercise_create),
                    enabled = trimmed.isNotEmpty() && resolver != null && !creating,
                    onClick = {
                        creating = true
                        val exercise = ExerciseCatalogue.custom(
                            trimmed, modality,
                            primary = credits.filterValues { it == Credit.PRIMARY }.keys,
                            secondary = credits.filterValues { it == Credit.SECONDARY }.keys,
                            isDumbbell = perDumbbell,
                        )
                        model.create(exercise, onFailure = { creating = false }, onCreated = onOpen)
                    },
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}

@Composable
private fun Modality.loggingHint(): String = stringResource(when (this) {
    Modality.WEIGHTED -> Res.string.logged_as_weighted
    Modality.BODYWEIGHT -> Res.string.logged_as_bodyweight
    Modality.ISOMETRIC -> Res.string.logged_as_isometric
    Modality.CARDIO -> Res.string.logged_as_cardio
})

@Composable
private fun Label(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
    )
}

/** A pill that is filled when [credit] is primary, outlined when secondary and plain when off. */
@Composable
private fun Chip(label: String, credit: Credit, onClick: () -> Unit) {
    val palette = GainsColors.palette
    val fill by animateColorAsState(if (credit == Credit.PRIMARY) palette.volt else MaterialTheme.colorScheme.surfaceContainerHigh, Motion.standard(), label = "chip")
    val text by animateColorAsState(
        when (credit) {
            Credit.PRIMARY -> MaterialTheme.colorScheme.onPrimary
            Credit.SECONDARY -> palette.volt
            Credit.NONE -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        Motion.standard(), label = "chip-text",
    )
    Box(
        Modifier
            .clip(CircleShape)
            .background(fill)
            .border(1.5.dp, if (credit == Credit.SECONDARY) palette.volt else Color.Transparent, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = text)
    }
}
