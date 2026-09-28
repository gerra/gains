package app.gains.server

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** A plain-text message: the confirmation and reset mails of email accounts are the only ones the server sends. */
data class Mail(val to: String, val subject: String, val text: String)

class MailException(message: String) : Exception(message)

/**
 * Sends the mails email accounts need: the confirmation link at sign-up and the reset link
 * (docs/launch-plan.md, item 18). An interface, like [AppleTokens], so tests read the link out
 * of a list instead of an inbox, and so the provider is a deployment choice: every one of them
 * (Postmark, SES, the alias's host) offers SMTP submission, which [SmtpMailer] speaks.
 */
interface Mailer {
    /** False when the server has no `SMTP_*` secrets: email sign-in is then off and its routes answer 503. */
    val enabled: Boolean

    /** Hands [mail] to the provider. Throws on any failure; the caller decides what a lost mail means. */
    fun send(mail: Mail)
}

/** A server without the `SMTP_HOST`, `SMTP_PORT`, `SMTP_USER`, `SMTP_PASSWORD` and `MAIL_FROM` secrets. */
object NoMailer : Mailer {
    override val enabled = false
    override fun send(mail: Mail) = throw MailException("no mail provider configured")
}

/**
 * SMTP submission over the JDK's sockets, so the server needs no mail library for the three
 * commands it uses: one message at a time, `AUTH PLAIN`, and TLS from the first byte on port 465
 * ([Security.TLS]) or `STARTTLS` before anything else on 587 ([Security.STARTTLS]). The password
 * is never sent before the connection is encrypted; [Security.NONE] exists for the tests' fake
 * server on the loopback and is not reachable from the configuration. The body goes as base64
 * with a UTF-8 content type, so the text needs nothing of the server (no `8BITMIME`), and a
 * subject with non-ASCII in it is encoded the same way (RFC 2047).
 */
class SmtpMailer(
    private val host: String,
    private val port: Int,
    private val user: String?,
    private val password: String?,
    private val from: String,
    private val security: Security = if (port == 465) Security.TLS else Security.STARTTLS,
    private val timeoutMillis: Int = 15_000,
    private val sslFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory,
    private val clock: () -> Instant = Instant::now,
) : Mailer {
    enum class Security { TLS, STARTTLS, NONE }

    override val enabled = true

    override fun send(mail: Mail) {
        require(mail.to.none { it == '\r' || it == '\n' || it == '<' || it == '>' }) { "not an address: ${mail.to}" }
        var socket = Socket()
        socket.connect(InetSocketAddress(host, port), timeoutMillis)
        socket.soTimeout = timeoutMillis
        try {
            if (security == Security.TLS) socket = upgrade(socket)
            val session = Session(socket)
            session.expect(220)
            session.ehlo()
            if (security == Security.STARTTLS) {
                session.command("STARTTLS", 220)
                socket = upgrade(socket)
                session.reopen(socket)
                session.ehlo()
            }
            if (user != null && password != null) {
                val plain = Base64.getEncoder().encodeToString("\u0000$user\u0000$password".toByteArray(Charsets.UTF_8))
                session.command("AUTH PLAIN $plain", 235)
            }
            session.command("MAIL FROM:<$from>", 250)
            session.command("RCPT TO:<${mail.to}>", 250)
            session.command("DATA", 354)
            session.data(message(mail))
            session.command("QUIT", 221, tolerant = true)
        } finally {
            socket.close()
        }
    }

    private fun upgrade(socket: Socket): Socket {
        val tls = sslFactory.createSocket(socket, host, port, true) as SSLSocket
        tls.soTimeout = timeoutMillis
        tls.startHandshake()
        return tls
    }

    /** The message with its headers, the lines it is sent as (without the SMTP dot-stuffing, which [Session.data] adds). */
    internal fun message(mail: Mail): List<String> {
        val date = DateTimeFormatter.RFC_1123_DATE_TIME.format(clock().atOffset(ZoneOffset.UTC))
        val body = Base64.getMimeEncoder(76, "\r\n".toByteArray()).encodeToString(mail.text.toByteArray(Charsets.UTF_8))
        return listOf(
            "From: Gains <$from>",
            "To: <${mail.to}>",
            "Subject: ${encodeHeader(mail.subject)}",
            "Date: $date",
            "Message-ID: <${UUID.randomUUID()}@${from.substringAfter('@', "gains")}>",
            "MIME-Version: 1.0",
            "Content-Type: text/plain; charset=utf-8",
            "Content-Transfer-Encoding: base64",
            "",
        ) + body.split("\r\n")
    }

    /** One connection's reader and writer, and the reply parsing SMTP's multi-line answers need. */
    private class Session(socket: Socket) {
        private lateinit var reader: BufferedReader
        private lateinit var writer: OutputStreamWriter

        init {
            reopen(socket)
        }

        fun reopen(socket: Socket) {
            reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
            writer = OutputStreamWriter(socket.getOutputStream(), Charsets.ISO_8859_1)
        }

        fun ehlo() = command("EHLO gains", 250)

        fun command(line: String, expected: Int, tolerant: Boolean = false) {
            writer.write("$line\r\n")
            writer.flush()
            expect(expected, tolerant, line.substringBefore(' '))
        }

        fun data(lines: List<String>) {
            for (line in lines) writer.write((if (line.startsWith(".")) ".$line" else line) + "\r\n")
            writer.write(".\r\n")
            writer.flush()
            expect(250, what = "DATA")
        }

        /** Reads one reply, however many lines (`250-…` continues, `250 …` ends), and checks its code. */
        fun expect(code: Int, tolerant: Boolean = false, what: String = "greeting") {
            val lines = ArrayList<String>()
            while (true) {
                val line = reader.readLine() ?: throw MailException("SMTP $what: the server closed the connection")
                lines += line
                if (line.length < 4 || line[3] != '-') break
            }
            val got = lines.last().take(3).toIntOrNull()
            if (got != code && !tolerant) throw MailException("SMTP $what answered ${lines.last().take(120)}")
        }
    }

    companion object {
        /** RFC 2047 for a header that isn't plain ASCII; an ASCII one goes as it is. */
        fun encodeHeader(text: String): String =
            if (text.all { it.code in 32..126 }) text
            else "=?UTF-8?B?${Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))}?="
    }
}
