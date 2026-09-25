package app.gains

import android.app.Application
import app.gains.data.AndroidDriverFactory
import app.gains.data.DatabaseDriverFactory
import app.gains.di.initKoin
import app.gains.sync.KeystoreTokenVault
import app.gains.sync.TokenVault
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

class GainsApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initKoin(
            module {
                single<DatabaseDriverFactory> { AndroidDriverFactory(this@GainsApplication) }
                // Loaded after the shared module, so this replaces its token-in-the-database default.
                single<TokenVault> { KeystoreTokenVault(this@GainsApplication) }
            },
        ) {
            androidContext(this@GainsApplication)
        }
    }
}
