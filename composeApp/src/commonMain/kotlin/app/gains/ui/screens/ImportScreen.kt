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
import app.gains.csv.CsvProblem
import app.gains.resources.Res
import app.gains.resources.*
import app.gains.ui.i18n.*
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import app.gains.ui.inject
import app.gains.ui.rememberScreenModel
import app.gains.ui.theme.GainsColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal sealed interface ImportState {
    data object Idle : ImportState
    data class Parsing(val fileCount: Int) : ImportState
    data class Preview(val preview: ImportPreview, val confirmedOutliers: Set<String>, val unit: WeightUnit) : ImportState
    data object Committing : ImportState
    data class Done(val result: ImportResult) : ImportState
    /** [problem] when the connectors could not read the file, else [cause]: what went wrong while importing or saving. */
    data class Error(val problem: CsvProblem? = null, val cause: String? = null, val whileSaving: Boolean = false) : ImportState
}

internal class ImportModel(private val importService: ImportService = inject()) : ScreenModel() {
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
                _state.value = ImportState.Error(problem = e.problem)
            } catch (e: Exception) {
                _state.value = ImportState.Error(cause = e.message ?: e::class.simpleName)
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
                _state.value = ImportState.Error(cause = e.message ?: e::class.simpleName, whileSaving = true)
            }
        }
    }

    fun reset() { _state.value = ImportState.Idle }
}

