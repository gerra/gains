package app.gains.ui.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.staticCompositionLocalOf
import app.gains.data.AppLanguage
import app.gains.platform.applyAppLanguage

/** The language the composition is worded in. Screen models are made anew when it changes. */
internal val LocalAppLanguage = staticCompositionLocalOf { AppLanguage.SYSTEM }

/**
 * Shows [content] in [language]: the device's own where none has been chosen, otherwise the chosen
 * one whatever the device says.
 *
 * A string resource picks its language from the platform's current locale, which it reads while
 * composing, so the choice is put in force here — during the composition, before a word of
 * [content] has been read — rather than from an effect, which would land a frame late. [key] then
 * throws the content away and composes it again, so every string is looked up afresh.
 */
@Composable
internal fun InLanguage(language: AppLanguage, content: @Composable () -> Unit) {
    applyAppLanguage(language.tag)
    CompositionLocalProvider(LocalAppLanguage provides language) {
        key(language) { content() }
    }
}
