package app.gains.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.gains.analysis.Dates
import app.gains.analysis.ExerciseNotes
import app.gains.domain.Exercise
import app.gains.resources.Res
import app.gains.resources.close
import app.gains.resources.default_note
import app.gains.resources.default_note_explainer
import app.gains.resources.default_note_placeholder
import app.gains.resources.done
import app.gains.resources.no_past_notes
import app.gains.resources.notes_on
import app.gains.resources.past_notes
import app.gains.ui.components.PrimaryButton
import app.gains.ui.components.SectionHeader
import app.gains.ui.i18n.dateContextual
import app.gains.ui.i18n.displayName
import app.gains.ui.theme.GainsColors
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * Everything written about one exercise, opened from the note line of its workout card: the
 * default note, shown on the card whenever a workout's own note is empty, and every note written in
 * a workout before, newest first. The default note is kept however the sheet is closed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExerciseNotesSheet(
    exercise: Exercise,
    defaultNote: String,
    past: List<ExerciseNotes.Past>,
    onSaveDefault: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val palette = GainsColors.palette
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var text by remember(exercise.id) { mutableStateOf(defaultNote) }
    val close = {
        if (text.trim() != defaultNote.trim()) onSaveDefault(text)
        onDismiss()
    }
    val hideThenClose = { scope.launch { sheetState.hide() }.invokeOnCompletion { close() }; Unit }
    ModalBottomSheet(
        onDismissRequest = close,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp).navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.notes_on, exercise.displayName()), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                val closeLabel = stringResource(Res.string.close)
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable(onClick = hideThenClose).semantics { role = Role.Button; contentDescription = closeLabel },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.Close, null, modifier = Modifier.size(18.dp)) }
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                Spacer(Modifier.height(20.dp))
                val fieldLabel = stringResource(Res.string.default_note)
                Text(fieldLabel, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(Res.string.default_note_explainer), style = MaterialTheme.typography.bodySmall, color = muted)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    text, { text = it },
                    placeholder = { Text(stringResource(Res.string.default_note_placeholder)) },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = fieldLabel },
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = palette.volt, unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant, cursorColor = palette.volt),
                    shape = MaterialTheme.shapes.medium,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    maxLines = 4,
                )
                SectionHeader(stringResource(Res.string.past_notes))
                if (past.isEmpty()) {
                    Text(stringResource(Res.string.no_past_notes), style = MaterialTheme.typography.bodySmall, color = muted)
                } else {
                    val today = Dates.today()
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        past.forEach { note ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(dateContextual(note.date, today), style = MaterialTheme.typography.labelMedium, color = muted)
                                Text(note.note, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            PrimaryButton(stringResource(Res.string.done), hideThenClose, Modifier.fillMaxWidth())
        }
    }
}

/** A clock with an arrow running back round it: the notes written before. Material's "history" mark. */
internal val NotesHistoryIcon: ImageVector by lazy {
    ImageVector.Builder("NotesHistory", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            addPathNodes(
                "M13,3c-4.97,0 -9,4.03 -9,9L1,12l3.89,3.89 0.07,0.14L9,12L6,12c0,-3.87 3.13,-7 7,-7s7,3.13 7,7 -3.13,7 -7,7" +
                    "c-1.93,0 -3.68,-0.79 -4.94,-2.06l-1.42,1.42C8.27,19.99 10.51,21 13,21c4.97,0 9,-4.03 9,-9s-4.03,-9 -9,-9z" +
                    "M12,8v5l4.28,2.54 0.72,-1.21 -3.5,-2.08L13.5,8L12,8z",
            ),
            fill = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Black),
        )
    }.build()
}
