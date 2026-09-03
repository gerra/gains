package app.gains

import androidx.compose.ui.window.ComposeUIViewController
import app.gains.data.DatabaseDriverFactory
import app.gains.data.IosDriverFactory
import app.gains.di.initKoin
import app.gains.platform.CsvFilePicker
import app.gains.platform.IncomingFiles
import app.gains.platform.PickedFile
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.dsl.module
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfURL
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UniformTypeIdentifiers.UTType
import platform.UniformTypeIdentifiers.UTTypeCommaSeparatedText
import platform.UniformTypeIdentifiers.UTTypePlainText
import platform.darwin.NSObject

private var koinStarted = false

/** Entry point used by the SwiftUI wrapper in iosApp. */
fun MainViewController(): UIViewController {
    if (!koinStarted) {
        initKoin(module { single<DatabaseDriverFactory> { IosDriverFactory() } })
        koinStarted = true
    }
    return ComposeUIViewController { App(filePicker = IosFilePicker()) }
}

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

@OptIn(ExperimentalForeignApi::class)
class IosFilePicker : CsvFilePicker {
    // Keep a strong reference: UIKit only holds the delegate weakly.
    private var delegate: PickerDelegate? = null

    override fun pick(onResult: (List<PickedFile>) -> Unit) {
        val types = listOfNotNull<UTType>(UTTypeCommaSeparatedText, UTTypePlainText)
        val picker = UIDocumentPickerViewController(forOpeningContentTypes = types, asCopy = true)
        delegate = PickerDelegate { file -> onResult(file); delegate = null }
        picker.delegate = delegate
        picker.allowsMultipleSelection = true
        rootViewController()?.presentViewController(picker, animated = true, completion = null)
    }

    private fun rootViewController(): UIViewController? {
        var vc = UIApplication.sharedApplication.keyWindow?.rootViewController
        while (vc?.presentedViewController != null) vc = vc.presentedViewController
        return vc
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
