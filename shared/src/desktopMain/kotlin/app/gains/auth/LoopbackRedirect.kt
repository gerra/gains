package app.gains.auth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel

/**
 * The desktop's end of a browser sign-in: a one-shot HTTP listener on `127.0.0.1` that a provider
 * redirects the browser to, since a desktop app has no URL scheme of its own that every OS routes
 * back to it (docs/sync.md, "Signing in"). [port] is picked by the OS; the caller builds its
 * redirect URI from it, opens the provider's page, then waits in [receive].
 *
 * It binds the loopback address only, so nothing off the machine can reach it, and it answers
 * only the first request that carries a query; the caller still checks that the query's `state`
 * is its own, because any local process could connect first.
 */
class LoopbackRedirect private constructor(private val server: ServerSocketChannel) : Closeable {
    /** Read once: cancelling [receive] interrupts `accept`, which closes the channel. */
    val port: Int = (server.localAddress as InetSocketAddress).port

    /**
     * Waits for the browser to arrive, answers it with [page] (HTML), and returns the full URL it
     * requested, `http://127.0.0.1:<port>/…?…`, for the caller to parse. Requests without a query
     * (the browser's `/favicon.ico`) get a 404 and waiting goes on. Each connection is read on its
     * own, because Chrome opens speculative connections that send nothing, and the real request may
     * arrive on another one. Cancelling the coroutine stops the wait; the caller still closes this.
     */
    suspend fun receive(page: String): String = coroutineScope {
        val result = CompletableDeferred<String>()
        launch(Dispatchers.IO) {
            while (true) {
                val connection = runInterruptible { server.accept() }
                launch { answer(connection, page, result) }
            }
        }
        result.await().also { coroutineContext.cancelChildren() }
    }

    private suspend fun answer(connection: SocketChannel, page: String, result: CompletableDeferred<String>) {
        try {
            respond(connection, page)?.let { result.complete("http://127.0.0.1:$port$it") }
        } finally {
            connection.close()
        }
    }

    /** Answers one connection, and returns its request target when it was the redirect. */
    private suspend fun respond(connection: SocketChannel, page: String): String? {
        // Reads and writes go through the channel so that cancelling interrupts them.
        val target = runInterruptible { requestTarget(connection) } ?: return null
        val redirect = target.startsWith("/") && '?' in target
        runInterruptible {
            val body = (if (redirect) page else "Not found").encodeToByteArray()
            val head = buildString {
                append(if (redirect) "HTTP/1.1 200 OK\r\n" else "HTTP/1.1 404 Not Found\r\n")
                append(if (redirect) "Content-Type: text/html; charset=utf-8\r\n" else "Content-Type: text/plain; charset=utf-8\r\n")
                append("Content-Length: ${body.size}\r\n")
                // The page shows once; a reload must not reach an app that stopped listening.
                append("Cache-Control: no-store\r\n")
                append("Connection: close\r\n\r\n")
            }
            Channels.newOutputStream(connection).apply {
                write(head.encodeToByteArray())
                write(body)
                flush()
            }
        }
        return target.takeIf { redirect }
    }

    override fun close() = server.close()

    companion object {
        /** Opens the listener on a free port of `127.0.0.1`. */
        fun open(): LoopbackRedirect = LoopbackRedirect(
            ServerSocketChannel.open().bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)),
        )

        /** Longest request line read; Google's redirect is a few hundred bytes. */
        private const val MAX_LINE = 8 * 1024

        /**
         * The target of a `GET` request line (`GET /?code=… HTTP/1.1` → `/?code=…`), or null for
         * anything else. Only the request line is read: the answer closes the connection, so the
         * headers after it don't matter.
         */
        private fun requestTarget(connection: SocketChannel): String? {
            val input = Channels.newInputStream(connection)
            val line = StringBuilder()
            while (line.length < MAX_LINE) {
                val byte = input.read()
                if (byte == -1) return null
                if (byte == '\n'.code) break
                if (byte != '\r'.code) line.append(byte.toChar())
            }
            val parts = line.split(' ')
            return if (parts.size == 3 && parts[0] == "GET") parts[1] else null
        }
    }
}
