package app.gains.sync

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * The OS tools each keyring drives, answered by a script instead of a process: what is run, what
 * goes on standard input (never the command line), and how each tool's exit codes are read.
 */
class KeyringTest {
    /** Records every run and answers each from [answers], in order, or with "not found" once they run out. */
    private class Tools(vararg answers: CommandResult) : CommandRunner {
        private val answers = answers.toMutableList()
        val runs = mutableListOf<Pair<List<String>, String?>>()
        override fun invoke(command: List<String>, stdin: String?): CommandResult {
            runs += command to stdin
            return if (answers.isEmpty()) CommandResult(1, "", "") else answers.removeAt(0)
        }
    }

    private fun ok(stdout: String = "") = CommandResult(0, stdout, "")

    // --- macOS -------------------------------------------------------------------------------

    @Test
    fun macReadsTheItemAndTellsMissingFromBroken() {
        assertEquals("tok.en", MacKeychain(Tools(ok("tok.en\n"))).read())
        assertNull(MacKeychain(Tools(CommandResult(44, "", "The specified item could not be found in the keychain."))).read())
        assertFailsWith<IllegalStateException> { MacKeychain(Tools(CommandResult(1, "", "keychain locked"))).read() }
    }

    @Test
    fun macWritesThroughInteractiveModeAndReadsBack() {
        val tools = Tools(ok(), ok("tok.en\n"))
        MacKeychain(tools).write("tok.en")
        val (command, stdin) = tools.runs[0]
        assertEquals(listOf("security", "-i"), command, "the token is not on the command line")
        assertEquals("add-generic-password -U -s app.gains.sync -a token -l Gains -w tok.en\n", stdin)
        assertEquals(listOf("security", "find-generic-password", "-s", "app.gains.sync", "-a", "token", "-w"), tools.runs[1].first)
    }

    @Test
    fun macRefusesAWriteThatDidNotTake() {
        assertFailsWith<IllegalStateException> { MacKeychain(Tools(ok(), CommandResult(44, "", ""))).write("tok.en") }
        assertFailsWith<IllegalStateException> { MacKeychain(Tools(CommandResult(0, "", "security: SecKeychainItemCreateFromContent: User interaction is not allowed."))).write("tok.en") }
        assertFailsWith<IllegalArgumentException> { MacKeychain(Tools()).write("not a token") }
    }

    @Test
    fun macDeleteToleratesAMissingItemOnly() {
        MacKeychain(Tools(ok())).delete()
        MacKeychain(Tools(CommandResult(44, "", ""))).delete()
        assertFailsWith<IllegalStateException> { MacKeychain(Tools(CommandResult(1, "", "boom"))).delete() }
    }

    // --- Linux -------------------------------------------------------------------------------

    @Test
    fun secretServiceReadsAndTellsMissingFromNoService() {
        assertEquals("tok.en", SecretService(Tools(ok("tok.en"))).read())
        assertNull(SecretService(Tools(CommandResult(1, "", ""))).read(), "exit 1 and silence: no such item")
        assertFailsWith<IllegalStateException> {
            SecretService(Tools(CommandResult(1, "", "secret-tool: Cannot autolaunch D-Bus without X11 \$DISPLAY"))).read()
        }
    }

    @Test
    fun secretServiceWritesOnStandardInput() {
        val tools = Tools(ok())
        SecretService(tools).write("tok.en")
        val (command, stdin) = tools.runs.single()
        assertEquals(listOf("secret-tool", "store", "--label=Gains", "service", "app.gains.sync", "account", "token"), command)
        assertEquals("tok.en", stdin)
        assertFailsWith<IllegalStateException> { SecretService(Tools(CommandResult(1, "", "no service"))).write("tok.en") }
    }

    @Test
    fun secretServiceClears() {
        val tools = Tools(ok())
        SecretService(tools).delete()
        assertEquals(listOf("secret-tool", "clear", "service", "app.gains.sync", "account", "token"), tools.runs.single().first)
        assertFailsWith<IllegalStateException> { SecretService(Tools(CommandResult(1, "", "no service"))).delete() }
    }

