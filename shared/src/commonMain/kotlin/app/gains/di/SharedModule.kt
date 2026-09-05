package app.gains.di

import app.gains.auth.AccountRepository
import app.gains.auth.AuthConfig
import app.gains.data.BodyweightRepository
import app.gains.data.DatabaseDriverFactory
import app.gains.data.ExerciseRepository
import app.gains.data.ProgramRepository
import app.gains.data.SessionRepository
import app.gains.data.SettingsRepository
import app.gains.db.GainsDatabase
import app.gains.importer.ImportService
import app.gains.analysis.TrainingData
import app.gains.strava.StravaApi
import app.gains.strava.StravaConfig
import app.gains.strava.StravaRepository
import app.gains.strava.StravaService
import app.gains.strava.platformHttpEngine
import org.koin.dsl.module

/** Shared dependencies. Platforms must additionally provide a [DatabaseDriverFactory]. */
val sharedModule = module {
    single { GainsDatabase(get<DatabaseDriverFactory>().createDriver()) }
    single { SessionRepository(get()) }
    single { ExerciseRepository(get()) }
    single { BodyweightRepository(get()) }
    single { SettingsRepository(get()) }
    single { ProgramRepository(get(), get()) }
    single { TrainingData(get(), get()) }
    single { ImportService(get(), get()) }
    // Replace with real ids when the Google / Apple credentials and the sync server exist.
    single { AuthConfig() }
    single { AccountRepository(get(), get()) }
    // Strava: leave the config blank and the athlete enters their own API application's credentials in the app.
    single { StravaConfig() }
    single { StravaApi(platformHttpEngine()) }
    single { StravaRepository(get(), get()) }
    single { StravaService(get(), get(), get(), get(), get(), get(), get()) }
}
