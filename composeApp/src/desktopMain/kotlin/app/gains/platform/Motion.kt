package app.gains.platform

/** Desktops have no system-wide setting the JVM can read; motion stays on. */
internal actual fun systemReducesMotion(): Boolean = false
