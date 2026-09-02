package app.gains

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.gains.data.DatabaseDriverFactory
import app.gains.data.DesktopDriverFactory
import app.gains.di.initKoin
import app.gains.platform.CsvFilePicker
import app.gains.platform.IncomingFiles
import app.gains.platform.PickedFile
import org.koin.dsl.module
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

fun main(args: Array<String>) {
    initKoin(module { single<DatabaseDriverFactory> { DesktopDriverFactory() } })
    // `gains a.csv b.csv` opens straight into the import preview with those files.
    IncomingFiles.offer(args.map(::File).filter { it.isFile }.map { PickedFile(it.name, it.readText()) })
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Gains",
            state = rememberWindowState(width = 480.dp, height = 860.dp),
        ) {
            App(filePicker = DesktopFilePicker())
        }
    }
}

class DesktopFilePicker : CsvFilePicker {
    override fun pick(onResult: (List<PickedFile>) -> Unit) {
        val dialog = FileDialog(null as Frame?, "Choose Liftoff CSV exports", FileDialog.LOAD)
        dialog.setFilenameFilter { _, name -> name.endsWith(".csv", ignoreCase = true) }
        dialog.isMultipleMode = true
        dialog.isVisible = true
        onResult(dialog.files.filter { it.isFile }.map { PickedFile(it.name, it.readText()) })
    }
}
