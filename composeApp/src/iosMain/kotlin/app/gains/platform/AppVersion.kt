package app.gains.platform

import platform.Foundation.NSBundle

/** MARKETING_VERSION and CURRENT_PROJECT_VERSION, which Info.plist carries as these two keys. */
internal actual fun appVersion(): String {
    val info = NSBundle.mainBundle
    val version = info.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: "?"
    val build = info.objectForInfoDictionaryKey("CFBundleVersion") as? String
    return if (build == null) version else "$version ($build)"
}
