package app.gains.platform

import platform.UIKit.UIAccessibilityIsReduceMotionEnabled

internal actual fun systemReducesMotion(): Boolean = UIAccessibilityIsReduceMotionEnabled()
