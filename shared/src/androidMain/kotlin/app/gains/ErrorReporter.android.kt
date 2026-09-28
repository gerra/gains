package app.gains

import android.util.Log

internal actual fun logError(error: Throwable) {
    Log.e("Gains", "unexpected error", error)
}
