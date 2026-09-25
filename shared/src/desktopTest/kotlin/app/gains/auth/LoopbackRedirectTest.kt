package app.gains.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The desktop's loopback listener, with real sockets on 127.0.0.1 standing in for the browser. */
class LoopbackRedirectTest {
    @Test
    fun itAnswersTheRedirectAndReturnsItsUrl() = runBlocking {
        LoopbackRedirect.open().use { loopback ->
            val redirect = async { loopback.receive("<p>done</p>") }
            val (status, body) = get("http://127.0.0.1:${loopback.port}/?state=s1&code=4%2F0Ab")
            assertEquals(200, status)
            assertEquals("<p>done</p>", body)
            assertEquals("http://127.0.0.1:${loopback.port}/?state=s1&code=4%2F0Ab", withTimeout(5.seconds) { redirect.await() })
        }
    }

    /** Chrome's speculative connections send nothing; they must not hold up the real request, nor must the favicon. */
    @Test
    fun anIdleConnectionAndTheFaviconDontCount() = runBlocking {
        LoopbackRedirect.open().use { loopback ->
            val redirect = async { loopback.receive("done") }
            Socket("127.0.0.1", loopback.port).use {
                assertEquals(404, get("http://127.0.0.1:${loopback.port}/favicon.ico").first)
                assertEquals(200, get("http://127.0.0.1:${loopback.port}/?error=access_denied&state=s1").first)
                assertEquals("http://127.0.0.1:${loopback.port}/?error=access_denied&state=s1", withTimeout(5.seconds) { redirect.await() })
            }
        }
    }

    /** The provider's timeout: a closed tab never calls back, and the wait must still end. */
    @Test
    fun waitingCanBeCancelled() = runBlocking {
        LoopbackRedirect.open().use { loopback ->
            Socket("127.0.0.1", loopback.port).use {
                assertNull(withTimeoutOrNull(300.milliseconds) { loopback.receive("done") })
            }
        }
    }

    private suspend fun get(url: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        try {
            val status = connection.responseCode
            val body = (if (status < 400) connection.inputStream else connection.errorStream).bufferedReader().readText()
            status to body
        } finally {
            connection.disconnect()
        }
    }
}
