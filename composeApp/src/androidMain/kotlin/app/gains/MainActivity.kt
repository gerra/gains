package app.gains

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import app.gains.platform.CsvFilePicker
import app.gains.platform.IncomingFiles
import app.gains.platform.PhotoPicker
import app.gains.platform.PickedFile
import app.gains.platform.ResumeRequests

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

    private var pendingPhoto: ((ByteArray?) -> Unit)? = null

    // The system photo picker: it hands back the one image the user chose and needs no storage permission.
    private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        val callback = pendingPhoto
        pendingPhoto = null
        callback?.invoke(uri?.let { bytes(it) })
    }

    private val photoPicker = PhotoPicker { onResult ->
        pendingPhoto = onResult
        pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    // The workout in progress lives in the tray while the lifter is elsewhere; see AndroidLiveSessionNotifier.
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notifier.onPermissionResult(granted)
    }
    private val notifier = AndroidLiveSessionNotifier(this) { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent, launched = savedInstanceState == null)
        setContent {
            // The system back button / predictive back gesture pops the navigator while it has
            // somewhere to go; at the root the callback is disabled so the system leaves the app.
            App(
                filePicker = filePicker,
                systemBack = { enabled, onBack -> BackHandler(enabled, onBack) },
                notifier = notifier,
                photoPicker = photoPicker,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /**
     * ACTION_VIEW / ACTION_SEND / ACTION_SEND_MULTIPLE from a file manager or the share sheet, or a
     * tap on the workout notification. [launched] is false when the activity is being recreated
     * (a rotation) with an intent it already acted on, which must not open the workout again.
     */
    @Suppress("DEPRECATION")
    private fun handleIntent(intent: Intent?, launched: Boolean = true) {
        if (intent?.action == ACTION_RESUME_SESSION) {
            if (launched) ResumeRequests.request()
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

    private fun bytes(uri: Uri): ByteArray? = runCatching {
        contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }.getOrNull()

    private fun read(uri: Uri): PickedFile? = runCatching {
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "export.csv"
        val content = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return null
        PickedFile(name, content)
    }.getOrNull()

    companion object {
        /** The workout notification's tap: bring the running workout back up. */
        const val ACTION_RESUME_SESSION = "app.gains.action.RESUME_SESSION"
    }
}
