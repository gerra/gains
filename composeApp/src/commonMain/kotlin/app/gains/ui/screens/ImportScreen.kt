package app.gains.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gains.analysis.Dates
import app.gains.analysis.Format
import app.gains.csv.CsvFormatException
import app.gains.domain.WeightUnit
import app.gains.importer.ImportPreview
import app.gains.importer.ImportResult
import app.gains.importer.ImportService
import app.gains.platform.CsvFilePicker
import app.gains.platform.IncomingFiles
import app.gains.platform.PickedFile
import app.gains.ui.ScreenModel
import app.gains.ui.components.ChipRow
import app.gains.ui.components.EmptyState
import app.gains.ui.components.KeyValueRow
import app.gains.ui.components.SectionHeader
import app.gains.ui.inject
import app.gains.ui.rememberScreenModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ImportState {
    data object Idle : ImportState
    data class Parsing(val fileName: String) : ImportState
    data class Preview(val fileName: String, val preview: ImportPreview, val confirmedOutliers: Set<String>, val unit: WeightUnit) : ImportState
    data object Committing : ImportState
    data class Done(val result: ImportResult) : ImportState
    data class Error(val message: String) : ImportState
}

class ImportModel(private val importService: ImportService = inject()) : ScreenModel() {
    private val _state = MutableStateFlow<ImportState>(ImportState.Idle)
    val state: StateFlow<ImportState> = _state
    private var lastFile: PickedFile? = null

    fun load(file: PickedFile, unit: WeightUnit = WeightUnit.LBS) {
        lastFile = file
        _state.value = ImportState.Parsing(file.name)
        scope.launch {
            try {
                val preview = importService.preview(file.content, unit)
                _state.value = ImportState.Preview(file.name, preview, emptySet(), unit)
            } catch (e: CsvFormatException) {
                _state.value = ImportState.Error(e.message ?: "Could not read the file.")
            } catch (e: Exception) {
                _state.value = ImportState.Error("Import failed: ${e.message ?: e::class.simpleName}")
            }
        }
    }

    fun setUnit(unit: WeightUnit) {
        val file = lastFile ?: return
        load(file, unit)
    }

    fun toggleOutlier(key: String) {
        _state.update { s ->
            if (s !is ImportState.Preview) s
            else s.copy(confirmedOutliers = if (key in s.confirmedOutliers) s.confirmedOutliers - key else s.confirmedOutliers + key)
        }
    }

    fun setAllOutliers(confirmed: Boolean) {
        _state.update { s ->
            if (s !is ImportState.Preview) s
            else s.copy(confirmedOutliers = if (confirmed) s.preview.outliers.map { it.key }.toSet() else emptySet())
        }
    }

    fun commit() {
        val s = _state.value as? ImportState.Preview ?: return
        _state.value = ImportState.Committing
        scope.launch {
            try {
                _state.value = ImportState.Done(importService.commit(s.preview, s.confirmedOutliers))
            } catch (e: Exception) {
                _state.value = ImportState.Error("Saving failed: ${e.message ?: e::class.simpleName}")
            }
        }
    }

    fun reset() { _state.value = ImportState.Idle }
}

@Composable
fun ImportScreen(filePicker: CsvFilePicker, onDone: () -> Unit) {
    val model = rememberScreenModel { ImportModel() }
    val state by model.state.collectAsState()

    LaunchedEffect(Unit) {
        IncomingFiles.consume()?.let { model.load(it) }
    }
    val pick = { filePicker.pick { file -> if (file != null) model.load(file) } }

    when (val s = state) {
        ImportState.Idle -> EmptyState(
            title = "Import a Liftoff export",
            body = "Pick the CSV file. You'll see a summary before anything is saved. Re-importing an overlapping export is safe: sessions already stored are skipped.",
            action = { Button(onClick = pick) { Text("Choose CSV") } },
        )
        is ImportState.Parsing -> Centered { CircularProgressIndicator(); Spacer(Modifier.height(8.dp)); Text("Reading ${s.fileName}…") }
        ImportState.Committing -> Centered { CircularProgressIndicator(); Spacer(Modifier.height(8.dp)); Text("Saving…") }
        is ImportState.Error -> EmptyState("Couldn't import", s.message, action = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { model.reset() }) { Text("Back") }
                Button(onClick = pick) { Text("Choose another file") }
            }
        })
        is ImportState.Done -> EmptyState(
            title = "Imported",
            body = buildString {
                append(Format.plural(s.result.sessionsWritten, "session")).append(" saved")
                if (s.result.exercisesCreated > 0) append(", ").append(Format.plural(s.result.exercisesCreated, "new exercise")).append(" created")
                if (s.result.outliersDiscarded > 0) append(", ").append(Format.plural(s.result.outliersDiscarded, "outlier hold")).append(" discarded")
                append(".")
            },
            action = { Button(onClick = onDone) { Text("Done") } },
        )
        is ImportState.Preview -> PreviewContent(s, model, onCancel = { model.reset() })
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { content() }
}

