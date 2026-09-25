package app.gains.platform

import android.content.Context
import android.provider.Settings
import org.koin.mp.KoinPlatform

/** "Remove animations" sets the animator scale to nothing; Compose already cuts its own animations then, the loops included. */
internal actual fun systemReducesMotion(): Boolean {
    val context = KoinPlatform.getKoin().getOrNull<Context>() ?: return false
    return Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}
