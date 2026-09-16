package app.gains

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import app.gains.ui.components.dismissKeyboardOnTap
import kotlin.test.Test
import kotlin.test.assertEquals

/** Tapping empty space drops text-field focus (which closes the keyboard); real controls keep working. */
@OptIn(ExperimentalTestApi::class)
class DismissKeyboardOnTapTest {
    private val width = 400
    private val height = 800

    @Test
    fun tappingEmptySpaceClearsFocus() = runDesktopComposeUiTest(width, height) {
        setContent {
            Box(Modifier.fillMaxSize().dismissKeyboardOnTap()) {
                OutlinedTextField("", {}, Modifier.fillMaxWidth().testTag("weight"))
            }
        }
        onNodeWithTag("weight").performClick()
        onNodeWithTag("weight").assertIsFocused()
        onRoot().performTouchInput { click(Offset(width / 2f, height * 0.75f)) }
        waitForIdle()
        onNodeWithTag("weight").assertIsNotFocused()
    }

    @Test
    fun tappingAnotherFieldMovesFocusThere() = runDesktopComposeUiTest(width, height) {
        setContent {
            Column(Modifier.fillMaxSize().dismissKeyboardOnTap()) {
                OutlinedTextField("", {}, Modifier.fillMaxWidth().testTag("weight"))
                OutlinedTextField("", {}, Modifier.fillMaxWidth().testTag("reps"))
            }
        }
        onNodeWithTag("weight").performClick()
        onNodeWithTag("weight").assertIsFocused()
        onNodeWithTag("reps").performClick()
        onNodeWithTag("reps").assertIsFocused()
        onNodeWithTag("weight").assertIsNotFocused()
    }

    @Test
    fun buttonsStillFire() = runDesktopComposeUiTest(width, height) {
        var clicks = 0
        setContent {
            Column(Modifier.fillMaxSize().dismissKeyboardOnTap()) {
                OutlinedTextField("", {}, Modifier.fillMaxWidth().testTag("weight"))
                TextButton(onClick = { clicks++ }) { Text("Add set") }
            }
        }
        onNodeWithTag("weight").performClick()
        onNodeWithTag("weight").assertIsFocused()
        onNodeWithText("Add set").performClick()
        waitForIdle()
        assertEquals(1, clicks)
    }
}
