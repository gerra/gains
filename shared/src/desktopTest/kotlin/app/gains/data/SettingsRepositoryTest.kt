package app.gains.data

import app.gains.db.GainsDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The preferences that are only a stored word: what is read back, and what an unknown one falls back to. */
class SettingsRepositoryTest {
    private fun newSettings() = SettingsRepository(GainsDatabase(DesktopDriverFactory(file = null).createDriver()), Dispatchers.Unconfined)

    @Test
    fun theLanguageIsTheDevicesUntilOneIsChosen() = runTest {
        val settings = newSettings()
        assertEquals(AppLanguage.SYSTEM, settings.observeLanguage().first())

        settings.setLanguage(AppLanguage.RUSSIAN)
        assertEquals(AppLanguage.RUSSIAN, settings.observeLanguage().first())

        settings.setLanguage(AppLanguage.SYSTEM)
        assertEquals(AppLanguage.SYSTEM, settings.observeLanguage().first())
    }

    @Test
    fun aLanguageTheAppNoLongerKnowsFallsBackToTheDevices() = runTest {
        val settings = newSettings()
        settings.set(SettingsRepository.KEY_LANGUAGE, "KLINGON")
        assertEquals(AppLanguage.SYSTEM, settings.observeLanguage().first())
    }
}
