package app.gains.platform

/**
 * MARKETING_VERSION from Config.xcconfig, which composeApp/build.gradle.kts passes to `run` and the
 * packaged app as a system property, as it does the sign-in settings. A test run has none.
 */
internal actual fun appVersion(): String = System.getProperty("gains.version") ?: "dev"
