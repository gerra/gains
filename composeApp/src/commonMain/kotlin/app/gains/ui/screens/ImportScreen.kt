package app.gains.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.gains.analysis.Dates
import app.gains.analysis.Format
import app.gains.csv.CsvFormatException
import app.gains.domain.WeightUnit
import app.gains.importer.CsvFile
import app.gains.importer.ImportPreview
import app.gains.importer.ImportResult
import app.gains.importer.ImportService
import app.gains.platform.CsvFilePicker
import app.gains.platform.IncomingFiles
import app.gains.platform.PickedFile
import app.gains.ui.ScreenModel
import app.gains.ui.components.ChipRow
import app.gains.ui.components.EmptyState
import app.gains.ui.components.GainsCard
import app.gains.ui.components.KeyValueRow
import app.gains.ui.components.PrimaryButton
import app.gains.ui.components.ScreenTitle
import app.gains.ui.components.SecondaryButton
import app.gains.ui.components.SectionHeader
import app.gains.ui.inject
import app.gains.ui.rememberScreenModel
import app.gains.ui.theme.GainsColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ImportState {
    data object Idle : ImportState
    data class Parsing(val fileCount: Int) : ImportState
    data class Preview(val preview: ImportPreview, val confirmedOutliers: Set<String>, val unit: WeightUnit) : ImportState
    data object Committing : ImportState
    data class Done(val result: ImportResult) : ImportState
    data class Error(val message: String) : ImportState
}

class ImportModel(private val importService: ImportService = inject()) : ScreenModel() {
    private val _state = MutableStateFlow<ImportState>(ImportState.Idle)
    val state: StateFlow<ImportState> = _state
    private var lastFiles: List<PickedFile> = emptyList()

    fun load(files: List<PickedFile>, unit: WeightUnit? = null) {
        if (files.isEmpty()) return
        lastFiles = files
        _state.value = ImportState.Parsing(files.size)
        scope.launch {
            try {
                val csvFiles = files.map { CsvFile(it.name, it.content) }
                val preview = importService.preview(csvFiles, unit)
                val shownUnit = unit ?: csvFiles.firstNotNullOfOrNull { importService.detect(it)?.defaultWeightUnit } ?: WeightUnit.KG
                _state.value = ImportState.Preview(preview, emptySet(), shownUnit)
            } catch (e: CsvFormatException) {
                _state.value = ImportState.Error(e.message ?: "Could not read the file.")
            } catch (e: Exception) {
                _state.value = ImportState.Error("Import failed: ${e.message ?: e::class.simpleName}")
            }
        }
    }

    fun setUnit(unit: WeightUnit) = load(lastFiles, unit)

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
    val palette = GainsColors.palette

    LaunchedEffect(Unit) {
        model.load(IncomingFiles.consume())
    }
    val pick = { filePicker.pick { files -> model.load(files) } }

