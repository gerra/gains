package app.gains

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import app.gains.platform.CsvFilePicker
import app.gains.platform.IncomingFiles
import app.gains.platform.IncomingLinks
import app.gains.platform.OAuthLauncher
import app.gains.platform.PickedFile

class MainActivity : ComponentActivity() {
    private var pendingPick: ((List<PickedFile>) -> Unit)? = null

    private val openDocuments = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        val callback = pendingPick
        pendingPick = null
        callback?.invoke(uris.mapNotNull { read(it) })
    }

    private val filePicker = CsvFilePicker { onResult ->
        pendingPick = onResult
        openDocuments.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/octet-stream", "*/*"))
    }

    /** Strava's consent page opens in the browser; the gains:// redirect comes back through the intent filter. */
    private val oauth = object : OAuthLauncher {
        override val mobile: Boolean get() = true
        override fun redirectUri(): String = "gains://localhost/strava"
        override fun open(url: String) {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            // The system back button / predictive back gesture pops the navigator while it has
            // somewhere to go; at the root the callback is disabled so the system leaves the app.
            App(filePicker = filePicker, oauth = oauth, systemBack = { enabled, onBack -> BackHandler(enabled, onBack) })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** ACTION_VIEW / ACTION_SEND / ACTION_SEND_MULTIPLE from a file manager or the share sheet, or the OAuth redirect. */
    @Suppress("DEPRECATION")
    private fun handleIntent(intent: Intent?) {
        val data = intent?.data
        if (intent?.action == Intent.ACTION_VIEW && data?.scheme == "gains") {
            IncomingLinks.offer(data.toString())
            return
        }
        val uris: List<Uri> = when (intent?.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            else -> emptyList()
        }
        IncomingFiles.offer(uris.mapNotNull { read(it) })
    }

    private fun read(uri: Uri): PickedFile? = runCatching {
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "export.csv"
        val content = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return null
        PickedFile(name, content)
    }.getOrNull()
}
