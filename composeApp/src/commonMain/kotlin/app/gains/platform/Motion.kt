package app.gains.platform

/**
 * Whether the device asks apps to keep motion down: iOS's Reduce Motion, Android's animations
 * turned off in the accessibility or developer settings. Read when the app starts.
 */
internal expect fun systemReducesMotion(): Boolean
