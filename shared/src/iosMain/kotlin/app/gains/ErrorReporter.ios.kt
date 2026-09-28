package app.gains

import platform.Foundation.NSLog

internal actual fun logError(error: Throwable) {
    // The text goes in as an argument, never as the format: a stack trace may contain a '%'.
    NSLog("Gains: unexpected error: %@", error.stackTraceToString())
}
