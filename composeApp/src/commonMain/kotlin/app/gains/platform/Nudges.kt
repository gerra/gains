package app.gains.platform

/**
 * A reminder for the platform to deliver at [atEpochMs], whether or not the app is running then.
 * The words are finished here rather than worked out later: by the time it is shown the app may be
 * asleep, killed, or on a device that has been rebooted since.
 */
internal data class Nudge(val id: String, val atEpochMs: Long, val title: String, val body: String)

/**
 * Local notifications the app asks the platform to hold for it. [schedule] replaces everything
 * outstanding with [nudges] — an empty list cancels the lot — so the app never has to reason about
 * what it asked for last time. The whole plan is re-sent whenever the streak changes, which is to
 * say whenever a workout is saved, so a reminder never survives the session that made it pointless.
 */
internal fun interface NudgeScheduler {
    fun schedule(nudges: List<Nudge>)

    companion object {
        /** For platforms (and tests) with nowhere to put a reminder. */
        val None = NudgeScheduler {}
    }
}
