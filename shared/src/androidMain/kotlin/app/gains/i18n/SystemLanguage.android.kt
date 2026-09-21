package app.gains.i18n

import java.util.Locale

/** The app's locale: the per-app language from Android 13's settings when one is set, else the device's. */
actual fun systemLanguageTag(): String? = Locale.getDefault().toLanguageTag()
