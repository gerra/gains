package app.gains

import android.app.Activity
import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import app.gains.auth.AccountKind
import app.gains.auth.AuthConfig
import app.gains.auth.AuthNotConfiguredException
import app.gains.auth.IdentityAssertion
import app.gains.auth.IdentityProvider
import app.gains.auth.SignInCancelledException
import app.gains.auth.SignInProof
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

/**
 * Sign-in on Android (docs/sync.md, "Signing in"). Google goes through Credential Manager's
 * account chooser, a bottom sheet Play services draws over the app, which hands back an identity
 * token whose audience is the **Web application** client ([AuthConfig.googleClientId], the
 * `serverClientId`): the server lists that client in `GOOGLE_CLIENT_IDS`. The sheet needs the
 * activity in front to be shown from, which [activity] supplies at the moment of the tap; the
 * application context alone can't host it.
 *
 * Closing the sheet is a [SignInCancelledException], so the screen stays quiet. Sign in with Apple
 * has no native sheet on Android and waits for the server's web flow (docs/launch-plan.md, item
 * 11); until then it is [AuthNotConfiguredException], and its button stays hidden anyway.
 */
internal class AndroidIdentityProvider(
    private val config: AuthConfig,
    private val context: Context,
    private val activity: () -> Activity?,
) : IdentityProvider {
    override suspend fun signIn(kind: AccountKind): SignInProof = when (kind) {
        AccountKind.GOOGLE -> signInWithGoogle()
        AccountKind.APPLE, AccountKind.GUEST -> throw AuthNotConfiguredException(kind)
    }

    /** The account chooser, then the token it returns. The server reads the name from Google's token. */
    private suspend fun signInWithGoogle(): IdentityAssertion {
        val clientId = config.googleClientId?.takeIf { it.isNotBlank() } ?: throw AuthNotConfiguredException(AccountKind.GOOGLE)
        val host = activity() ?: error("No activity to show the account chooser from")
        val option = GetGoogleIdOption.Builder()
            .setServerClientId(clientId)
            // Every Google account on the phone, not only those that signed in before: a first
            // sign-in has none, and the sheet would say so instead of offering any.
            .setFilterByAuthorizedAccounts(false)
            // A tap always shows the chooser, even with one account, so the person sees which one it is.
            .setAutoSelectEnabled(false)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val response = try {
            CredentialManager.create(context).getCredential(host, request)
        } catch (e: GetCredentialCancellationException) {
            throw SignInCancelledException()
        }
        val credential = response.credential
        check(credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            "Unexpected credential: ${credential.type}"
        }
        return IdentityAssertion(GoogleIdTokenCredential.createFrom(credential.data).idToken, name = null)
    }
}

/**
 * The Android app's sign-in settings, from `BuildConfig`, which the build fills from the Gradle
 * properties `gains.serverUrl` and `gains.googleWebClientId` (`composeApp/android.gradle`; see
 * docs/development.md, "Android"), so they change without touching code. Google stays off until
 * the web client id is there, and its button stays hidden. Apple waits for item 11.
 */
internal fun androidAuthConfig(): AuthConfig = AuthConfig(
    serverBaseUrl = BuildConfig.SERVER_URL.trim().trimEnd('/').ifBlank { null },
    googleClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID.trim().ifBlank { null },
)
