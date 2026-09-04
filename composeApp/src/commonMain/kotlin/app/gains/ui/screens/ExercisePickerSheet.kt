package app.gains.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gains.analysis.Format
import app.gains.domain.Exercise
import app.gains.domain.Modality
import app.gains.domain.MuscleGroup
import app.gains.ui.components.Pill
import app.gains.ui.components.PrimaryButton
import app.gains.ui.components.RoundedIconBox
import app.gains.ui.theme.GainsColors
import kotlinx.coroutines.launch

/**
 * Full-height exercise picker in the style of Hevy / Strong / Liftoff: search on top, muscle-group
 * filter chips, a "Recent" section, then the catalogue in alphabetical sections. Rows are
 * multi-select; the sticky button at the bottom adds everything ticked at once. A name that matches
 * nothing can be created as a custom exercise straight from the search box.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExercisePickerSheet(
    catalogue: List<Exercise>,
    recent: List<Exercise>,
    /** Exercises already in the workout: shown but not selectable again. */
    alreadyAdded: Set<String>,
    onAdd: (List<Exercise>) -> Unit,
    /** Creates a custom exercise and returns it so it can be ticked immediately. Null hides the create row. */
    onCreate: ((String) -> Exercise)?,
    onDismiss: () -> Unit,
    /** Single-select: tapping a row adds it straight away. */
    single: Boolean = false,
) {
    val palette = GainsColors.palette
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var group by remember { mutableStateOf<MuscleGroup?>(null) }
    /** Ordered so exercises land in the workout in the order they were ticked. */
    var selected by remember { mutableStateOf(listOf<String>()) }

    val q = query.trim()
    val filtered = remember(catalogue, q, group) {
        catalogue.filter { e ->
            (group == null || e.muscleGroups.any { it.group == group }) &&
                (q.isEmpty() || e.name.contains(q, ignoreCase = true))
        }
    }
    val sections: List<Pair<String, List<Exercise>>> = remember(filtered) {
        filtered.groupBy { e -> e.name.firstOrNull()?.uppercaseChar()?.takeIf { it.isLetter() }?.toString() ?: "#" }
            .entries.sortedBy { it.key }.map { (letter, list) -> letter to list.sortedBy { it.name.lowercase() } }
    }
    val showRecent = q.isEmpty() && group == null && recent.isNotEmpty()
    val canCreate = onCreate != null && q.isNotEmpty() && catalogue.none { it.name.equals(q, ignoreCase = true) }
    val byId = remember(catalogue) { catalogue.associateBy { it.id } }

    fun close(then: () -> Unit = {}) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { then(); onDismiss() }
    }
    fun toggle(id: String) {
        if (single) { byId[id]?.let { e -> close { onAdd(listOf(e)) } }; return }
        selected = if (id in selected) selected - id else selected + id
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(Modifier.fillMaxHeight(0.94f).imePadding()) {
            // Header
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Add exercises", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        if (selected.isEmpty()) "${catalogue.size} in your library" else "${selected.size} selected",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable { close() },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.Close, "Close", modifier = Modifier.size(18.dp)) }
            }
            Spacer(Modifier.height(12.dp))

            // Search
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                placeholder = { Text("Search or type a new exercise") }, singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp)) },
                trailingIcon = if (query.isEmpty()) null else ({
                    Icon(Icons.Default.Close, "Clear", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp).clip(CircleShape).clickable { query = "" })
                }),
                shape = CircleShape,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = palette.volt,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(10.dp))

            // Muscle-group filter
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item { FilterChip("All", group == null) { group = null } }
                items(MuscleGroup.entries) { g -> FilterChip(g.displayName, group == g) { group = if (group == g) null else g } }
            }
            Spacer(Modifier.height(4.dp))

            // Results
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 12.dp)) {
                if (canCreate) {
                    item(key = "create") {
                        CreateRow(q) {
                            val created = onCreate(q)
                            selected = selected + created.id
                            query = ""
                        }
                    }
                }
                if (showRecent) {
                    item(key = "h:recent") { SectionLabel("Recent") }
                    items(recent, key = { "r:" + it.id }) { e ->
                        PickerRow(e, selected = e.id in selected, added = e.id in alreadyAdded) { toggle(e.id) }
                    }
                }
                if (filtered.isEmpty() && !canCreate) {
                    item {
                        Text(
                            if (group != null) "No exercises for ${group!!.displayName}${if (q.isNotEmpty()) " matching \"$q\"" else ""}." else "Nothing matches \"$q\".",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 24.dp),
                        )
                    }
                }
                for ((letter, list) in sections) {
                    item(key = "h:$letter") { SectionLabel(if (showRecent || sections.size > 1) letter else "Results") }
                    items(list, key = { it.id }) { e ->
                        PickerRow(e, selected = e.id in selected, added = e.id in alreadyAdded) { toggle(e.id) }
                    }
                }
            }

            // Sticky action
            Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 20.dp, vertical = 12.dp).navigationBarsPadding()) {
                PrimaryButton(
                    if (selected.isEmpty()) "Select exercises" else "Add ${Format.plural(selected.size, "exercise")}",
                    onClick = { val picked = selected.mapNotNull { byId[it] }; close { onAdd(picked) } },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = selected.isNotEmpty(),
                )
            }
        }
    }
}

