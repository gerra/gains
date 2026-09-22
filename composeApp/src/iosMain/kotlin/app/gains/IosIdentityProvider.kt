package app.gains

import app.gains.auth.AccountKind
import app.gains.auth.AuthNotConfiguredException
import app.gains.auth.IdentityAssertion
import app.gains.auth.IdentityProvider
import app.gains.auth.SignInCancelledException
import kotlinx.cinterop.ExperimentalForeignApi
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
import platform.Foundation.NSError
import platform.Foundation.NSPersonNameComponents
import platform.Foundation.NSPersonNameComponentsFormatter
import platform.UIKit.UIApplication
import platform.UIKit.UIWindow
import platform.darwin.NSObject

/**
 * The native sign-in sheets on iOS. Apple goes through AuthenticationServices, whose identity
 * token the server checks against the bundle id (docs/sync.md, "Signing in"). Google is not wired
 * up yet, so its button stays disabled and a call for it says so.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosIdentityProvider : IdentityProvider {
    // Keep strong references until the sheet answers: the controller only holds its delegate and
    // presentation-context provider weakly, and nothing else holds the controller.
    private var controller: ASAuthorizationController? = null
    private var delegate: AppleDelegate? = null

    override suspend fun signIn(kind: AccountKind): IdentityAssertion = when (kind) {
        AccountKind.APPLE -> withContext(Dispatchers.Main) { signInWithApple() }
        AccountKind.GOOGLE, AccountKind.GUEST -> throw AuthNotConfiguredException(kind)
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
        controller.presentationContextProvider = delegate
        this.controller = controller
        this.delegate = delegate
        controller.performRequests()
    }

    private class AppleDelegate(private val onResult: (Result<IdentityAssertion>) -> Unit) :
        NSObject(),
        ASAuthorizationControllerDelegateProtocol,
        ASAuthorizationControllerPresentationContextProvidingProtocol {

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

        override fun presentationAnchorForAuthorizationController(controller: ASAuthorizationController): ASPresentationAnchor =
            UIApplication.sharedApplication.keyWindow ?: UIWindow()
    }
}

/** The name as the person's locale writes it; null when empty, since Apple sends it only on the first sign-in. */
private fun displayName(components: NSPersonNameComponents): String? =
    NSPersonNameComponentsFormatter().stringFromPersonNameComponents(components).takeIf { it.isNotBlank() }
