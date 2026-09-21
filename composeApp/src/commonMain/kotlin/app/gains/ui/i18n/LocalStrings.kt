package app.gains.ui.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import app.gains.i18n.English
import app.gains.i18n.Strings

/**
 * The language of everything on screen. [App] provides the device's; a screen composed on its own
 * (a test, a preview) gets English. Read it as `strings` at the top of a composable, and hand it to
 * a screen model, which lives outside the composition, when the model wording anything.
 */
internal val LocalStrings = staticCompositionLocalOf<Strings> { English }

/** The current language, for composables: `val strings = strings`. */
internal val strings: Strings
    @Composable @ReadOnlyComposable get() = LocalStrings.current
