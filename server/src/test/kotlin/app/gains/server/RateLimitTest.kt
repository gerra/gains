package app.gains.server

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RateLimitTest {
    @Test
    fun hitsInsideTheWindowCountAndOlderOnesDrop() {
        var now = Instant.parse("2026-09-28T10:00:00Z")
        val limit = RateLimit(2, Duration.ofMinutes(1), clock = { now })
        assertTrue(limit.allow("a"))
        assertTrue(limit.allow("a"))
        assertFalse(limit.allow("a"))
        assertTrue(limit.allow("b"), "keys are separate")
        now += Duration.ofSeconds(59)
        assertFalse(limit.allow("a"), "a refused hit is not counted, and the window has not passed")
        now += Duration.ofSeconds(2)
        assertTrue(limit.allow("a"))
    }

    @Test
    fun theTableIsBounded() {
        var now = Instant.parse("2026-09-28T10:00:00Z")
        val limit = RateLimit(1, Duration.ofMinutes(1), clock = { now }, maxKeys = 3)
        for (key in listOf("a", "b", "c")) assertTrue(limit.allow(key))
        assertFalse(limit.allow("a"))
        // A fourth key when the table is full: quiet keys go first; none is quiet yet, so everything goes.
        assertTrue(limit.allow("d"))
        assertTrue(limit.allow("a"), "forgotten with the rest")
        now += Duration.ofMinutes(2)
        assertTrue(limit.allow("e"))
        assertTrue(limit.allow("f"))
        assertTrue(limit.allow("g"), "the expired keys made room without a wipe")
    }
}
