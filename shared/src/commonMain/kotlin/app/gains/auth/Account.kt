package app.gains.auth

import app.gains.data.SettingsRepository
import app.gains.sync.SyncApi
import app.gains.sync.SyncStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Clock

enum class AccountKind(val label: String) { GUEST("Guest"), GOOGLE("Google"), APPLE("Apple") }

data class Account(
    val kind: AccountKind,
    val displayName: String? = null,
    val email: String? = null,
) {
    val isGuest: Boolean get() = kind == AccountKind.GUEST
}

/**
 * Where the sync server is and which sign-ins the platform can offer. Each provider's button is
 * enabled only when its id is present; nothing leaves the device while [serverBaseUrl] is null.
 */
data class AuthConfig(
    val googleClientId: String? = null,
    /**
     * The audience Apple puts in the identity token, which the server checks against
     * `APPLE_CLIENT_IDS`. On iOS it is the app's bundle id, because the native flow signs tokens
     * for the app itself; a web or Android flow would use a Services ID instead.
     */
    val appleServiceId: String? = null,
    /** Base URL of the sync server (see docs/sync.md), without a trailing slash. */
    val serverBaseUrl: String? = null,
) {
    val googleEnabled: Boolean get() = !googleClientId.isNullOrBlank() && syncEnabled
    val appleEnabled: Boolean get() = !appleServiceId.isNullOrBlank() && syncEnabled
    val syncEnabled: Boolean get() = !serverBaseUrl.isNullOrBlank()
}

class AuthNotConfiguredException(val provider: AccountKind) :
    IllegalStateException("${provider.label} sign-in is not configured yet.")

/**
 * The person closed the provider's sheet without signing in. It is a choice rather than a failure,
 * so the sign-in screens say nothing about it.
 */
class SignInCancelledException : Exception("Sign-in was cancelled.")

/** What a platform's sign-in sheet hands back: the provider's identity token and, when it was given, the person's name. */
data class IdentityAssertion(val token: String, val name: String? = null)

/**
 * The platform's native sign-in: Sign in with Apple through AuthenticationServices on iOS, Google
 * through Credential Manager on Android and through [GoogleOAuth] in a browser sheet on iOS. Each
 * platform registers its own in Koin; the shared module's default offers nothing.
 */
interface IdentityProvider {
    /**
     * Shows the provider's sheet and returns its token. Throws [AuthNotConfiguredException] when
     * the platform has no such sheet, and [SignInCancelledException] when the person closes it.
     */
    suspend fun signIn(kind: AccountKind): IdentityAssertion
}

/** The desktop and any platform that has not registered a provider yet. */
object NoIdentityProvider : IdentityProvider {
    override suspend fun signIn(kind: AccountKind): IdentityAssertion = throw AuthNotConfiguredException(kind)
}

/**
 * Persists which account the app is running under and, for a signed-in one, holds the token the
 * sync server issued. Signing in exchanges the platform's identity token for one of ours
 * (docs/sync.md, "Signing in"); local data is kept whatever happens.
 */
class AccountRepository(
    private val settings: SettingsRepository,
    private val config: AuthConfig,
    private val api: SyncApi,
    private val store: SyncStore,
    private val identity: IdentityProvider,
) {
    fun observeAccount(): Flow<Account?> = settings.observe(KEY_ACCOUNT).map { decode(it) }

    suspend fun continueAsGuest() = settings.set(KEY_ACCOUNT, encode(Account(AccountKind.GUEST)))

    suspend fun signInWithGoogle() {
        if (!config.googleEnabled) throw AuthNotConfiguredException(AccountKind.GOOGLE)
        signIn(AccountKind.GOOGLE)
    }

    suspend fun signInWithApple() {
        if (!config.appleEnabled) throw AuthNotConfiguredException(AccountKind.APPLE)
        signIn(AccountKind.APPLE)
    }

    private suspend fun signIn(kind: AccountKind) {
        val assertion = identity.signIn(kind)
        val response = api.signIn(kind, assertion.token, assertion.name)
        store.setToken(response.token)
        store.setTokenIssuedAt(Clock.System.now().toString())
        store.startFeed(response.user.id)
        settings.set(KEY_ACCOUNT, encode(Account(kind, response.user.name ?: assertion.name, response.user.email)))
    }

    /** Swaps the token for a fresh one. Called by the sync controller once a week; a 401 means the person must sign in again. */
    suspend fun refreshToken() {
        if (store.token() == null) return
        val response = api.refresh()
        store.setToken(response.token)
        store.setTokenIssuedAt(Clock.System.now().toString())
    }

    /** Forgets the account and its token. Local data is kept; the user lands on the sign-in screen again. */
    suspend fun signOut() {
        store.clearToken()
        settings.set(KEY_ACCOUNT, "")
    }

    /** Removes the account and everything it holds on the server, then signs out. Local data is kept. */
    suspend fun deleteAccount() {
        api.deleteAccount()
        signOut()
    }

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
