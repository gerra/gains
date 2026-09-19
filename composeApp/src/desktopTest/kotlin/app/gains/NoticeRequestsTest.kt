package app.gains

import app.gains.platform.NoticeRequests
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Requests from the platform's notice: counted until consumed, and attended only while the UI collects them. */
class NoticeRequestsTest {
    private class Requests : NoticeRequests()

    @Test
    fun countsUntilConsumed() {
        val requests = Requests()
        assertEquals(0, requests.pending.value)
        requests.request()
        requests.request()
        assertEquals(2, requests.pending.value)
        requests.consume()
        assertEquals(0, requests.pending.value)
    }

    @Test
    fun attendedOnlyWhileCollected() = runBlocking {
        val requests = Requests()
        assertFalse(requests.attended)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { requests.pending.collect {} }
        yield()
        assertTrue(requests.attended)
        collector.cancelAndJoin()
        assertFalse(requests.attended)
    }
}