    // --- Windows -----------------------------------------------------------------------------

    private fun tempFile() = File.createTempFile("gains-token", ".dpapi").apply { delete(); deleteOnExit() }

    @Test
    fun dpapiKeepsTheCiphertextInTheFileAndTheTokenOnThePipes() {
        val file = tempFile()
        val tools = Tools(ok("Y2lwaGVy\r\n"), ok("tok.en\r\n"))
        val keyring = WindowsDpapi(tools, file)

        keyring.write("tok.en")
        assertEquals("Y2lwaGVy", file.readText())
        val (writeCommand, writeStdin) = tools.runs[0]
        assertEquals("powershell", writeCommand.first())
        assertTrue(writeCommand.none { it.contains("tok.en") }, "the token is not on the command line")
        assertEquals("tok.en", writeStdin)

        assertEquals("tok.en", keyring.read())
        assertEquals("Y2lwaGVy", tools.runs[1].second, "the ciphertext goes in on standard input")

        keyring.delete()
        assertFalse(file.exists())
        keyring.delete()
    }

    @Test
    fun dpapiWithoutAFileReadsNothingWithoutRunningAnything() {
        val tools = Tools()
        assertNull(WindowsDpapi(tools, tempFile()).read())
        assertTrue(tools.runs.isEmpty())
    }

    @Test
    fun dpapiDropsACiphertextThatWontOpen() {
        val file = tempFile().apply { writeText("Y2lwaGVy") }
        assertNull(WindowsDpapi(Tools(ok("")), file).read(), "another account's file is no token")
        assertFalse(file.exists())
        file.writeText("Y2lwaGVy")
        assertFailsWith<IllegalStateException> { WindowsDpapi(Tools(CommandResult(1, "", "powershell broke")), file).read() }
        assertTrue(file.exists(), "a tool that fails is not a missing token")
    }

    @Test
    fun dpapiRefusesAnEmptyCiphertext() {
        val file = tempFile()
        assertFailsWith<IllegalStateException> { WindowsDpapi(Tools(ok("")), file).write("tok.en") }
        assertFalse(file.exists())
    }

    // --- detection ---------------------------------------------------------------------------

    @Test
    fun eachOsGetsItsKeyringWhenTheToolIsThere() {
        val home = File(System.getProperty("java.io.tmpdir"))
        assertIs<MacKeychain>(Keyring.detect("Mac OS X", { it == "security" }, Tools(), home))
        assertNull(Keyring.detect("Mac OS X", { false }, Tools(), home))
        assertIs<WindowsDpapi>(Keyring.detect("Windows 11", { it == "powershell" }, Tools(), home))
        assertNull(Keyring.detect("Windows 11", { false }, Tools(), home))
        assertNull(Keyring.detect("FreeBSD", { true }, Tools(), home))
    }

    @Test
    fun linuxNeedsSecretToolAndAServiceThatAnswers() {
        val home = File(System.getProperty("java.io.tmpdir"))
        assertNull(Keyring.detect("Linux", { false }, Tools(), home), "no secret-tool")

        val reachable = Tools(CommandResult(1, "", ""))
        assertIs<SecretService>(Keyring.detect("Linux", { it == "secret-tool" }, reachable, home))
        assertEquals(listOf("secret-tool", "lookup", "service", "app.gains.sync", "account", "probe"), reachable.runs.single().first, "the probe never touches the token's item")

        assertIs<SecretService>(Keyring.detect("Linux", { true }, Tools(ok("x")), home))
        val headless = Tools(CommandResult(1, "", "secret-tool: Cannot autolaunch D-Bus without X11 \$DISPLAY"))
        assertNull(Keyring.detect("Linux", { true }, headless, home), "no service: the token stays in the database")
    }
}
