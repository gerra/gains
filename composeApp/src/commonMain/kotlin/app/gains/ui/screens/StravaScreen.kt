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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.gains.analysis.Dates
import app.gains.analysis.Format
import app.gains.analysis.TrainingData
import app.gains.domain.Exercise
import app.gains.domain.Session
import app.gains.platform.IncomingLinks
import app.gains.platform.OAuthLauncher
import app.gains.strava.StravaConnection
import app.gains.strava.StravaCredentials
import app.gains.strava.StravaLink
import app.gains.strava.StravaMapper
import app.gains.strava.StravaService
import app.gains.strava.SyncDirection
import app.gains.strava.SyncReport
import app.gains.ui.ScreenModel
import app.gains.ui.components.Dp16
import app.gains.ui.components.GainsCard
import app.gains.ui.components.KeyValueRow
import app.gains.ui.components.Pill
import app.gains.ui.components.PrimaryButton
import app.gains.ui.components.ScreenTitle
import app.gains.ui.components.SecondaryButton
import app.gains.ui.components.SectionHeader
import app.gains.ui.inject
import app.gains.ui.rememberScreenModel
import app.gains.ui.theme.GainsColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock

enum class StravaBusy { CONNECTING, SYNCING, UPLOADING }

/** What the screen itself changes, kept apart from what is observed from the database. */
data class StravaUi(
    val clientId: String = "",
    val clientSecret: String = "",
    val busy: StravaBusy? = null,
    /** The authorisation URL of the attempt in progress, for hosts where the browser did not open. */
    val authUrl: String? = null,
    val message: String? = null,
    val error: String? = null,
    /** done to total during a bulk upload. */
    val progress: Pair<Int, Int>? = null,
)

data class StravaState(
    val connection: StravaConnection? = null,
    val credentials: StravaCredentials? = null,
    val lastSync: Long? = null,
    val links: List<StravaLink> = emptyList(),
    /** Sessions not on Strava yet, oldest first. */
    val uploadable: List<Session> = emptyList(),
    val exercisesById: Map<String, Exercise> = emptyMap(),
    val ui: StravaUi = StravaUi(),
) {
    val downloaded: Int get() = links.count { it.direction == SyncDirection.DOWNLOAD }
    val uploaded: Int get() = links.count { it.direction == SyncDirection.UPLOAD }
    val configured: Boolean get() = credentials != null
}

class StravaModel(
    private val service: StravaService = inject(),
    trainingData: TrainingData = inject(),
) : ScreenModel() {
    private val ui = MutableStateFlow(StravaUi())
    private var launcher: OAuthLauncher? = null

    val state: StateFlow<StravaState> = combine(
        combine(service.observeConnection(), service.observeCredentials(), service.observeLastSync()) { c, cr, l -> Triple(c, cr, l) },
        service.observeLinks(), service.observeUploadable(), trainingData.snapshot, ui,
    ) { (connection, credentials, lastSync), links, uploadable, snapshot, ui ->
        StravaState(connection, credentials, lastSync, links, uploadable, snapshot.exercisesById, ui)
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), StravaState())

    init {
        scope.launch {
            service.observeCredentials().first()?.let { c -> ui.update { it.copy(clientId = c.clientId, clientSecret = c.clientSecret) } }
        }
        // The platform posts the URL Strava redirected to; finish the sign-in with it.
        scope.launch {
            IncomingLinks.pending.collect { url -> if (url != null) { IncomingLinks.consume(); complete(url) } }
        }
    }

    fun setClientId(v: String) = ui.update { it.copy(clientId = v) }
    fun setClientSecret(v: String) = ui.update { it.copy(clientSecret = v) }

    fun connect(launcher: OAuthLauncher) {
        this.launcher = launcher
        scope.launch {
            try {
                val u = ui.value
                service.saveCredentials(u.clientId, u.clientSecret)
                val url = service.beginAuthorization(launcher.redirectUri(), launcher.mobile)
                ui.update { it.copy(busy = StravaBusy.CONNECTING, authUrl = url, error = null, message = null) }
                launcher.open(url)
            } catch (e: Exception) {
                ui.update { it.copy(error = e.message ?: "Could not start the Strava sign-in.", busy = null, authUrl = null) }
            }
        }
    }

    fun cancelConnect() {
        service.cancelAuthorization()
        launcher?.cancel()
        ui.update { it.copy(busy = null, authUrl = null) }
    }

    private suspend fun complete(url: String) {
        ui.update { it.copy(busy = StravaBusy.CONNECTING, error = null) }
        try {
            val connection = service.completeAuthorization(url)
            launcher?.cancel()
            ui.update {
                it.copy(
                    busy = null, authUrl = null,
                    message = "Connected as ${connection.athleteName}.",
                    error = if (connection.canWrite) null else "Strava did not grant permission to upload. Disconnect and connect again with \"Upload your activities\" ticked.",
                )
            }
            if (connection.canRead) sync()
        } catch (e: Exception) {
            ui.update { it.copy(busy = null, authUrl = null, error = e.message ?: "Connecting to Strava failed.") }
        }
    }

    fun sync() {
        scope.launch {
            ui.update { it.copy(busy = StravaBusy.SYNCING, error = null, message = null) }
            try {
                val report = service.syncActivities()
                ui.update { it.copy(busy = null, message = report.summary()) }
            } catch (e: Exception) {
                ui.update { it.copy(busy = null, error = e.message ?: "Sync failed.") }
            }
        }
    }

    fun upload(sessionId: String) {
        scope.launch {
            ui.update { it.copy(busy = StravaBusy.UPLOADING, error = null, message = null) }
            try {
                service.upload(sessionId)
                ui.update { it.copy(busy = null, message = "Uploaded to Strava.") }
            } catch (e: Exception) {
                ui.update { it.copy(busy = null, error = e.message ?: "Upload failed.") }
            }
        }
    }

    fun uploadAll() {
        scope.launch {
            ui.update { it.copy(busy = StravaBusy.UPLOADING, error = null, message = null, progress = 0 to state.value.uploadable.size) }
            val report = service.uploadAll { done, total -> ui.update { it.copy(progress = done to total) } }
            ui.update {
                it.copy(
                    busy = null, progress = null,
                    message = "Uploaded ${Format.plural(report.uploaded, "workout")}." + if (report.remaining > 0) " ${report.remaining} still to go." else "",
                    error = report.error,
                )
            }
        }
    }

    fun disconnect() {
        scope.launch {
            service.disconnect()
            ui.update { it.copy(message = "Disconnected. Imported sessions stay in your history.", error = null) }
        }
    }

    fun dismissMessages() = ui.update { it.copy(message = null, error = null) }

    companion object {
        fun SyncReport.summary(): String = buildString {
            append(if (imported == 0) "No new activities on Strava." else "Imported ${Format.plural(imported, "activity", "activities")} from Strava.")
            if (alreadyLinked > 0) append(" $alreadyLinked already here.")
            if (skippedCount > 0) append(" Skipped " + skipped.entries.joinToString(", ") { (sport, n) -> "$n ${StravaMapper.humanize(sport).lowercase()}" } + ".")
        }

        /** "just now", "12 min ago", "3 h ago", "2 days ago". */
        fun ago(epochSeconds: Long, now: Long = Clock.System.now().epochSeconds): String {
            val s = (now - epochSeconds).coerceAtLeast(0)
            return when {
                s < 60 -> "just now"
                s < 3600 -> "${s / 60} min ago"
                s < 86400 -> "${s / 3600} h ago"
                else -> Format.plural((s / 86400).toInt(), "day") + " ago"
            }
        }
    }
}

