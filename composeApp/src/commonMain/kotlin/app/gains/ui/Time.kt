package app.gains.ui

import kotlin.time.Clock

/** Wall-clock epoch milliseconds: what the session clock and the rest timer count against. */
internal fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()
