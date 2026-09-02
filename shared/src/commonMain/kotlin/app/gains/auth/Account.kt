package app.gains.auth

import app.gains.data.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class AccountKind(val label: String) { GUEST("Guest"), GOOGLE("Google"), APPLE("Apple") }

data class Account(
    val kind: AccountKind,
    val displayName: String? = null,
    val email: String? = null,
) {
    val isGuest: Boolean get() = kind == AccountKind.GUEST
}

/**
 * Third-party sign-in and the sync server are not wired up yet. Fill these in when the
 * credentials exist; each provider's button is enabled only when its id is present.
 */
data class AuthConfig(
    val googleClientId: String? = null,
    val appleServiceId: String? = null,
    /** Base URL of the self-hosted sync server (Hetzner). */
    val serverBaseUrl: String? = null,
) {
    val googleEnabled: Boolean get() = !googleClientId.isNullOrBlank()
    val appleEnabled: Boolean get() = !appleServiceId.isNullOrBlank()
    val syncEnabled: Boolean get() = !serverBaseUrl.isNullOrBlank()
}

class AuthNotConfiguredException(provider: AccountKind) :
    IllegalStateException("${provider.label} sign-in is not configured yet.")

/** Persists which account the app is running under. Sign-in providers are stubs until configured. */
class AccountRepository(
    private val settings: SettingsRepository,
    private val config: AuthConfig,
) {
    fun observeAccount(): Flow<Account?> = settings.observe(KEY_ACCOUNT).map { decode(it) }

    suspend fun continueAsGuest() = settings.set(KEY_ACCOUNT, encode(Account(AccountKind.GUEST)))

    suspend fun signInWithGoogle() {
        if (!config.googleEnabled) throw AuthNotConfiguredException(AccountKind.GOOGLE)
        // TODO: exchange the Google ID token with the sync server, then store the account.
        throw AuthNotConfiguredException(AccountKind.GOOGLE)
    }

    suspend fun signInWithApple() {
        if (!config.appleEnabled) throw AuthNotConfiguredException(AccountKind.APPLE)
        // TODO: exchange the Apple identity token with the sync server, then store the account.
        throw AuthNotConfiguredException(AccountKind.APPLE)
    }

    /** Forgets the account. Local data is kept; the user lands on the sign-in screen again. */
    suspend fun signOut() = settings.set(KEY_ACCOUNT, "")

    companion object {
        const val KEY_ACCOUNT = "account"
        private const val SEP = "|"

        fun encode(account: Account): String =
            listOf(account.kind.name, account.displayName ?: "", account.email ?: "").joinToString(SEP)

        fun decode(raw: String?): Account? {
            if (raw.isNullOrEmpty()) return null
            val parts = raw.split(SEP)
            val kind = AccountKind.entries.firstOrNull { it.name == parts[0] } ?: return null
            return Account(kind, parts.getOrNull(1)?.ifEmpty { null }, parts.getOrNull(2)?.ifEmpty { null })
        }
    }
}
