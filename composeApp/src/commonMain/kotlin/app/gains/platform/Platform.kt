package app.gains.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A CSV the user chose or shared into the app. */
data class PickedFile(val name: String, val content: String)

/** Platform file picking (Files / SAF / AWT dialog). Several files may be chosen at once; the callback runs on the main thread. */
fun interface CsvFilePicker {
    fun pick(onResult: (List<PickedFile>) -> Unit)
}

/** Files handed to the app from outside (share sheet, "Open with", ACTION_VIEW / ACTION_SEND_MULTIPLE). */
object IncomingFiles {
    private val _pending = MutableStateFlow<List<PickedFile>>(emptyList())
    val pending: StateFlow<List<PickedFile>> = _pending

    fun offer(files: List<PickedFile>) { if (files.isNotEmpty()) _pending.value = _pending.value + files }
    fun offer(file: PickedFile) = offer(listOf(file))
    fun consume(): List<PickedFile> = _pending.value.also { _pending.value = emptyList() }
}

/**
 * The browser half of an OAuth sign-in (Strava today). The screen asks for a [redirectUri],
 * builds the provider's URL around it and calls [open]; the platform then delivers the URL the
 * provider redirects back to through [IncomingLinks], from wherever it lands (a loopback server
 * on the desktop, the `gains://` scheme on the phones).
 */
interface OAuthLauncher {
    /** Where the provider should send the user back to. May start listening; called once per attempt. */
    fun redirectUri(): String
    /** True on the phones, where Strava's mobile endpoint hands off to its app when installed. */
    val mobile: Boolean
    fun open(url: String)
    /** Stops waiting for a callback. */
    fun cancel() {}

    companion object {
        /** For tests and hosts without a browser: nothing opens, so the screen shows the link to copy. */
        val Unavailable: OAuthLauncher = object : OAuthLauncher {
            override fun redirectUri(): String = "gains://localhost/strava"
            override val mobile: Boolean get() = false
            override fun open(url: String) {}
        }
    }
}

/** URLs handed to the app from outside: today only OAuth callbacks (`gains://localhost/strava?code=…`). */
object IncomingLinks {
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending

    fun offer(url: String) { _pending.value = url }
    fun consume(): String? = _pending.value.also { _pending.value = null }
}
