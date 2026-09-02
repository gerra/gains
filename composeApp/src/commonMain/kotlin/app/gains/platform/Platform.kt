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
