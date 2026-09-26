package app.gains.auth

import app.gains.data.SettingsRepository
import app.gains.sync.SyncApi
import app.gains.sync.SyncStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
     * for the app itself. Apple's web flow ([AppleWebFlow], on the desktop) uses the server's
     * Services ID; the server holds it and runs that flow, so there it only switches the button on.
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

/**
 * What a platform's sign-in hands to [AccountRepository], which trades it for our token. Most
 * flows end with the provider's identity token ([IdentityAssertion]); Apple's web flow, on
 * Android and the desktop, ends on our server instead and leaves the app an [ExchangeCode].
 */
sealed interface SignInProof

/**
 * What a platform's sign-in sheet hands back: the provider's identity token and, when it was given,
 * the person's name. [authorizationCode] is Apple's one-time code, which the server exchanges for
 * the refresh token it revokes when the account is deleted; it expires in five minutes, so it is
 * sent with the sign-in and never stored.
 */
data class IdentityAssertion(val token: String, val name: String? = null, val authorizationCode: String? = null) : SignInProof

/**
 * The end of a sign-in the server finished itself ([AppleWebFlow]): Apple posted the identity
 * token to the server, which signed the person in and sent the browser back to the app with this
 * one-time [code]. `POST /auth/exchange` trades it for our token, once and within a minute.
 */
data class ExchangeCode(val code: String) : SignInProof

/**
 * The platform's sign-in: Sign in with Apple through AuthenticationServices on iOS, Google
 * through Credential Manager on Android, and through [GoogleOAuth] in a browser sheet on iOS and
 * in the person's browser on the desktop, where Apple goes through [AppleWebFlow]. Each platform
 * registers its own in Koin; the shared module's default offers nothing.
 */
interface IdentityProvider {
    /**
     * Shows the provider's sheet and returns what it proved. Throws [AuthNotConfiguredException]
     * when the platform has no such sheet, and [SignInCancelledException] when the person closes it.
     */
    suspend fun signIn(kind: AccountKind): SignInProof
}

/** Any platform that has not registered a provider yet, and the tests. */
object NoIdentityProvider : IdentityProvider {
    override suspend fun signIn(kind: AccountKind): SignInProof = throw AuthNotConfiguredException(kind)
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
    /** Held while a sign-in stores its token and account, so [forgetOrphanedToken] never sees one without the other. */
    private val writing = Mutex()

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

    /**
     * One path for every sign-in: whatever the platform proved becomes our token, and storing it
     * and starting the feed happen the same way. The web flow's name reaches the server from Apple
     * directly, so only an [IdentityAssertion] has one to fall back on.
     */
    private suspend fun signIn(kind: AccountKind) {
        val proof = identity.signIn(kind)
        val response = when (proof) {
            is IdentityAssertion -> api.signIn(kind, proof.token, proof.name, proof.authorizationCode)
            is ExchangeCode -> api.exchange(proof.code)
        }
        val name = response.user.name ?: (proof as? IdentityAssertion)?.name
        writing.withLock {
            store.setToken(response.token)
            store.setTokenIssuedAt(Clock.System.now().toString())
            store.startFeed(response.user.id)
            settings.set(KEY_ACCOUNT, encode(Account(kind, name, response.user.email)))
        }
    }

    /**
     * Clears a token that no signed-in account goes with. The iOS Keychain outlives the app while
     * its database does not, so after a reinstall the vault still holds the last install's token
     * with no account or a guest in front of it; it should not linger there. Called once at start.
     */
    suspend fun forgetOrphanedToken() = writing.withLock {
        val account = decode(settings.observe(KEY_ACCOUNT).first())
        if ((account == null || account.isGuest) && store.token() != null) store.clearToken()
    }

    /** Swaps the token for a fresh one. Called by the sync controller once a week; a 401 means the person must sign in again. */
    suspend fun refreshToken() {
        if (store.token() == null) return
        val response = api.refresh()
        store.setToken(response.token)
        store.setTokenIssuedAt(Clock.System.now().toString())
    }

    /**
     * Forgets the account and its token. Local data is kept; the user lands on the sign-in screen
     * again. The account row is cleared even when the vault can't clear the token (the Keychain
     * throws when `SecItemDelete` fails): the callers launch this with no exception handler, and an
     * uncaught exception crashes a Kotlin/Native app. The token left behind has no account in front
     * of it, so [forgetOrphanedToken] drops it at the next start.
     */
    suspend fun signOut() {
        try {
            store.clearToken()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Orphaned now; cleared at the next start.
        }
        settings.set(KEY_ACCOUNT, "")
    }

    /**
     * Removes the account and everything it holds on the server, then signs out. Local data is
     * kept, and the feed is forgotten so a later sign-in uploads all of it again. If the server
     * call fails nothing local changes: the person is still signed in and can try again.
     */
    suspend fun deleteAccount() {
        api.deleteAccount()
        store.forgetFeed()
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
