package app.gains

import app.gains.auth.AccountKind
import app.gains.auth.AuthConfig
import app.gains.auth.AuthNotConfiguredException
import app.gains.auth.GoogleOAuth
import app.gains.auth.IdentityAssertion
import app.gains.auth.IdentityProvider
import app.gains.auth.SignInCancelledException
import io.ktor.client.HttpClient
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.AuthenticationServices.ASAuthorization
import platform.AuthenticationServices.ASAuthorizationAppleIDCredential
import platform.AuthenticationServices.ASAuthorizationAppleIDProvider
import platform.AuthenticationServices.ASAuthorizationController
import platform.AuthenticationServices.ASAuthorizationControllerDelegateProtocol
import platform.AuthenticationServices.ASAuthorizationControllerPresentationContextProvidingProtocol
import platform.AuthenticationServices.ASAuthorizationErrorCanceled
import platform.AuthenticationServices.ASAuthorizationErrorDomain
import platform.AuthenticationServices.ASAuthorizationScopeEmail
import platform.AuthenticationServices.ASAuthorizationScopeFullName
import platform.AuthenticationServices.ASPresentationAnchor
import platform.AuthenticationServices.ASWebAuthenticationPresentationContextProvidingProtocol
import platform.AuthenticationServices.ASWebAuthenticationSession
import platform.AuthenticationServices.ASWebAuthenticationSessionErrorCodeCanceledLogin
import platform.AuthenticationServices.ASWebAuthenticationSessionErrorDomain
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.Foundation.NSError
import platform.Foundation.NSPersonNameComponents
import platform.Foundation.NSPersonNameComponentsFormatter
import platform.Foundation.NSURL
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault
import platform.UIKit.UIApplication
import platform.UIKit.UIWindow
import platform.darwin.NSObject

