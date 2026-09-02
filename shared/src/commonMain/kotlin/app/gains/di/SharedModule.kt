package app.gains.di

import app.gains.auth.AccountRepository
import app.gains.auth.AuthConfig
import app.gains.data.BodyweightRepository
import app.gains.data.DatabaseDriverFactory
import app.gains.data.ExerciseRepository
import app.gains.data.SessionRepository
import app.gains.data.SettingsRepository
import app.gains.db.GainsDatabase
import app.gains.importer.ImportService
import app.gains.analysis.TrainingData
import org.koin.dsl.module

/** Shared dependencies. Platforms must additionally provide a [DatabaseDriverFactory]. */
val sharedModule = module {
    single { GainsDatabase(get<DatabaseDriverFactory>().createDriver()) }
    single { SessionRepository(get()) }
    single { ExerciseRepository(get()) }
    single { BodyweightRepository(get()) }
    single { SettingsRepository(get()) }
    single { TrainingData(get(), get()) }
    single { ImportService(get(), get()) }
    // Replace with real ids when the Google / Apple credentials and the sync server exist.
    single { AuthConfig() }
    single { AccountRepository(get(), get()) }
}
