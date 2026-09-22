package app.gains.di

import app.gains.auth.AccountRepository
import app.gains.auth.AuthConfig
import app.gains.auth.IdentityProvider
import app.gains.auth.NoIdentityProvider
import app.gains.data.BodyweightRepository
import app.gains.data.DatabaseDriverFactory
import app.gains.data.ExerciseRepository
import app.gains.data.LiveSessionRepository
import app.gains.data.ProgramRepository
import app.gains.data.SessionRepository
import app.gains.data.SettingsRepository
import app.gains.db.GainsDatabase
import app.gains.importer.ImportService
import app.gains.analysis.TrainingData
import app.gains.sync.SyncApi
import app.gains.sync.SyncController
import app.gains.sync.SyncEngine
import app.gains.sync.SyncStore
import app.gains.sync.createHttpClient
import org.koin.dsl.module

/**
 * Shared dependencies. Platforms must additionally provide a [DatabaseDriverFactory], and may
 * override [AuthConfig] (with the client ids and the server) and [IdentityProvider] (with their
 * native sign-in); the defaults here keep the app a guest.
 */
val sharedModule = module {
    single { GainsDatabase(get<DatabaseDriverFactory>().createDriver()) }
    single { SessionRepository(get()) }
    single { LiveSessionRepository(get()) }
    single { ExerciseRepository(get()) }
    single { BodyweightRepository(get()) }
    single { SettingsRepository(get()) }
    single { ProgramRepository(get(), get()) }
    single { TrainingData(get(), get()) }
    single { ImportService(get(), get()) }
    single { AuthConfig() }
    single<IdentityProvider> { NoIdentityProvider }
    single { createHttpClient() }
    single { SyncStore(get()) }
    single {
        val store = get<SyncStore>()
        SyncApi(get(), get<AuthConfig>().serverBaseUrl ?: "", token = { store.token() })
    }
    single { AccountRepository(get(), get(), get(), get(), get()) }
    single { SyncEngine(get(), get()) }
    single { SyncController(get(), get(), get(), enabled = get<AuthConfig>().syncEnabled) }
}
