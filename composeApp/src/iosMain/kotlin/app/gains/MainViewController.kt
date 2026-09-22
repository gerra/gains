package app.gains

import androidx.compose.ui.window.ComposeUIViewController
import app.gains.auth.AuthConfig
import app.gains.auth.IdentityProvider
import app.gains.data.DatabaseDriverFactory
import app.gains.data.IosDriverFactory
import app.gains.di.initKoin
import app.gains.platform.CsvFilePicker
import app.gains.platform.IncomingFiles
import app.gains.platform.PhotoPicker
import app.gains.platform.PickedFile
import app.gains.sync.SyncController
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.dsl.module
import org.koin.mp.KoinPlatform
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSItemProvider
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfURL
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UniformTypeIdentifiers.UTType
import platform.UniformTypeIdentifiers.UTTypeCommaSeparatedText
import platform.UniformTypeIdentifiers.UTTypePlainText
import platform.darwin.NSObject
import platform.posix.memcpy

private var koinStarted = false

/** Entry point used by the SwiftUI wrapper in iosApp. */
fun MainViewController(): UIViewController {
    if (!koinStarted) {
        initKoin(
            module {
                single<DatabaseDriverFactory> { IosDriverFactory() }
                // Loaded after the shared module, so these replace its guest-only defaults.
                single { iosAuthConfig() }
                single<IdentityProvider> { IosIdentityProvider(get(), get()) }
            },
        )
        koinStarted = true
        requestSyncOnForeground()
    }
    return ComposeUIViewController {
        App(filePicker = IosFilePicker(), notifier = IosLiveSessionNotifier, photoPicker = IosPhotoPicker(), nudges = IosNudgeScheduler)
    }
}

/**
 * A sync each time the app comes back to the front, as docs/sync.md promises. Observed here with
 * UIKit's notification, since Compose's lifecycle isn't among the app's dependencies; the
 * observer lives as long as the process, like the Koin graph it reads from. The controller does
 * nothing for a guest or without a server.
 */
private fun requestSyncOnForeground() {
    val sync = KoinPlatform.getKoin().get<SyncController>()
    NSNotificationCenter.defaultCenter.addObserverForName(UIApplicationWillEnterForegroundNotification, null, NSOperationQueue.mainQueue) { _ ->
        sync.requestSync()
    }
}

/**
 * The server and the Google client come from Info.plist (`GainsServerURL`, `GainsGoogleClientID`,
 * set from Config.xcconfig) so they are changed without touching code; the Apple audience is the
 * bundle id, which the native flow signs for.
 */
private fun iosAuthConfig(): AuthConfig {
    val bundle = NSBundle.mainBundle
    return AuthConfig(
        serverBaseUrl = (bundle.objectForInfoDictionaryKey("GainsServerURL") as? String)?.trim()?.trimEnd('/')?.ifBlank { null },
        googleClientId = (bundle.objectForInfoDictionaryKey("GainsGoogleClientID") as? String)?.trim()?.ifBlank { null },
        appleServiceId = bundle.bundleIdentifier,
    )
}

/**
 * Called from the Swift app delegate as the app finishes launching, so a tap on the workout
 * notification that cold starts the app reaches [IosLiveSessionNotifier] and opens the workout.
 */
fun prepareLiveSessionNotices() = IosLiveSessionNotifier.install()

/** Reads files on the IO dispatcher so the main thread never blocks on disk or the file provider. */
private val fileReads = CoroutineScope(Dispatchers.IO)

/** Called from Swift when the app is opened with a CSV (share sheet "Open in Gains", Files, AirDrop). */
fun handleIncomingFile(url: NSURL) {
    fileReads.launch {
        readCsv(url)?.let { IncomingFiles.offer(it) }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun readCsv(url: NSURL): PickedFile? {
    val accessing = url.startAccessingSecurityScopedResource()
    try {
        val text = NSString.stringWithContentsOfURL(url, NSUTF8StringEncoding, null) ?: return null
        return PickedFile(url.lastPathComponent ?: "export.csv", text)
    } finally {
        if (accessing) url.stopAccessingSecurityScopedResource()
    }
}

/** Whatever is on top right now, so a sheet is presented from the visible screen. */
private fun topViewController(): UIViewController? {
    var vc = UIApplication.sharedApplication.keyWindow?.rootViewController
    while (vc?.presentedViewController != null) vc = vc.presentedViewController
    return vc
}

@OptIn(ExperimentalForeignApi::class)
internal fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size <= 0) return ByteArray(0)
    return ByteArray(size).apply {
        usePinned { memcpy(it.addressOf(0), this@toByteArray.bytes, this@toByteArray.length) }
    }
}

/**
 * What the picked photo is asked for as. The UTI rather than UTTypeImage, whose binding is nullable
 * while this never is; file scope rather than a companion, which Kotlin/Native does not allow to
 * hold fields on a subclass of an Objective-C type.
 */
private const val IMAGE_UTI = "public.image"

/**
 * The workout photo, from the system photo picker. It runs out of process and hands back only what
 * was chosen, so the app needs no access to the library and no permission prompt.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosPhotoPicker : PhotoPicker {
    // Keep a strong reference: UIKit only holds the delegate weakly.
    private var delegate: PhotoDelegate? = null

    override fun pick(onResult: (ByteArray?) -> Unit) {
        val configuration = PHPickerConfiguration().apply {
            selectionLimit = 1L
            filter = PHPickerFilter.imagesFilter
        }
        val picker = PHPickerViewController(configuration)
        delegate = PhotoDelegate { bytes -> delegate = null; onResult(bytes) }
        picker.delegate = delegate
        topViewController()?.presentViewController(picker, animated = true, completion = null)
    }

    private class PhotoDelegate(private val onResult: (ByteArray?) -> Unit) : NSObject(), PHPickerViewControllerDelegateProtocol {
        override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
            picker.dismissViewControllerAnimated(true, null)
            val provider = (didFinishPicking.firstOrNull() as? PHPickerResult)?.itemProvider
            if (provider == null) { onResult(null); return }
            load(provider) { bytes ->
                fileReads.launch { withContext(Dispatchers.Main) { onResult(bytes) } }
            }
        }

        /** The chosen image's bytes, whatever the library holds it as; null when it cannot be read. */
        private fun load(provider: NSItemProvider, onLoaded: (ByteArray?) -> Unit) {
            provider.loadDataRepresentationForTypeIdentifier(IMAGE_UTI) { data: NSData?, _: NSError? ->
                onLoaded(data?.toByteArray())
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
internal class IosFilePicker : CsvFilePicker {
    // Keep a strong reference: UIKit only holds the delegate weakly.
    private var delegate: PickerDelegate? = null

    override fun pick(onResult: (List<PickedFile>) -> Unit) {
        val types = listOfNotNull<UTType>(UTTypeCommaSeparatedText, UTTypePlainText)
        val picker = UIDocumentPickerViewController(forOpeningContentTypes = types, asCopy = true)
        delegate = PickerDelegate { file -> onResult(file); delegate = null }
        picker.delegate = delegate
        picker.allowsMultipleSelection = true
        topViewController()?.presentViewController(picker, animated = true, completion = null)
    }

    private class PickerDelegate(private val onResult: (List<PickedFile>) -> Unit) : NSObject(), UIDocumentPickerDelegateProtocol {
        override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
            val urls = didPickDocumentsAtURLs.mapNotNull { it as? NSURL }
            fileReads.launch {
                val files = urls.mapNotNull(::readCsv)
                withContext(Dispatchers.Main) { onResult(files) }
            }
        }

        override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) = onResult(emptyList())
    }
}
