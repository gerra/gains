package app.gains.server

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.time.Instant
import java.util.Base64
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The SMTP dialogue against a fake server on the loopback, in the clear ([SmtpMailer.Security.NONE],
 * which the configuration never picks): what is said, in which order, and that the body arrives
 * as it was written.
 */
class SmtpMailerTest {
    /** A one-connection SMTP server that answers like a submission host and records what it was told. */
    private class FakeSmtp(private val rejectRecipient: Boolean = false) {
        private val server = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val port = server.localPort
        val commands = mutableListOf<String>()
        val data = mutableListOf<String>()
        val thread = thread(start = true) {
            server.accept().use { socket ->
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
                val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.ISO_8859_1)
                fun say(line: String) { writer.write(line + "\r\n"); writer.flush() }
                say("220 fake.example ESMTP")
                while (true) {
                    val line = reader.readLine() ?: break
                    commands += line
                    when {
                        line.startsWith("EHLO") -> { say("250-fake.example"); say("250-AUTH PLAIN LOGIN"); say("250 8BITMIME") }
                        line.startsWith("AUTH PLAIN") -> say("235 2.7.0 Authentication successful")
                        line.startsWith("MAIL FROM") -> say("250 OK")
                        line.startsWith("RCPT TO") -> say(if (rejectRecipient) "550 5.1.1 No such user" else "250 OK")
                        line == "DATA" -> {
                            say("354 End data with <CR><LF>.<CR><LF>")
                            while (true) {
                                val l = reader.readLine() ?: break
                                if (l == ".") break
                                data += if (l.startsWith("..")) l.substring(1) else l
                            }
                            say("250 OK queued")
                        }
                        line == "QUIT" -> { say("221 Bye"); break }
                        else -> say("500 what")
                    }
                }
            }
            server.close()
        }
    }

    private fun mailer(port: Int) = SmtpMailer(
        "127.0.0.1", port, user = "gains", password = "s3cret", from = "gains@gerra.sh",
        security = SmtpMailer.Security.NONE, timeoutMillis = 5_000, clock = { Instant.parse("2026-09-28T10:00:00Z") },
    )

    @Test
    fun theMessageGoesOutAuthenticatedWithItsTextIntact() {
        val smtp = FakeSmtp()
        val text = "Hi,\n\nConfirm here: https://gains.gerra.sh/verify?token=abc\n.\nA line that starts with a dot, and an ümlaut.\n"
        mailer(smtp.port).send(Mail("ada@example.com", "Confirm your email for Gains", text))
        smtp.thread.join(5_000)

        val plain = Base64.getEncoder().encodeToString("\u0000gains\u0000s3cret".toByteArray())
        assertEquals(listOf("EHLO gains", "AUTH PLAIN $plain", "MAIL FROM:<gains@gerra.sh>", "RCPT TO:<ada@example.com>", "DATA", "QUIT"), smtp.commands)
        val headers = smtp.data.takeWhile { it.isNotEmpty() }
        assertTrue("From: Gains <gains@gerra.sh>" in headers, headers.toString())
        assertTrue("To: <ada@example.com>" in headers)
        assertTrue("Subject: Confirm your email for Gains" in headers)
        assertTrue("Date: Mon, 28 Sep 2026 10:00:00 GMT" in headers)
        assertTrue("Content-Type: text/plain; charset=utf-8" in headers)
        val body = smtp.data.dropWhile { it.isNotEmpty() }.drop(1).joinToString("")
        assertEquals(text, String(Base64.getDecoder().decode(body), Charsets.UTF_8))
    }

    @Test
    fun aRefusedRecipientIsAnError() {
        val smtp = FakeSmtp(rejectRecipient = true)
        val error = assertFailsWith<MailException> { mailer(smtp.port).send(Mail("nobody@example.com", "x", "y")) }
        assertTrue("550" in error.message!!, error.message)
        smtp.thread.join(5_000)
    }

    @Test
    fun aSubjectBeyondAsciiIsEncoded() {
        assertEquals("Plain", SmtpMailer.encodeHeader("Plain"))
        assertEquals("=?UTF-8?B?0J/RgNC40LLQtdGC?=", SmtpMailer.encodeHeader("Привет"))
    }
}
