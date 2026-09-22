package app.gains

import androidx.compose.ui.window.ComposeUIViewController
import app.gains.data.DatabaseDriverFactory
import app.gains.data.IosDriverFactory
import app.gains.di.initKoin
import app.gains.platform.CsvFilePicker
import app.gains.platform.IncomingFiles
import app.gains.platform.PhotoPicker
import app.gains.platform.PickedFile
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.dsl.module
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSItemProvider
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
        initKoin(module { single<DatabaseDriverFactory> { IosDriverFactory() } })
        koinStarted = true
    }
    return ComposeUIViewController {
        App(filePicker = IosFilePicker(), notifier = IosLiveSessionNotifier, photoPicker = IosPhotoPicker(), nudges = IosNudgeScheduler)
    }
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
private fun NSData.toByteArray(): ByteArray {
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
