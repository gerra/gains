package app.gains

import android.app.Activity
import android.app.Application
import android.os.Bundle
import app.gains.auth.IdentityProvider
import app.gains.data.AndroidDriverFactory
import app.gains.data.DatabaseDriverFactory
import app.gains.di.initKoin
import app.gains.sync.KeystoreTokenVault
import app.gains.sync.TokenVault
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

class GainsApplication : Application() {
    /**
     * The activity in front, for the sheets that must be shown from one: Credential Manager's
     * account chooser and the Custom Tab ([AndroidIdentityProvider]). Null while none is resumed,
     * as when the app is in the background.
     */
    private var foreground: Activity? = null

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(Foreground())
        initKoin(
            module {
                single<DatabaseDriverFactory> { AndroidDriverFactory(this@GainsApplication) }
                // Loaded after the shared module, so these replace its guest-only defaults.
                single { androidAuthConfig() }
                single<IdentityProvider> { AndroidIdentityProvider(get(), this@GainsApplication) { foreground } }
                single<TokenVault> { KeystoreTokenVault(this@GainsApplication) }
            },
        ) {
            androidContext(this@GainsApplication)
        }
    }

    /**
     * Keeps [foreground] pointed at the resumed activity, and tells [WebSignIn] when the app's own
     * screen is back in front, which ends a sign-in whose browser tab was backed out of. The other
     * callbacks are not needed.
     */
    private inner class Foreground : ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            foreground = activity
            if (activity is MainActivity) WebSignIn.onAppResumed()
        }
        override fun onActivityPaused(activity: Activity) { if (foreground === activity) foreground = null }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }
}
