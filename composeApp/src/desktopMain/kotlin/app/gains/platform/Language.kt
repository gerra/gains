package app.gains.platform

import java.util.Locale

/** The language the JVM started in, so that "System" can be given back after an override. */
private val deviceLocale: Locale = Locale.getDefault()

internal actual fun applyAppLanguage(tag: String?) {
    val locale = tag?.let(Locale::forLanguageTag) ?: deviceLocale
    if (Locale.getDefault() != locale) Locale.setDefault(locale)
}
