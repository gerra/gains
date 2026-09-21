package app.gains.ui.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.jetbrains.compose.resources.ResourceEnvironment
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.rememberResourceEnvironment

/**
 * The string resources for code that lives outside the composition: the screen models and the
 * desktop file dialog. The plain `getString` asks the platform for the resource environment
 * (language, theme, density), which on the desktop goes through the AWT toolkit and fails
 * without a display, as in a headless UI test. The composition knows the environment already,
 * so a screen reads it there once, through [rememberTexts], and hands it to its model.
 */
internal class Texts(private val environment: ResourceEnvironment) {
    suspend fun get(resource: StringResource, vararg args: Any): String = getString(environment, resource, *args)
}

/** The strings in the language of the composition, for a model made here. */
@Composable
internal fun rememberTexts(): Texts {
    val environment = rememberResourceEnvironment()
    return remember(environment) { Texts(environment) }
}