/**
 * The native sign-in sheets on iOS (docs/sync.md, "Signing in"). Apple goes through
 * AuthenticationServices, whose identity token the server checks against the bundle id. Google is
 * [GoogleOAuth]'s PKCE flow in an `ASWebAuthenticationSession` rather than the GoogleSignIn SDK, so
 * the project needs no Swift package and no bridge; its token's audience is [AuthConfig.googleClientId].
 * [http] is the app's client, used for the one call to Google's token endpoint.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosIdentityProvider(private val config: AuthConfig, private val http: HttpClient) : IdentityProvider {
    // Keep strong references until the sheet answers: the controller only holds its delegate and
    // presentation-context provider weakly, the session its provider too, and nothing else holds
    // the controller or the session.
    private var controller: ASAuthorizationController? = null
    private var delegate: AppleDelegate? = null
    private var session: ASWebAuthenticationSession? = null
    private val anchor = KeyWindowAnchor()

    override suspend fun signIn(kind: AccountKind): IdentityAssertion = when (kind) {
        AccountKind.APPLE -> withContext(Dispatchers.Main) { signInWithApple() }
        AccountKind.GOOGLE -> signInWithGoogle()
        AccountKind.GUEST -> throw AuthNotConfiguredException(kind)
    }

    /**
     * Shows the Sign in with Apple sheet. A cancelled coroutine leaves the sheet up and ignores its
     * answer: the controller cannot be taken down from here, and dropping it early would free the
     * delegate the sheet is about to call.
     */
    private suspend fun signInWithApple(): IdentityAssertion = suspendCancellableCoroutine { continuation ->
        val request = ASAuthorizationAppleIDProvider().createRequest().apply {
            requestedScopes = listOf(ASAuthorizationScopeFullName, ASAuthorizationScopeEmail)
        }
        val controller = ASAuthorizationController(authorizationRequests = listOf(request))
        val delegate = AppleDelegate { result ->
            this.controller = null
            this.delegate = null
            continuation.resumeWith(result)
        }
        controller.delegate = delegate
        controller.presentationContextProvider = anchor
        this.controller = controller
        this.delegate = delegate
        controller.performRequests()
    }

    /**
     * Google's account chooser in a browser sheet, then the code it redirects with traded for an
     * identity token. The name is left out: the server reads it from Google's token.
     */
    private suspend fun signInWithGoogle(): IdentityAssertion {
        val clientId = config.googleClientId?.takeIf { it.isNotBlank() } ?: throw AuthNotConfiguredException(AccountKind.GOOGLE)
        val verifier = GoogleOAuth.base64Url(randomBytes(32))
        val challenge = GoogleOAuth.base64Url(sha256(verifier.encodeToByteArray()))
        val state = GoogleOAuth.base64Url(randomBytes(32))
        val redirect = withContext(Dispatchers.Main) {
            authenticate(GoogleOAuth.authorizationUrl(clientId, challenge, state), GoogleOAuth.redirectScheme(clientId))
        }
        val code = GoogleOAuth.parseCallback(redirect, state)
        return IdentityAssertion(GoogleOAuth.exchange(http, clientId, code, verifier), name = null)
    }

    /**
     * Opens [url] in the system browser sheet and returns the URL it was redirected to on
     * [callbackScheme]. The sheet catches that scheme itself, so Info.plist registers no URL type.
     * It shares Safari's cookies, so someone already signed in to Google only picks an account.
     * As with Apple, a cancelled coroutine leaves the sheet up and ignores its answer.
     */
    private suspend fun authenticate(url: String, callbackScheme: String): String = suspendCancellableCoroutine { continuation ->
        val target = NSURL.URLWithString(url)
        if (target == null) {
            continuation.resumeWith(Result.failure(IllegalStateException("Not a URL: $url")))
            return@suspendCancellableCoroutine
        }
        val session = ASWebAuthenticationSession(target, callbackScheme) { callback: NSURL?, error: NSError? ->
            this.session = null
            val redirect = callback?.absoluteString
            val cancelled = error != null && error.domain == ASWebAuthenticationSessionErrorDomain && error.code == ASWebAuthenticationSessionErrorCodeCanceledLogin
            continuation.resumeWith(
                when {
                    redirect != null -> Result.success(redirect)
                    cancelled -> Result.failure(SignInCancelledException())
                    else -> Result.failure(IllegalStateException(error?.localizedDescription ?: "Google sign-in returned nothing."))
                }
            )
        }
        session.presentationContextProvider = anchor
        session.prefersEphemeralWebBrowserSession = false
        this.session = session
        if (!session.start()) {
            this.session = null
            continuation.resumeWith(Result.failure(IllegalStateException("Could not open the Google sign-in sheet.")))
        }
    }

    private class AppleDelegate(private val onResult: (Result<IdentityAssertion>) -> Unit) :
        NSObject(),
        ASAuthorizationControllerDelegateProtocol {

        override fun authorizationController(controller: ASAuthorizationController, didCompleteWithAuthorization: ASAuthorization) {
            val credential = didCompleteWithAuthorization.credential as? ASAuthorizationAppleIDCredential
            val token = credential?.identityToken?.toByteArray()?.decodeToString()
            onResult(
                if (credential == null || token.isNullOrEmpty()) Result.failure(IllegalStateException("Apple returned no identity token."))
                else Result.success(IdentityAssertion(token, credential.fullName?.let(::displayName)))
            )
        }

        override fun authorizationController(controller: ASAuthorizationController, didCompleteWithError: NSError) {
            val cancelled = didCompleteWithError.domain == ASAuthorizationErrorDomain && didCompleteWithError.code == ASAuthorizationErrorCanceled
            onResult(Result.failure(if (cancelled) SignInCancelledException() else IllegalStateException(didCompleteWithError.localizedDescription)))
        }
    }

    /** Where both sheets are presented: the app's key window. */
    private class KeyWindowAnchor :
        NSObject(),
        ASAuthorizationControllerPresentationContextProvidingProtocol,
        ASWebAuthenticationPresentationContextProvidingProtocol {

        override fun presentationAnchorForAuthorizationController(controller: ASAuthorizationController): ASPresentationAnchor = keyWindow()

        override fun presentationAnchorForWebAuthenticationSession(session: ASWebAuthenticationSession): ASPresentationAnchor = keyWindow()

        private fun keyWindow(): UIWindow = UIApplication.sharedApplication.keyWindow ?: UIWindow()
    }
}

/** [count] bytes from the system's secure random source, for the PKCE verifier and the OAuth state. */
@OptIn(ExperimentalForeignApi::class)
private fun randomBytes(count: Int): ByteArray {
    val bytes = ByteArray(count)
    val status = bytes.usePinned { SecRandomCopyBytes(kSecRandomDefault, count.convert(), it.addressOf(0)) }
    check(status == 0) { "SecRandomCopyBytes failed: $status" }
    return bytes
}

/** SHA-256 of [input], which turns the PKCE verifier into its challenge. [input] must not be empty. */
@OptIn(ExperimentalForeignApi::class, ExperimentalUnsignedTypes::class)
private fun sha256(input: ByteArray): ByteArray {
    val digest = UByteArray(CC_SHA256_DIGEST_LENGTH)
    input.usePinned { data ->
        digest.usePinned { out -> CC_SHA256(data.addressOf(0), input.size.convert(), out.addressOf(0)) }
    }
    return digest.asByteArray()
}

/** The name as the person's locale writes it; null when empty, since Apple sends it only on the first sign-in. */
private fun displayName(components: NSPersonNameComponents): String? =
    NSPersonNameComponentsFormatter().stringFromPersonNameComponents(components).takeIf { it.isNotBlank() }
