package app.gains

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.gains.platform.WatchStatus
import app.gains.platform.applyAppLanguage
import app.gains.ui.components.SectionHeader
import app.gains.ui.screens.WatchNudgeCard
import app.gains.ui.screens.WatchSettingsCard
import app.gains.ui.theme.GainsTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Getting Gains onto the Apple Watch (docs/watch.md, "Connecting a watch"): Settings' card before
 * and after, and the workout's once-only offer, on a narrow phone (360 dp), dark, light and in
 * Russian. Also checks that both buttons do what they say.
 */
@OptIn(ExperimentalTestApi::class)
class WatchConnectScreenshotTest {
    private val outDir = File(System.getProperty("gains.screenshotDir") ?: "build/screenshots").apply { mkdirs() }

    @AfterTest
    fun giveTheDeviceLanguageBack() = applyAppLanguage(null)

    @Test
    fun captureTheWatchCards() {
        for ((name, dark, language) in listOf(Triple("23-apple-watch", true, null), Triple("23b-apple-watch-light", false, null), Triple("23c-apple-watch-ru", true, "ru"))) {
            applyAppLanguage(language)
            runDesktopComposeUiTest(width = 720, height = 2440) {
                var opened = 0
                var notNow = 0
                mainClock.autoAdvance = false
                setContent {
                    CompositionLocalProvider(LocalDensity provides Density(2f)) {
                        GainsTheme(darkTheme = dark) {
                            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                                Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    SectionHeader("Workout · watch paired, Gains not on it")
                                    WatchNudgeCard(onOpenWatchApp = { opened++ }, onNotNow = { notNow++ })
                                    SectionHeader("Settings · not installed")
                                    WatchSettingsCard(WatchStatus.NOT_INSTALLED, onOpenWatchApp = { opened++ })
                                    SectionHeader("Settings · installed")
                                    WatchSettingsCard(WatchStatus.INSTALLED, onOpenWatchApp = { opened++ })
                                }
                            }
                        }
                    }
                }
                repeat(60) { mainClock.advanceTimeByFrame() }
                ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", File(outDir, "$name.png"))

                if (language == null) {
                    onAllNodes(hasText("Open the Watch app") and hasClickAction()).onFirst().performClick()
                    onAllNodes(hasText("Not now") and hasClickAction()).onFirst().performClick()
                    repeat(10) { mainClock.advanceTimeByFrame() }
                    assertEquals(1, opened)
                    assertEquals(1, notNow)
                }
            }
        }
    }
}
