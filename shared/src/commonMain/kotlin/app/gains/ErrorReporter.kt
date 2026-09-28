package app.gains

/**
 * Where an exception nobody expected goes: a screen's action that failed for a reason it has no
 * words for, or a background write that should never fail. One place, so a crash-reporting service
 * later is one binding in Koin rather than a search through the models; until then [LogErrorReporter]
 * writes it to the platform's log. Expected failures (a closed sign-in sheet, a CSV in an unknown
 * format) are handled where they happen and never come here.
 */
fun interface ErrorReporter {
    fun report(error: Throwable)
}

/** The default [ErrorReporter]: the platform's own log, so the report is where a developer already looks. */
object LogErrorReporter : ErrorReporter {
    override fun report(error: Throwable) = logError(error)
}

/** Writes [error] and its stack trace to the platform's log: stderr on the desktop, `NSLog` on iOS, `Log.e` on Android. */
internal expect fun logError(error: Throwable)
