package app.gains.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A CSV the user chose or shared into the app. */
data class PickedFile(val name: String, val content: String)

/** Platform file picking: Files/SAF/AWT dialog. Callback is invoked on the main thread. */
fun interface CsvFilePicker {
    fun pick(onResult: (PickedFile?) -> Unit)
}

/** Files handed to the app from outside (share sheet, "Open with", ACTION_VIEW). */
object IncomingFiles {
    private val _pending = MutableStateFlow<PickedFile?>(null)
    val pending: StateFlow<PickedFile?> = _pending

    fun offer(file: PickedFile) { _pending.value = file }
    fun consume(): PickedFile? = _pending.value.also { _pending.value = null }
}
