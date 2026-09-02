package app.gains

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import app.gains.platform.CsvFilePicker
import app.gains.platform.IncomingFiles
import app.gains.platform.PickedFile

class MainActivity : ComponentActivity() {
    private var pendingPick: ((PickedFile?) -> Unit)? = null

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val callback = pendingPick
        pendingPick = null
        callback?.invoke(uri?.let { read(it) })
    }

    private val filePicker = CsvFilePicker { onResult ->
        pendingPick = onResult
        openDocument.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/octet-stream", "*/*"))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            App(filePicker = filePicker)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** ACTION_VIEW / ACTION_SEND from a file manager or the share sheet. */
    private fun handleIntent(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> @Suppress("DEPRECATION") (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            else -> null
        }
        uri?.let { read(it) }?.let { IncomingFiles.offer(it) }
    }

    private fun read(uri: Uri): PickedFile? = runCatching {
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "export.csv"
        val content = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return null
        PickedFile(name, content)
    }.getOrNull()
}
