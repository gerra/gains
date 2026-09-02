package app.gains.di

import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.module.Module

/** Starts Koin with the shared module plus whatever the platform supplies (at least a DatabaseDriverFactory). */
fun initKoin(vararg platformModules: Module, configure: KoinApplication.() -> Unit = {}): KoinApplication =
    startKoin {
        configure()
        modules(sharedModule, *platformModules)
    }
