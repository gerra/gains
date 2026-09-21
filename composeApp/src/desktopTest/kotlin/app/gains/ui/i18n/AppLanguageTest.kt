package app.gains.ui.i18n

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import app.gains.data.AppLanguage
import app.gains.platform.applyAppLanguage
import app.gains.resources.Res
import app.gains.resources.settings_title
import org.jetbrains.compose.resources.stringResource
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The language setting: the words on screen follow it as soon as it is changed, with no relaunch,
 * and "System" hands the device's own language back. The test JVM runs in English.
 */
@OptIn(ExperimentalTestApi::class)
class AppLanguageTest {
    @AfterTest
    fun giveTheDeviceLanguageBack() = applyAppLanguage(null)

    @Test
    fun everyWordFollowsTheChosenLanguage() = runDesktopComposeUiTest {
        var language by mutableStateOf(AppLanguage.ENGLISH)
        var shown = ""
        setContent {
            InLanguage(language) { shown = stringResource(Res.string.settings_title) }
        }
        waitForIdle()
        assertEquals("Settings", shown)

        language = AppLanguage.RUSSIAN
        waitForIdle()
        assertEquals("Настройки", shown)

        language = AppLanguage.SYSTEM
        waitForIdle()
        assertEquals("Settings", shown)
    }
}
