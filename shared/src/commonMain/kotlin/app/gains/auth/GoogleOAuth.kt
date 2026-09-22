package app.gains.auth

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLParameter
import io.ktor.http.parameters
import io.ktor.http.parseQueryString
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64

/** Google's authorization server said no, or answered with something that is not a sign-in. */
class GoogleOAuthException(message: String) : Exception(message)

/**
 * Sign in with Google as an OAuth 2.0 authorization-code flow with PKCE, which is what the
 * GoogleSignIn SDK does inside. Doing it here instead keeps the SDK and a Swift bridge out of the
 * iOS project: the platform only shows [authorizationUrl] in a browser sheet and hands back the
 * redirect, and everything else is plain Kotlin that the desktop tests can check.
 *
 * It needs an **iOS** OAuth client: those accept a redirect to their own reversed-id scheme and
 * have no secret, since the id ships inside every copy of the app. The `id_token` it returns has
 * that client id as its audience, which is why the server lists it in `GOOGLE_CLIENT_IDS`.
 */
object GoogleOAuth {
    const val AUTHORIZATION_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
    const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
    private const val CLIENT_ID_SUFFIX = ".apps.googleusercontent.com"
    private const val SCHEME_PREFIX = "com.googleusercontent.apps."

    /** `<prefix>.apps.googleusercontent.com` → `com.googleusercontent.apps.<prefix>`, the scheme Google redirects an iOS client to. */
    fun redirectScheme(clientId: String): String {
        require(clientId.endsWith(CLIENT_ID_SUFFIX)) { "Not a Google OAuth client id: $clientId" }
        return SCHEME_PREFIX + clientId.removeSuffix(CLIENT_ID_SUFFIX)
    }

    fun redirectUri(clientId: String): String = "${redirectScheme(clientId)}:/oauth2redirect"

    /**
     * The page the browser sheet opens. `prompt=select_account` shows the account chooser every
     * time, so a person with several Google accounts can pick the one they want.
     */
    fun authorizationUrl(clientId: String, codeChallenge: String, state: String): String {
        val query = listOf(
            "client_id" to clientId,
            "redirect_uri" to redirectUri(clientId),
            "response_type" to "code",
            "scope" to "openid email profile",
            "code_challenge" to codeChallenge,
            "code_challenge_method" to "S256",
            "state" to state,
            "prompt" to "select_account",
        ).joinToString("&") { (name, value) -> "${name.encodeURLParameter()}=${value.encodeURLParameter()}" }
        return "$AUTHORIZATION_ENDPOINT?$query"
    }

    /**
     * The authorization code from the redirect Google sent back. Throws when Google reports an
     * error, when there is no code, or when `state` is not the one this sign-in sent, which would
     * mean the redirect belongs to some other request.
     */
    fun parseCallback(url: String, expectedState: String): String {
        val query = url.substringBefore('#').substringAfter('?', missingDelimiterValue = "")
        val parameters = parseQueryString(query)
        parameters["error"]?.let { throw GoogleOAuthException("Google sign-in failed: $it") }
        if (parameters["state"] != expectedState) throw GoogleOAuthException("Google sign-in answered a different request.")
        return parameters["code"]?.takeIf { it.isNotEmpty() } ?: throw GoogleOAuthException("Google returned no authorization code.")
    }

    /** Trades the code for Google's identity token, proving with [verifier] that this is the app that asked. */
    suspend fun exchange(client: HttpClient, clientId: String, code: String, verifier: String): String {
        val response = client.submitForm(
            TOKEN_ENDPOINT,
            parameters {
                append("grant_type", "authorization_code")
                append("client_id", clientId)
                append("code", code)
                append("code_verifier", verifier)
                append("redirect_uri", redirectUri(clientId))
            },
        )
        val body = runCatching { json.decodeFromString(TokenResponse.serializer(), response.bodyAsText()) }.getOrNull()
        if (response.status.value !in 200..299) {
            throw GoogleOAuthException("Google sign-in failed: ${body?.errorDescription ?: body?.error ?: response.status.value}")
        }
        return body?.idToken?.takeIf { it.isNotEmpty() } ?: throw GoogleOAuthException("Google returned no identity token.")
    }

    /** Base64url without padding, the encoding PKCE uses for the verifier, the challenge and here the state. */
    fun base64Url(bytes: ByteArray): String = base64.encode(bytes)

    private val base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private class TokenResponse(
        @SerialName("id_token") val idToken: String? = null,
        val error: String? = null,
        @SerialName("error_description") val errorDescription: String? = null,
    )
}
