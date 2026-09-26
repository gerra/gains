package app.gains.auth

import io.ktor.http.parseQueryString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The app's end of Apple's web flow: the start URL it opens and the callback the server sends back. */
class AppleWebFlowTest {
    @Test
    fun theStartUrlCarriesTheCallbackAndStateEncoded() {
        val callback = AppleWebFlow.loopbackCallback(53682)
        assertEquals("http://127.0.0.1:53682/", callback)
        val url = AppleWebFlow.startUrl("https://api.gains.gerra.sh", callback, state = "a b&c")
        assertTrue(url.startsWith("https://api.gains.gerra.sh/auth/apple/start?"), url)
        val query = url.substringAfter('?')
        assertTrue(" " !in query && "&c" !in query, "encoded: $query")
        val parameters = parseQueryString(query)
        assertEquals(callback, parameters["redirect"])
        assertEquals("a b&c", parameters["state"])
        assertFailsWith<IllegalArgumentException> { AppleWebFlow.loopbackCallback(0) }
    }

    @Test
    fun theCallbackGivesTheCodeForThisSignInOnly() {
        assertEquals("one-time", AppleWebFlow.parseCallback("http://127.0.0.1:1/?code=one-time&state=s1", "s1"))
        assertFailsWith<AppleSignInException> { AppleWebFlow.parseCallback("http://127.0.0.1:1/?code=one-time&state=s2", "s1") }
        assertFailsWith<AppleSignInException> { AppleWebFlow.parseCallback("http://127.0.0.1:1/?state=s1", "s1") }
        assertFailsWith<AppleSignInException> { AppleWebFlow.parseCallback("http://127.0.0.1:1/", "s1") }
    }

    @Test
    fun aClosedApplePageIsACancelAndAnythingElseAFailure() {
        assertFailsWith<SignInCancelledException> { AppleWebFlow.parseCallback("http://127.0.0.1:1/?error=cancelled&state=s1", "s1") }
        assertFailsWith<AppleSignInException> { AppleWebFlow.parseCallback("http://127.0.0.1:1/?error=failed&state=s1", "s1") }
        // Someone else's cancel is not ours: it can't end this sign-in quietly.
        assertFailsWith<AppleSignInException> { AppleWebFlow.parseCallback("http://127.0.0.1:1/?error=cancelled&state=s2", "s1") }
    }
}
