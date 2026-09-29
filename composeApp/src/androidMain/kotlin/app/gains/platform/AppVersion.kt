package app.gains.platform

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import org.koin.mp.KoinPlatform

/**
 * versionName and versionCode from :androidApp. The library this code lives in has no BuildConfig
 * of the application's, so they are read back from the installed package; the context is the one
 * GainsApplication hands Koin.
 */
internal actual fun appVersion(): String {
    val context = KoinPlatform.getKoin().get<Context>()
    val info = if (Build.VERSION.SDK_INT >= 33) {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0)
    }
    val build = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    return "${info.versionName ?: "?"} ($build)"
}