@Composable
private fun FilterChip(label: String, active: Boolean, onClick: () -> Unit) {
    val palette = GainsColors.palette
    Box(
        Modifier
            .clip(CircleShape)
            .background(if (active) palette.volt else MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
    )
}

@Composable
private fun CreateRow(name: String, onClick: () -> Unit) {
    val palette = GainsColors.palette
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundedIconBox(palette.volt) { Icon(Icons.Default.Add, null, tint = palette.volt, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Create \"$name\"", style = MaterialTheme.typography.titleMedium, color = palette.volt)
            Text("Custom exercise · muscles guessed from the name", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PickerRow(e: Exercise, selected: Boolean, added: Boolean, onToggle: () -> Unit) {
    val palette = GainsColors.palette
    val primary = e.muscleGroups.maxByOrNull { it.weight }?.group
    val muscles = e.muscleGroups.sortedByDescending { it.weight }.joinToString(" · ") { it.group.displayName }
    val subtitle = buildList {
        if (muscles.isNotEmpty()) add(muscles)
        if (e.modality != Modality.WEIGHTED) add(e.modality.name.lowercase().replaceFirstChar { it.uppercase() })
        if (e.isDumbbell) add("per dumbbell")
    }.joinToString(" · ")
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(if (selected) palette.volt.copy(alpha = 0.10f) else Color.Transparent)
            .then(if (added) Modifier else Modifier.clickable(onClick = onToggle))
            .padding(horizontal = 6.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundedIconBox(muscleColor(primary)) {
            Text(muscleInitials(primary, e), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = muscleColor(primary))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(e.name, style = MaterialTheme.typography.titleMedium, color = if (added) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Spacer(Modifier.width(10.dp))
        if (added) {
            Pill("Added", palette.muted)
        } else {
            Box(
                Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(if (selected) palette.volt else Color.Transparent)
                    .border(1.5.dp, if (selected) palette.volt else MaterialTheme.colorScheme.outline, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** Two-letter tag for the icon box, e.g. "CH" for chest, "QU" for quads; falls back to the exercise initial. */
private fun muscleInitials(group: MuscleGroup?, e: Exercise): String = when (group) {
    null -> e.name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    MuscleGroup.FRONT_DELTS -> "FD"
    MuscleGroup.SIDE_DELTS -> "SD"
    MuscleGroup.REAR_DELTS -> "RD"
    MuscleGroup.UPPER_BACK -> "UB"
    MuscleGroup.LOWER_BACK -> "LB"
    else -> group.displayName.take(2).uppercase()
}

@Composable
private fun muscleColor(group: MuscleGroup?): Color {
    val p = GainsColors.palette
    return when (group) {
        MuscleGroup.CHEST, MuscleGroup.FRONT_DELTS, MuscleGroup.SIDE_DELTS, MuscleGroup.REAR_DELTS, MuscleGroup.TRICEPS -> p.coral
        MuscleGroup.LATS, MuscleGroup.UPPER_BACK, MuscleGroup.LOWER_BACK, MuscleGroup.TRAPS, MuscleGroup.BICEPS, MuscleGroup.FOREARMS -> p.cyan
        MuscleGroup.QUADS, MuscleGroup.HAMSTRINGS, MuscleGroup.GLUTES, MuscleGroup.CALVES -> p.violet
        MuscleGroup.CORE, MuscleGroup.NECK -> p.amber
        null -> p.muted
    }
}
