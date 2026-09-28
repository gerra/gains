package app.gains

import android.app.Activity
import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import app.gains.auth.AccountKind
import app.gains.auth.AppleWebCallback
import app.gains.auth.AppleWebFlow
import app.gains.auth.AuthConfig
import app.gains.auth.AuthNotConfiguredException
import app.gains.auth.ExchangeCode
import app.gains.auth.GoogleOAuth
import app.gains.auth.IdentityAssertion
import app.gains.auth.IdentityProvider
import app.gains.auth.SignInCancelledException
import app.gains.auth.SignInProof
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import java.security.SecureRandom

/**
 * Sign-in on Android (docs/sync.md, "Signing in"). Google goes through Credential Manager's
 * account chooser, a bottom sheet Play services draws over the app, which hands back an identity
 * token whose audience is the **Web application** client ([AuthConfig.googleClientId], the
 * `serverClientId`): the server lists that client in `GOOGLE_CLIENT_IDS`. The sheet needs the
 * activity in front to be shown from, which [activity] supplies at the moment of the tap; the
 * application context alone can't host it.
 *
 * Sign in with Apple has no native sheet on Android, so it is [AppleWebFlow]: the server's start
 * page opens in a Custom Tab (the browser's own tab drawn over the app, with its cookies, so an
 * Apple ID already signed in there is offered), Apple posts to the server, and the server sends the
 * tab to the App Link `https://gains.gerra.sh/auth/done` with a one-time code. [WebSignIn] carries
 * that URL back here, and the code goes to `AccountRepository` as an [ExchangeCode].
 *
 * Closing either sheet is a [SignInCancelledException], so the screen stays quiet.
 */
internal class AndroidIdentityProvider(
    private val config: AuthConfig,
    private val context: Context,
    private val activity: () -> Activity?,
) : IdentityProvider {
    override suspend fun signIn(kind: AccountKind): SignInProof = when (kind) {
        AccountKind.GOOGLE -> signInWithGoogle()
        AccountKind.APPLE -> signInWithApple()
        // An email account has no sheet: AccountRepository posts the password itself.
        AccountKind.GUEST, AccountKind.EMAIL -> throw AuthNotConfiguredException(kind)
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

    /**
     * Apple's page through our server in a Custom Tab, which comes back with a one-time code. Apple
     * sends the name to the server, which keeps it, so there is none to pass along here. The wait
     * ends with the callback, or as a cancel when the app comes back to the front without one.
     * The callback's state is checked before the code is trusted: a link that answers another
     * request says nothing about this one.
     */
    private suspend fun signInWithApple(): ExchangeCode {
        if (!config.appleEnabled) throw AuthNotConfiguredException(AccountKind.APPLE)
        val server = checkNotNull(config.serverBaseUrl)
        val host = activity() ?: error("No activity to open the browser from")
        val state = GoogleOAuth.base64Url(ByteArray(32).also(random::nextBytes))
        val callback = WebSignIn.expect()
        // From the activity, so the tab joins its task: coming back then only has to clear the
        // tab off the top (SignInCallbackActivity), and backing out lands on the same screen.
        CustomTabsIntent.Builder().build().launchUrl(host, Uri.parse(AppleWebFlow.startUrl(server, AppleWebCallback.ANDROID, state)))
        return ExchangeCode(AppleWebFlow.parseCallback(callback.await(), state))
    }

    private companion object {
        val random = SecureRandom()
    }
}

/**
 * The Android app's sign-in settings, from string resources that `:androidApp` fills from the
 * Gradle properties `gains.serverUrl`, `gains.googleWebClientId`, `gains.appleServicesId` and
 * `gains.passwordSignIn` (`androidApp/build.gradle.kts`, `res/values/sign_in_config.xml`; see
 * docs/development.md, "Android"), so they change without touching code. Google stays off until
 * the web client id is there, and its button stays hidden. Apple stays off until the Services ID
 * is there, which the owner sets once the server has `APPLE_SERVICES_ID`: before that
 * `/auth/apple/start` answers 503, and the button would only open an error page.
 */
internal fun androidAuthConfig(context: Context): AuthConfig = AuthConfig(
    serverBaseUrl = context.getString(R.string.gains_server_url).trim().trimEnd('/').ifBlank { null },
    googleClientId = context.getString(R.string.gains_google_web_client_id).trim().ifBlank { null },
    appleServiceId = context.getString(R.string.gains_apple_services_id).trim().ifBlank { null },
    passwordSignIn = context.getString(R.string.gains_password_sign_in).trim().toBoolean(),
)
