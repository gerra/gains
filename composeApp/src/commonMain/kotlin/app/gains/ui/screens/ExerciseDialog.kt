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
import app.gains.catalogue.NameNormalizer
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

internal class ExerciseFormModel(private val exercises: ExerciseRepository, reporter: ErrorReporter) : ScreenModel(reporter) {
    /** Matches a typed name against the catalogue, the lifter's own exercises and their merge mappings. */
    val resolver: StateFlow<ExerciseResolver?> = combine(exercises.observeExercises(), exercises.observeAliases()) { known, aliases -> ExerciseResolver(known, aliases) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** Stores [exercise]: a new one when [previous] is null, else [previous] rewritten under its own id. */
    fun save(exercise: Exercise, previous: Exercise?, onFailure: () -> Unit, onSaved: (Exercise) -> Unit) {
        scope.launchAction(onFailure = { onFailure() }) {
            if (previous == null) exercises.insertIfMissing(listOf(exercise)) else exercises.updateCustom(exercise, previous.name)
            onSaved(exercise)
        }
    }
}

/**
 * Defines a custom exercise: its name, how its sets are logged and what it trains. With [editing]
 * it changes one the lifter made (the id and every logged set stay); otherwise it makes a new one,
 * starting from [draft] when a search box already typed the name and guessed the rest.
 *
 * A name that is already taken is not made twice. When it is one of the lifter's own exercises the
 * dialog offers to edit that one, keeping what was already picked here; when it is a built-in,
 * [existingAction] hands it to [onExisting] (open its page, or pick it). [onSaved] gets the exercise
 * as stored.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ExerciseDialog(
    onDismiss: () -> Unit,
    onSaved: (Exercise) -> Unit,
    editing: Exercise? = null,
    draft: Exercise? = null,
    onExisting: (Exercise) -> Unit = {},
    existingAction: String = stringResource(Res.string.new_exercise_open_existing),
) {
    val model = rememberScreenModel { ExerciseFormModel(inject(), inject()) }
    val resolver by model.resolver.collectAsState()
    val palette = GainsColors.palette
    val start = editing ?: draft
    /** The custom exercise being changed; null while making a new one. */
    var target by remember { mutableStateOf(editing) }
    var name by remember { mutableStateOf(start?.name ?: "") }
    var modality by remember { mutableStateOf(start?.modality ?: Modality.WEIGHTED) }
    var perDumbbell by remember { mutableStateOf(start?.isDumbbell ?: false) }
    var credits by remember { mutableStateOf(start?.credits() ?: emptyMap()) }
    /** How it is logged or what it trains was picked by hand, so taking over an existing exercise keeps it. */
    var touched by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    val trimmed = name.trim()
    // An id is a name's normal form, so a name that maps onto a taken id is taken too, even when no lookup finds it.
    val taken = if (trimmed.isEmpty()) null else resolver?.let { it.find(trimmed) ?: it.exercise(NameNormalizer.toId(trimmed)) }
    val clash = taken?.takeIf { it.id != target?.id }
    val editingNow = target

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.large,
        title = { Text(stringResource(if (editingNow != null) Res.string.edit_exercise else Res.string.new_exercise_title)) },
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
                if (clash != null) {
                    Spacer(Modifier.height(8.dp))
                    val note = when {
                        editingNow != null -> stringResource(Res.string.edit_exercise_name_taken, clash.displayName())
                        !clash.isBuiltIn -> stringResource(Res.string.new_exercise_yours_exists, clash.displayName())
                        else -> stringResource(Res.string.new_exercise_exists, clash.displayName())
                    }
                    Text(note, style = MaterialTheme.typography.bodySmall, color = palette.amber)
                }

                Label(stringResource(Res.string.new_exercise_logged_as))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (m in Modality.entries) Chip(m.label(), if (modality == m) Credit.PRIMARY else Credit.NONE) { modality = m; touched = true }
                }
                Spacer(Modifier.height(8.dp))
                Text(modality.loggingHint(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (modality == Modality.WEIGHTED) {
                    Spacer(Modifier.height(8.dp))
                    Chip(stringResource(Res.string.per_dumbbell), if (perDumbbell) Credit.PRIMARY else Credit.NONE) { perDumbbell = !perDumbbell; touched = true }
                }
                if (editingNow != null && modality != editingNow.modality) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(Res.string.edit_exercise_sets_stay), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Label(stringResource(Res.string.new_exercise_muscles))
                // Cardio keeps its muscles for the exercise's page; weekly volume counts strength work only.
                val hint = if (modality == Modality.CARDIO) Res.string.new_exercise_muscles_hint_cardio else Res.string.new_exercise_muscles_hint
                Text(stringResource(hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (g in MuscleGroup.entries) {
                        val credit = credits[g] ?: Credit.NONE
                        Chip(g.label(), credit) {
                            val next = when (credit) { Credit.NONE -> Credit.PRIMARY; Credit.PRIMARY -> Credit.SECONDARY; Credit.SECONDARY -> Credit.NONE }
                            credits = if (next == Credit.NONE) credits - g else credits + (g to next)
                            touched = true
                        }
                    }
                }
            }
        },
        confirmButton = {
            when {
                // Making a new one under a name the lifter already made: change that one instead.
                clash != null && editingNow == null && !clash.isBuiltIn -> PrimaryButton(stringResource(Res.string.new_exercise_edit_existing), onClick = {
                    target = clash
                    name = clash.name
                    if (!touched) {
                        modality = clash.modality
                        perDumbbell = clash.isDumbbell
                        credits = clash.credits()
                    }
                })
                clash != null && editingNow == null -> PrimaryButton(existingAction, onClick = { onExisting(clash) })
                else -> PrimaryButton(
                    stringResource(if (editingNow != null) Res.string.save else Res.string.new_exercise_create),
                    enabled = trimmed.isNotEmpty() && resolver != null && clash == null && !saving,
                    onClick = {
                        saving = true
                        val made = ExerciseCatalogue.custom(
                            trimmed, modality,
                            primary = credits.filterValues { it == Credit.PRIMARY }.keys,
                            secondary = credits.filterValues { it == Credit.SECONDARY }.keys,
                            isDumbbell = perDumbbell,
                        )
                        model.save(editingNow?.let { made.copy(id = it.id) } ?: made, editingNow, onFailure = { saving = false }, onSaved = onSaved)
                    },
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}

/** The chips an exercise's muscles light up: full credit is a main muscle, anything less a secondary one. */
private fun Exercise.credits(): Map<MuscleGroup, Credit> =
    muscleGroups.associate { it.group to if (it.weight >= 1.0) Credit.PRIMARY else Credit.SECONDARY }

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
        text,
        style = MaterialTheme.typography.titleSmall,
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
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = text)
    }
}
