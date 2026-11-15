package app.gains.ui.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/**
 * Tapping anything that is not itself interactive (a card, a label, the gap between rows) drops
 * focus, which closes the on-screen keyboard.
 *
 * Compose leaves a text field focused until something else takes focus, so without this a lifter
 * who has typed a note has no way to put the keyboard away. The workout's [NumberPad] follows focus
 * too, so the same tap puts it away. Taps that a text field, button or other clickable
 * consumes never reach this modifier, so tapping from one field into another still just moves focus.
 */
@Composable
internal fun Modifier.dismissKeyboardOnTap(): Modifier {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    return pointerInput(Unit) {
        detectTapGestures(onTap = {
            focusManager.clearFocus()
            keyboard?.hide()
        })
    }
}
