package app.gains.platform

/**
 * Puts the app's language in force as the platform's current locale, which is where the string
 * resources take theirs from: [tag] is an IETF language tag ("ru"), null gives the device's own
 * language back. Cheap and idempotent — it is called from the composition, before anything below
 * reads a string, so that a change of language needs no relaunch.
 */
internal expect fun applyAppLanguage(tag: String?)