    when (val s = state) {
        ImportState.Idle -> EmptyState(
            title = "Import your history",
            body = "Liftoff, Strong and Hevy exports are recognised automatically, and any CSV with date, exercise, weight and reps columns works too. Pick one or more files; you'll see a summary before anything is saved, and a session is only ever stored once.",
            emoji = "↑",
            action = { PrimaryButton("Choose CSV files", pick) },
        )
        is ImportState.Parsing -> Centered { CircularProgressIndicator(color = palette.volt); Spacer(Modifier.height(12.dp)); Text(if (s.fileCount == 1) "Reading the file…" else "Reading ${s.fileCount} files…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        ImportState.Committing -> Centered { CircularProgressIndicator(color = palette.volt); Spacer(Modifier.height(12.dp)); Text("Saving…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        is ImportState.Error -> EmptyState("Couldn't import", s.message, emoji = "!", action = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Back", onClick = { model.reset() })
                PrimaryButton("Choose another file", pick)
            }
        })
        is ImportState.Done -> EmptyState(
            title = "Imported",
            emoji = "✓",
            body = buildString {
                append(Format.plural(s.result.sessionsWritten, "session")).append(" saved")
                if (s.result.exercisesCreated > 0) append(", ").append(Format.plural(s.result.exercisesCreated, "new exercise")).append(" created")
                if (s.result.outliersDiscarded > 0) append(", ").append(Format.plural(s.result.outliersDiscarded, "outlier hold")).append(" discarded")
                append(".")
            },
            action = { PrimaryButton("Done", onDone) },
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
    val palette = GainsColors.palette
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
        item {
            ScreenTitle("Import", subtitle = "${Format.plural(p.files.size, "file")} · ${Format.plural(p.rowCount, "row")}")
            run {
                GainsCard(Modifier.fillMaxWidth(), contentPadding = app.gains.ui.components.Dp16.Tight) {
                    for (f in p.files) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(f.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    f.error ?: "${f.connector ?: "CSV"} · ${Format.plural(f.rowCount, "row")} · ${Format.plural(f.sessionCount, "session")}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (f.error != null) palette.coral else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (p.sessionsInSeveralFiles > 0) {
                        Text(
                            "${Format.plural(p.sessionsInSeveralFiles, "session")} appeared in more than one file and will be stored once.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
            SectionHeader("Weights in the file are in")
            ChipRow(WeightUnit.entries, s.unit, { it.label }, { model.setUnit(it) })
            Spacer(Modifier.height(6.dp))
            Text("Used for files that don't state their unit (Liftoff exports lbs even when you log in kg). Weights are stored in kg, rounded to 0.25 kg.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            SectionHeader("Summary")
            GainsCard(Modifier.fillMaxWidth()) {
                val range = p.dateRange
                KeyValueRow("Sessions found", p.candidates.size.toString())
                KeyValueRow("Date range", if (range == null) "-" else "${Dates.contextual(range.start, today)} – ${Dates.contextual(range.endInclusive, today)}")
                KeyValueRow("New sessions", p.newCount.toString(), valueColor = if (p.newCount > 0) palette.volt else null)
                if (p.changedCount > 0) KeyValueRow("Changed since last import", p.changedCount.toString(), valueColor = palette.cyan)
                if (p.unchangedCount > 0) KeyValueRow("Already imported (skipped)", p.unchangedCount.toString())
                if (p.sessionsInSeveralFiles > 0) KeyValueRow("In more than one file (merged)", p.sessionsInSeveralFiles.toString())
                if (p.duplicates.isNotEmpty()) KeyValueRow("Duplicate sessions (skipped)", p.duplicates.size.toString(), valueColor = palette.amber)
                if (p.corruptDurationCount > 0) KeyValueRow("Durations discarded (>4 h)", p.corruptDurationCount.toString(), valueColor = palette.amber)
                if (p.newExercises.isNotEmpty()) KeyValueRow("New exercises", p.newExercises.size.toString())
                for ((reason, count) in p.skippedByReason) KeyValueRow("Rows skipped: ${reason.label}", count.toString(), valueColor = palette.muted)
            }
        }
        if (p.newExercises.isNotEmpty()) {
            item {
                SectionHeader("Exercises not in the catalogue")
                Text("These will be created as custom exercises. You can merge them into a catalogue exercise later in Settings.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
            }
            items(p.newExercises) { e ->
                GainsCard(Modifier.fillMaxWidth().padding(bottom = 8.dp), contentPadding = app.gains.ui.components.Dp16.Tight) {
                    Text(e.name, style = MaterialTheme.typography.titleSmall)
                    if (e.muscleGroups.isNotEmpty()) Text(e.muscleGroups.joinToString { it.group.displayName }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (p.duplicates.isNotEmpty()) {
            item { SectionHeader("Duplicates detected") }
            items(p.duplicates) { d ->
                GainsCard(Modifier.fillMaxWidth().padding(bottom = 8.dp), contentPadding = app.gains.ui.components.Dp16.Tight) {
                    Text("${Dates.contextual(d.date, today)} · logged twice" + if (d.keptIsAlreadyStored) " (already stored)" else "", style = MaterialTheme.typography.titleSmall)
                    Text(d.exerciseNames.joinToString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (p.outliers.isNotEmpty()) {
            item {
                SectionHeader("Suspicious holds", action = {
                    TextButton(onClick = { model.setAllOutliers(true) }) { Text("Keep all") }
                    TextButton(onClick = { model.setAllOutliers(false) }) { Text("Discard all") }
                })
                Text("These isometric durations are more than 5× the usual hold for the exercise. Unticked holds are discarded.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
            }
            items(p.outliers) { o ->
                GainsCard(Modifier.fillMaxWidth().padding(bottom = 8.dp), contentPadding = app.gains.ui.components.Dp16.Tight) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = o.key in s.confirmedOutliers, onCheckedChange = { model.toggleOutlier(o.key) },
                            colors = CheckboxDefaults.colors(checkedColor = palette.volt, checkmarkColor = MaterialTheme.colorScheme.onPrimary),
                        )
                        Spacer(Modifier.width(4.dp))
                        Column {
                            Text("${o.exerciseName} · ${Format.seconds(o.seconds)}", style = MaterialTheme.typography.titleSmall)
                            Text("${Dates.contextual(o.date, today)}, set ${o.setOrder + 1} · usual hold ${Format.seconds(o.medianSeconds)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        item {
            Spacer(Modifier.height(16.dp))
            val toWrite = p.commitCount(s.confirmedOutliers)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Cancel", onCancel, Modifier.weight(1f))
                PrimaryButton(if (toWrite == 0) "Nothing new" else "Import $toWrite", { model.commit() }, Modifier.weight(1f), enabled = toWrite > 0)
            }
        }
    }
}
