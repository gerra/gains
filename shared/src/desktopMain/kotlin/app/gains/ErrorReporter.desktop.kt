package app.gains

internal actual fun logError(error: Throwable) {
    System.err.println("Gains: unexpected error")
    error.printStackTrace()
}
