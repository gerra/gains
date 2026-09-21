package app.gains.i18n

import platform.Foundation.NSBundle
import platform.Foundation.NSLocale
import platform.Foundation.preferredLanguages

/**
 * The best of the app's declared localizations (CFBundleLocalizations in Info.plist) for the
 * device's language list, which is also what the per-app language setting changes; falls back
 * to the device's first preferred language.
 */
actual fun systemLanguageTag(): String? =
    NSBundle.mainBundle.preferredLocalizations.firstOrNull() as? String
        ?: NSLocale.preferredLanguages.firstOrNull() as? String
