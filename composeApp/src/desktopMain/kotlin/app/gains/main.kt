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
import app.gains.platform.IncomingLinks
import app.gains.platform.OAuthLauncher
import app.gains.platform.PickedFile
import com.sun.net.httpserver.HttpServer
import org.koin.dsl.module
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI

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
            App(filePicker = DesktopFilePicker(), oauth = DesktopOAuthLauncher())
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

/**
 * Desktop OAuth: a one-shot HTTP server on a random loopback port receives Strava's redirect
 * (Strava whitelists 127.0.0.1 and localhost as callback hosts) while the system browser shows
 * the consent page. The full callback URL goes to [IncomingLinks]; the server then shuts down.
 */
class DesktopOAuthLauncher : OAuthLauncher {
    private var server: HttpServer? = null
    override val mobile: Boolean get() = false

    override fun redirectUri(): String {
        cancel()
        val s = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        s.createContext("/strava") { exchange ->
            val callback = "http://127.0.0.1:${s.address.port}${exchange.requestURI}"
            val body = (
                "<!doctype html><html><head><meta charset=\"utf-8\"><title>Gains</title></head>" +
                    "<body style=\"font-family:-apple-system,Segoe UI,sans-serif;text-align:center;padding:64px;background:#0B0D12;color:#F4F5F7\">" +
                    "<h2>Back to Gains</h2><p>Strava has replied. You can close this tab and return to the app.</p></body></html>"
                ).toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            IncomingLinks.offer(callback)
            // Stop from another thread: stopping inside the handler would wait for the handler itself.
            Thread { runCatching { Thread.sleep(300) }; cancel() }.start()
        }
        s.start()
        server = s
        return "http://127.0.0.1:${s.address.port}/strava"
    }

    override fun open(url: String) {
        // Without a desktop environment the screen shows the link to copy instead.
        runCatching { if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI(url)) }
    }

    override fun cancel() {
        server?.stop(0)
        server = null
    }
}
