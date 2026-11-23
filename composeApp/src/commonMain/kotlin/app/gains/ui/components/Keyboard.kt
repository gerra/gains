package app.gains.ui.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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

/**
 * Called by a bottom sheet as it opens, outside the sheet's own content so it reaches the screen
 * underneath: drops whatever that screen has focused.
 *
 * A tap that opens a sheet (a weight cell, a date) is consumed by what was tapped, so it never
 * reaches [dismissKeyboardOnTap] and the screen keeps its focus under the sheet. If that was a text
 * field, the platform hands the keyboard straight back to it when the sheet closes: iOS makes the
 * field first responder again, Android restores the keyboard to the window it returns to. So a note
 * typed before choosing a weight would bring the keyboard up when the weight sheet is tapped away.
 * The [NumberPad] goes too, as it does for any other tap off it.
 */
@Composable
internal fun ReleaseFocusBehind() {
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { focusManager.clearFocus() }
}