@Composable
private fun PreviewContent(s: ImportState.Preview, model: ImportModel, onCancel: () -> Unit) {
    val p = s.preview
    val today = Dates.today()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        item {
            Text(s.fileName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("${Format.plural(p.rowCount, "row")} read", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SectionHeader("Weights in the file are in")
            ChipRow(WeightUnit.entries, s.unit, { it.label }, { model.setUnit(it) })
            Spacer(Modifier.height(4.dp))
            Text("Liftoff exports lbs even when you log in kg; they're converted and rounded to 0.25 kg.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            SectionHeader("Summary")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    val range = p.dateRange
                    KeyValueRow("Sessions found", p.candidates.size.toString())
                    KeyValueRow("Date range", if (range == null) "-" else "${Dates.contextual(range.start, today)} – ${Dates.contextual(range.endInclusive, today)}")
                    KeyValueRow("New sessions", p.newCount.toString())
                    if (p.changedCount > 0) KeyValueRow("Changed since last import", p.changedCount.toString())
                    if (p.unchangedCount > 0) KeyValueRow("Already imported (skipped)", p.unchangedCount.toString())
                    if (p.duplicates.isNotEmpty()) KeyValueRow("Duplicate sessions (skipped)", p.duplicates.size.toString())
                    if (p.corruptDurationCount > 0) KeyValueRow("Durations discarded (>4 h)", p.corruptDurationCount.toString())
                    if (p.newExercises.isNotEmpty()) KeyValueRow("New exercises", p.newExercises.size.toString())
                    for ((reason, count) in p.skippedByReason) KeyValueRow("Rows skipped: ${reason.label}", count.toString())
                }
            }
        }
        if (p.newExercises.isNotEmpty()) {
            item {
                SectionHeader("Exercises not in the catalogue")
                Text("These will be created as custom exercises. You can merge them into a catalogue exercise later in Settings.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(p.newExercises) { e ->
                Text("• ${e.name}" + if (e.muscleGroups.isEmpty()) "" else "  (${e.muscleGroups.joinToString { it.group.displayName }})", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp))
            }
        }
        if (p.duplicates.isNotEmpty()) {
            item { SectionHeader("Duplicates detected") }
            items(p.duplicates) { d ->
                Text(
                    "${Dates.contextual(d.date, today)}: ${d.exerciseNames.joinToString()} — logged twice" + if (d.keptIsAlreadyStored) " (already stored)" else "",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
        if (p.outliers.isNotEmpty()) {
            item {
                SectionHeader("Suspicious holds")
                Text("These isometric durations are more than 5× the usual hold for the exercise. Unticked holds are discarded.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row {
                    TextButton(onClick = { model.setAllOutliers(true) }) { Text("Keep all") }
                    TextButton(onClick = { model.setAllOutliers(false) }) { Text("Discard all") }
                }
            }
            items(p.outliers) { o ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = o.key in s.confirmedOutliers, onCheckedChange = { model.toggleOutlier(o.key) })
                    Spacer(Modifier.width(4.dp))
                    Column {
                        Text("${o.exerciseName} — ${Format.seconds(o.seconds)}", style = MaterialTheme.typography.bodyMedium)
                        Text("${Dates.contextual(o.date, today)}, set ${o.setOrder + 1}; usual hold ${Format.seconds(o.medianSeconds)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item {
            Spacer(Modifier.height(16.dp))
            val nothingToDo = p.newCount + p.changedCount == 0
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCancel, Modifier.weight(1f)) { Text("Cancel") }
                Button(onClick = { model.commit() }, Modifier.weight(1f), enabled = !nothingToDo) {
                    Text(if (nothingToDo) "Nothing new" else "Import ${p.newCount + p.changedCount}")
                }
            }
        }
    }
}
