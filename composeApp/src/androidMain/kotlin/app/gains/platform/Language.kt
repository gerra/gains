package app.gains.platform

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import org.koin.mp.KoinPlatform
import java.util.Locale

/** The language the process started in, so that "System" can be given back after an override. */
private val deviceLocale: Locale = Locale.getDefault()

internal actual fun applyAppLanguage(tag: String?) {
    val locale = tag?.let(Locale::forLanguageTag) ?: deviceLocale
    if (Locale.getDefault() != locale) Locale.setDefault(locale)
    // Android 13 and up keeps a per-app language of its own, which the app's Android resources
    // follow — the workout notification — and which its own settings show. Setting it recreates
    // the activity, so it is written only when it really changes.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val tags = tag.orEmpty()
        val manager = KoinPlatform.getKoin().getOrNull<Context>()?.getSystemService(LocaleManager::class.java)
        if (manager != null && manager.applicationLocales.toLanguageTags() != tags) {
            manager.applicationLocales = LocaleList.forLanguageTags(tags)
        }
    }
}
