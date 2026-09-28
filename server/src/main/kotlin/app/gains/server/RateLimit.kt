package app.gains.server

import java.time.Duration
import java.time.Instant

/**
 * At most [max] hits per key in any [window]: the brake on password guessing and on mail sending
 * (docs/launch-plan.md, item 18), keyed by the address tried and by the caller's IP. In memory,
 * like [AppleWebSignIn]'s maps: one server process, and a restart only forgets the count.
 * [maxKeys] caps the table, so hitting it with fresh keys can't grow it without bound; when the
 * cap is reached the keys that have gone quiet are dropped first, then the whole table.
 */
class RateLimit(
    private val max: Int,
    private val window: Duration,
    private val clock: () -> Instant = Instant::now,
    private val maxKeys: Int = 100_000,
) {
    private val hits = HashMap<String, ArrayDeque<Instant>>()

    /** Counts a hit for [key] and says whether it was within the limit. A refused hit is not counted. */
    @Synchronized
    fun allow(key: String): Boolean {
        val now = clock()
        val since = now - window
        val queue = hits[key] ?: run {
            if (hits.size >= maxKeys) {
                hits.values.removeIf { q -> q.lastOrNull()?.let { it <= since } ?: true }
                if (hits.size >= maxKeys) hits.clear()
            }
            ArrayDeque<Instant>().also { hits[key] = it }
        }
        while (queue.isNotEmpty() && queue.first() <= since) queue.removeFirst()
        if (queue.size >= max) return false
        queue.addLast(now)
        return true
    }
}