@Composable
fun StravaScreen(launcher: OAuthLauncher) {
    val model = rememberScreenModel { StravaModel() }
    val state by model.state.collectAsState()
    val ui = state.ui
    val palette = GainsColors.palette
    var confirmUploadAll by remember { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    val fieldColors = OutlinedTextFieldDefaults.colors(focusedBorderColor = palette.volt, unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant)
    val connection = state.connection

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
        item {
            ScreenTitle(
                "Strava",
                subtitle = connection?.let { "Connected as ${it.athleteName}" } ?: "Runs and rides in, workouts out",
            )
            ui.error?.let { StatusLine(it, MaterialTheme.colorScheme.error) }
            ui.message?.let { StatusLine(it, palette.volt) }
        }
        if (connection == null) {
            item { ConnectCard(state, model, launcher, fieldColors) }
        } else {
            item {
                SectionHeader("From Strava")
                GainsCard(Modifier.fillMaxWidth()) {
                    KeyValueRow("Last sync", state.lastSync?.let { StravaModel.ago(it) } ?: "Never")
                    KeyValueRow("Activities imported", state.downloaded.toString())
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Every run, ride, swim, walk and other cardio activity becomes a session in History with distance and time. Gym sessions on Strava are left out, and anything Gains uploaded is never imported back.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    if (ui.busy == StravaBusy.SYNCING) BusyRow("Fetching activities…")
                    else PrimaryButton("Sync now", onClick = model::sync, Modifier.fillMaxWidth(), enabled = ui.busy == null && connection.canRead)
                    if (!connection.canRead) {
                        Spacer(Modifier.height(6.dp))
                        Text("Strava did not grant permission to read activities. Disconnect and connect again.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                SectionHeader("To Strava")
                GainsCard(Modifier.fillMaxWidth()) {
                    KeyValueRow("Workouts uploaded", state.uploaded.toString())
                    KeyValueRow("Not on Strava yet", state.uploadable.size.toString())
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "A workout is uploaded as a Weight Training activity whose description lists every exercise and set; a lone run or ride goes up as that sport with its distance and time. Strava allows about a hundred uploads every 15 minutes, so a long history takes a few rounds.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    when {
                        ui.busy == StravaBusy.UPLOADING -> BusyRow(ui.progress?.let { (done, total) -> "Uploading $done of $total…" } ?: "Uploading…")
                        !connection.canWrite -> Text("Strava did not grant permission to upload. Disconnect and connect again with \"Upload your activities\" ticked.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        state.uploadable.isNotEmpty() -> SecondaryButton(
                            if (state.uploadable.size == 1) "Upload 1 workout" else "Upload all ${state.uploadable.size} workouts",
                            onClick = { if (state.uploadable.size == 1) model.uploadAll() else confirmUploadAll = true },
                            Modifier.fillMaxWidth(),
                        )
                        else -> Text("Everything logged here is on Strava.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (state.uploadable.isNotEmpty() && connection.canWrite) {
                item { SectionHeader("Not on Strava yet") }
                items(state.uploadable.asReversed().take(30), key = { it.id }) { session ->
                    UploadRow(session, state.exercisesById, enabled = ui.busy == null, onUpload = { model.upload(session.id) })
                }
                if (state.uploadable.size > 30) item {
                    Text("and ${state.uploadable.size - 30} more, oldest first when uploading all", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                }
            }
            item {
                SectionHeader("Account")
                GainsCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(connection.athleteName, style = MaterialTheme.typography.titleMedium)
                            Text("Athlete ${connection.athleteId} · ${connection.scopes.joinToString(", ").ifBlank { "no scopes reported" }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { confirmDisconnect = true }) { Text("Disconnect", color = palette.coral) }
                    }
                }
            }
        }
    }

    if (confirmUploadAll) {
        AlertDialog(
            onDismissRequest = { confirmUploadAll = false },
            shape = MaterialTheme.shapes.large,
            title = { Text("Upload ${state.uploadable.size} workouts?") },
            text = { Text("Each becomes an activity on Strava, oldest first. Strava's limit is about a hundred uploads every 15 minutes; if it is reached, come back later and upload the rest.") },
            confirmButton = { PrimaryButton("Upload", onClick = { confirmUploadAll = false; model.uploadAll() }) },
            dismissButton = { TextButton(onClick = { confirmUploadAll = false }) { Text("Cancel") } },
        )
    }
    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            shape = MaterialTheme.shapes.large,
            title = { Text("Disconnect Strava?") },
            text = { Text("Gains forgets the connection and revokes its access on Strava. Imported sessions stay in your history and nothing on Strava is deleted.") },
            confirmButton = { PrimaryButton("Disconnect", onClick = { confirmDisconnect = false; model.disconnect() }) },
            dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ConnectCard(state: StravaState, model: StravaModel, launcher: OAuthLauncher, fieldColors: androidx.compose.material3.TextFieldColors) {
    val palette = GainsColors.palette
    val ui = state.ui
    SectionHeader("Connect")
    GainsCard(Modifier.fillMaxWidth()) {
        if (ui.busy == StravaBusy.CONNECTING) {
            BusyRow("Waiting for Strava…")
            Spacer(Modifier.height(8.dp))
            Text(
                if (launcher.mobile) "Approve Gains in Strava and you will be brought back here."
                else "Approve Gains in the browser that just opened; this screen updates by itself.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ui.authUrl?.let { url ->
                Spacer(Modifier.height(10.dp))
                Text("Nothing opened? Copy the link into a browser:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SelectionContainer { Text(url, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3) }
                val clipboard = LocalClipboardManager.current
                Row {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(url)) }) { Text("Copy link", color = palette.volt) }
                    TextButton(onClick = model::cancelConnect) { Text("Cancel", color = palette.coral) }
                }
            }
        } else {
            Text(
                "Gains uses your own Strava API application, so nothing goes through anyone else's server. " +
                    "Create one at strava.com/settings/api (free, takes a minute) with \"localhost\" as the Authorization Callback Domain, then paste its client id and secret here. They are stored only on this device.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(ui.clientId, model::setClientId, label = { Text("Client ID") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = fieldColors, shape = MaterialTheme.shapes.medium)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(ui.clientSecret, model::setClientSecret, label = { Text("Client secret") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = fieldColors, shape = MaterialTheme.shapes.medium, visualTransformation = PasswordVisualTransformation())
            Spacer(Modifier.height(14.dp))
            PrimaryButton(
                "Connect Strava",
                onClick = { model.connect(launcher) },
                Modifier.fillMaxWidth(),
                enabled = ui.clientId.isNotBlank() && ui.clientSecret.isNotBlank(),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Strava will ask to let Gains view your activities, including private ones, and upload activities. Both are needed for a two-way sync.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun UploadRow(session: Session, exercisesById: Map<String, Exercise>, enabled: Boolean, onUpload: () -> Unit) {
    val palette = GainsColors.palette
    GainsCard(Modifier.fillMaxWidth().padding(bottom = 6.dp), contentPadding = Dp16.Tight) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(Dates.shortWithYear(session.date), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.width(8.dp))
                    Pill(Format.plural(session.setCount, "set"), palette.muted)
                }
                Text(
                    session.exercises.joinToString(" · ") { exercisesById[it.exerciseId]?.name ?: it.exerciseId },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                )
            }
            TextButton(onClick = onUpload, enabled = enabled) { Text("Upload", color = palette.volt) }
        }
    }
}

@Composable
private fun BusyRow(text: String) {
    val palette = GainsColors.palette
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(color = palette.volt, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun StatusLine(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.padding(bottom = 8.dp))
}
