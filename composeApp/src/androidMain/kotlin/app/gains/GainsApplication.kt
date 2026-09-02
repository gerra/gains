package app.gains

import android.app.Application
import app.gains.data.AndroidDriverFactory
import app.gains.data.DatabaseDriverFactory
import app.gains.di.initKoin
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

class GainsApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initKoin(module { single<DatabaseDriverFactory> { AndroidDriverFactory(this@GainsApplication) } }) {
            androidContext(this@GainsApplication)
        }
    }
}