@Composable
internal fun ImportScreen(filePicker: CsvFilePicker, onDone: () -> Unit) {
    val model = rememberScreenModel { ImportModel() }
    val state by model.state.collectAsState()
    val palette = GainsColors.palette

    LaunchedEffect(Unit) {
        model.load(IncomingFiles.consume())
    }
    val pick = { filePicker.pick { files -> model.load(files) } }

    when (val s = state) {
        ImportState.Idle -> EmptyState(
            title = stringResource(Res.string.import_your_history),
            body = stringResource(Res.string.import_blurb),
            emoji = "↑",
            action = { PrimaryButton(stringResource(Res.string.choose_csv_files), pick) },
        )
        is ImportState.Parsing -> Centered { CircularProgressIndicator(color = palette.volt); Spacer(Modifier.height(12.dp)); Text(if (s.fileCount == 1) pluralStringResource(Res.plurals.reading_files, 1, 1) else pluralStringResource(Res.plurals.reading_files, s.fileCount, s.fileCount), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        ImportState.Committing -> Centered { CircularProgressIndicator(color = palette.volt); Spacer(Modifier.height(12.dp)); Text(stringResource(Res.string.saving), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        is ImportState.Error -> {
            val message = when {
                s.problem != null -> csvProblemText(s.problem)
                s.whileSaving -> stringResource(Res.string.saving_failed, s.cause ?: "")
                s.cause != null -> stringResource(Res.string.import_failed, s.cause)
                else -> stringResource(Res.string.could_not_read_the_file)
            }
            EmptyState(stringResource(Res.string.could_not_import), message, emoji = "!", action = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton(stringResource(Res.string.back), onClick = { model.reset() })
                    PrimaryButton(stringResource(Res.string.choose_another_file), pick)
                }
            })
        }
        is ImportState.Done -> EmptyState(
            title = stringResource(Res.string.imported),
            emoji = "✓",
            body = importedSummary(s.result.sessionsWritten, s.result.exercisesCreated, s.result.outliersDiscarded),
            action = { PrimaryButton(stringResource(Res.string.done), onDone) },
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
    val palette = GainsColors.palette
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
        item {
            ScreenTitle(stringResource(Res.string.import_title), subtitle = "${pluralStringResource(Res.plurals.files, p.files.size, p.files.size)} · ${pluralStringResource(Res.plurals.rows, p.rowCount, p.rowCount)}")
            run {
                GainsCard(Modifier.fillMaxWidth(), contentPadding = app.gains.ui.components.Dp16.Tight) {
                    for (f in p.files) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(f.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    f.problem?.let { csvProblemText(it) } ?: f.error ?: "${f.connector ?: stringResource(Res.string.csv)} · ${pluralStringResource(Res.plurals.rows, f.rowCount, f.rowCount)} · ${sessionsText(f.sessionCount)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (f.error != null) palette.coral else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (p.sessionsInSeveralFiles > 0) {
                        Text(
                            stringResource(Res.string.appeared_in_several_files, sessionsText(p.sessionsInSeveralFiles)),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
            SectionHeader(stringResource(Res.string.weights_in_file_are_in))
            ChipRow(WeightUnit.entries, s.unit, { it.label() }, { model.setUnit(it) })
            Spacer(Modifier.height(6.dp))
            Text(stringResource(Res.string.unit_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            SectionHeader(stringResource(Res.string.summary))
            GainsCard(Modifier.fillMaxWidth()) {
                val range = p.dateRange
                KeyValueRow(stringResource(Res.string.sessions_found), p.candidates.size.toString())
                KeyValueRow(stringResource(Res.string.date_range), if (range == null) "-" else "${dateShortWithYear(range.start)} – ${dateShortWithYear(range.endInclusive)}")
                KeyValueRow(stringResource(Res.string.new_sessions), p.newCount.toString(), valueColor = if (p.newCount > 0) palette.volt else null)
                if (p.changedCount > 0) KeyValueRow(stringResource(Res.string.changed_since_last_import), p.changedCount.toString(), valueColor = palette.cyan)
                if (p.unchangedCount > 0) KeyValueRow(stringResource(Res.string.already_imported), p.unchangedCount.toString())
                if (p.sessionsInSeveralFiles > 0) KeyValueRow(stringResource(Res.string.in_more_than_one_file), p.sessionsInSeveralFiles.toString())
                if (p.duplicates.isNotEmpty()) KeyValueRow(stringResource(Res.string.duplicate_sessions), p.duplicates.size.toString(), valueColor = palette.amber)
                if (p.corruptDurationCount > 0) KeyValueRow(stringResource(Res.string.durations_discarded), p.corruptDurationCount.toString(), valueColor = palette.amber)
                if (p.newExercises.isNotEmpty()) KeyValueRow(stringResource(Res.string.new_exercises_label), p.newExercises.size.toString())
                for ((reason, count) in p.skippedByReason) KeyValueRow(if (reason == app.gains.csv.SkipReason.EMPTY_ROW) stringResource(Res.string.empty_rows_skipped) else stringResource(Res.string.rows_skipped, reason.label()), count.toString(), valueColor = palette.muted)
            }
        }
        if (p.newExercises.isNotEmpty()) {
            item {
                SectionHeader(stringResource(Res.string.exercises_not_in_catalogue))
                Text(stringResource(Res.string.exercises_not_in_catalogue_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
            }
            items(p.newExercises) { e ->
                GainsCard(Modifier.fillMaxWidth().padding(bottom = 8.dp), contentPadding = app.gains.ui.components.Dp16.Tight) {
                    Text(e.name, style = MaterialTheme.typography.titleSmall)
                    if (e.muscleGroups.isNotEmpty()) Text(e.muscleGroups.map { it.group.label() }.joinToString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (p.duplicates.isNotEmpty()) {
            item { SectionHeader(stringResource(Res.string.duplicates_detected)) }
            items(p.duplicates) { d ->
                GainsCard(Modifier.fillMaxWidth().padding(bottom = 8.dp), contentPadding = app.gains.ui.components.Dp16.Tight) {
                    Text(loggedTwiceText(dateShortWithYear(d.date), d.keptIsAlreadyStored), style = MaterialTheme.typography.titleSmall)
                    Text(d.exerciseNames.joinToString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (p.outliers.isNotEmpty()) {
            item {
                SectionHeader(stringResource(Res.string.suspicious_holds), action = {
                    TextButton(onClick = { model.setAllOutliers(true) }) { Text(stringResource(Res.string.keep_all)) }
                    TextButton(onClick = { model.setAllOutliers(false) }) { Text(stringResource(Res.string.discard_all)) }
                })
                Text(stringResource(Res.string.suspicious_holds_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
            }
            items(p.outliers.groupBy { "${it.date}|${it.exerciseId}" }.values.toList()) { group ->
                val o = group.first()
                val keys = group.map { it.key }.toSet()
                val checked = keys.all { it in s.confirmedOutliers }
                GainsCard(Modifier.fillMaxWidth().padding(bottom = 8.dp), contentPadding = app.gains.ui.components.Dp16.Tight) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { keep -> keys.forEach { key -> if ((key in s.confirmedOutliers) != keep) model.toggleOutlier(key) } },
                            colors = CheckboxDefaults.colors(checkedColor = palette.volt, checkmarkColor = MaterialTheme.colorScheme.onPrimary),
                        )
                        Spacer(Modifier.width(4.dp))
                        Column {
                            Text("${o.exerciseName} — ${dateShortWithYear(o.date)}", style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(Res.string.hold_outlier, setsText(group.size), secondsText(o.seconds), secondsText(o.medianSeconds)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        item {
            Spacer(Modifier.height(16.dp))
            val toWrite = p.commitCount(s.confirmedOutliers)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(stringResource(Res.string.cancel), onCancel, Modifier.weight(1f))
                PrimaryButton(if (toWrite == 0) stringResource(Res.string.nothing_new) else stringResource(Res.string.import_n, toWrite), { model.commit() }, Modifier.weight(1f), enabled = toWrite > 0)
            }
        }
    }
}
