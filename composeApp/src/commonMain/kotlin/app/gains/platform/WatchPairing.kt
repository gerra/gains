package app.gains.platform

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Where the lifter's Apple Watch stands with Gains (docs/watch.md, "Connecting a watch"). */
internal enum class WatchStatus {
    /** No watch to speak of: another platform, no watch paired, or not known yet. Nothing is shown. */
    NONE,
    /** A watch is paired with this iPhone, but Gains is not on it yet. */
    NOT_INSTALLED,
    /** Gains is on the paired watch: a workout started here opens on it. */
    INSTALLED,
}

/** The platform's word on the watch, and the one thing it can do towards installing on it. */
internal interface WatchPairing {
    val status: StateFlow<WatchStatus>

    /** Opens the iPhone's Watch app, where Gains is installed on the watch. False where there is none. */
    fun openWatchApp(): Boolean

    companion object {
        /** For platforms (and tests) with no watch. */
        val None: WatchPairing = Fixed(WatchStatus.NONE)
    }

    /** A status that never changes: for platforms without a watch, and for screenshots of each state. */
    class Fixed(status: WatchStatus, private val opens: Boolean = false) : WatchPairing {
        override val status: StateFlow<WatchStatus> = MutableStateFlow(status)
        var opened = 0
            private set

        override fun openWatchApp(): Boolean {
            opened++
            return opens
        }
    }
}

/** The platform's watch for the screens that talk about it: Settings and a workout's nudge. */
internal val LocalWatchPairing = staticCompositionLocalOf { WatchPairing.None }
