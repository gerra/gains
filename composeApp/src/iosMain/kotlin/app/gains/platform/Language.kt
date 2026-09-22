package app.gains.platform

import platform.Foundation.NSUserDefaults

/**
 * Where iOS keeps the languages the user prefers, most wanted first. An app writes its own choice
 * over them, and the resources — like everything else that asks for the locale — read it back from
 * here; taking it away leaves the device's own list showing through again.
 */
private const val APPLE_LANGUAGES = "AppleLanguages"

internal actual fun applyAppLanguage(tag: String?) {
    val defaults = NSUserDefaults.standardUserDefaults
    val current = defaults.arrayForKey(APPLE_LANGUAGES)?.firstOrNull() as? String
    when {
        tag == null -> defaults.removeObjectForKey(APPLE_LANGUAGES)
        current != tag -> defaults.setObject(listOf(tag), APPLE_LANGUAGES)
    }
}
