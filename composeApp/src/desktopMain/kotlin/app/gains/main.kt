package app.gains

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.isTraySupported
import androidx.compose.ui.window.rememberWindowState
import app.gains.data.DatabaseDriverFactory
import app.gains.data.DesktopDriverFactory
import app.gains.di.initKoin
import app.gains.platform.CsvFilePicker
import app.gains.platform.IncomingFiles
import app.gains.platform.LiveSessionNotice
import app.gains.platform.LiveSessionNotifier
import app.gains.platform.PickedFile
import app.gains.platform.ResumeRequests
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koin.dsl.module
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Window as AwtWindow
import java.io.File

fun main(args: Array<String>) {
    initKoin(module { single<DatabaseDriverFactory> { DesktopDriverFactory() } })
    // `gains a.csv b.csv` opens straight into the import preview with those files.
    IncomingFiles.offer(args.map(::File).filter { it.isFile }.map { PickedFile(it.name, it.readText()) })
    application {
        val windowState = rememberWindowState(width = 480.dp, height = 860.dp)
        val notifier = remember { DesktopLiveSessionNotifier() }
        val running by notifier.notice.collectAsState()
        var frame by remember { mutableStateOf<AwtWindow?>(null) }
        // While a workout runs, a volt dot sits in the system tray: a click brings the window back to it.
        running?.let { notice ->
            if (isTraySupported) {
                val resume = {
                    windowState.isMinimized = false
                    frame?.toFront()
                    ResumeRequests.request()
                }
                Tray(
                    icon = VoltDot,
                    tooltip = "${notice.title} in progress",
                    onAction = resume,
                    menu = { Item("Resume ${notice.title}", onClick = resume) },
                )
            }
        }
        Window(
            onCloseRequest = ::exitApplication,
            title = "Gains",
            state = windowState,
        ) {
            SideEffect { frame = window }
            App(filePicker = DesktopFilePicker(), notifier = notifier)
        }
    }
}

/** The tray reads the notice as state, so [main] can put an icon up and take it down with it. */
class DesktopLiveSessionNotifier : LiveSessionNotifier {
    private val _notice = MutableStateFlow<LiveSessionNotice?>(null)
    val notice: StateFlow<LiveSessionNotice?> = _notice
    override fun update(notice: LiveSessionNotice?) { _notice.value = notice }
}

/** The tray icon: the app's volt accent as a dot, legible on a light or dark menu bar. */
private object VoltDot : Painter() {
    override val intrinsicSize: Size get() = Size(16f, 16f)
    override fun DrawScope.onDraw() {
        drawCircle(Color(0xFFC8FF4D))
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
