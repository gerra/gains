package app.gains

import java.util.concurrent.CopyOnWriteArrayList

/**
 * The tests' [ErrorReporter]: keeps what reached it, so a test can say how many failures it
 * expected, and [assertNone] fails a test that expected none. Bound in place of the log so the
 * models' handler hides nothing in tests.
 */
class RecordingReporter : ErrorReporter {
    val reported: MutableList<Throwable> = CopyOnWriteArrayList()

    override fun report(error: Throwable) {
        reported += error
    }

    fun assertNone() {
        val first = reported.firstOrNull() ?: return
        throw AssertionError("${reported.size} unexpected exception(s) reached the reporter", first)
    }
}
