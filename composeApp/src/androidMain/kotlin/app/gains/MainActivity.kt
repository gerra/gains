package app.gains

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import app.gains.platform.CsvFilePicker
import app.gains.platform.IncomingFiles
import app.gains.platform.PhotoPicker
import app.gains.platform.PickedFile
import app.gains.platform.ResumeRequests
import app.gains.sync.SyncController
import org.koin.mp.KoinPlatform

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
    // The three refer to each other, so their types are spelled out: inferred, they are a cycle the
    // compiler refuses ("Type checking has run into a recursive problem").
    private val notificationPermission: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            notifier.onPermissionResult(granted)
        }
    private val askToNotify: () -> Unit = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
    private val notifier: AndroidLiveSessionNotifier = AndroidLiveSessionNotifier(this, askToNotify)
    // The streak reminders, held by the system as alarms while the app is not running.
    private val nudges = AndroidNudgeScheduler(this, askToNotify)

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
                nudges = nudges,
                systemBars = { dark -> LaunchedEffect(dark) { systemBarIcons(dark) } },
            )
        }
    }

    /**
     * Android 15 and later draw the app edge to edge under transparent system bars, and Android 16
     * takes away the opt-out (docs/launch-plan.md, item 21). The bars' icons then sit on the app's
     * own background, so they follow the theme chosen in Settings: dark icons on the light theme,
     * light ones on the dark. The manifest's platform theme never asks for dark icons, which would
     * leave the clock and the battery white on white in the light theme. Earlier versions keep the
     * theme's opaque grey status bar, where its light icons are right, so they are left alone.
     */
    private fun systemBarIcons(dark: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (dark) 0 else light, light)
    }

    /**
     * A sync each time the app comes back to the front, as docs/sync.md promises and iOS does on
     * foreground, so a workout logged elsewhere shows up without "Sync now". The one activity's
     * resume is the app's, and the controller does nothing for a guest or without a server.
     */
    override fun onResume() {
        super.onResume()
        KoinPlatform.getKoin().get<SyncController>().requestSync()
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
