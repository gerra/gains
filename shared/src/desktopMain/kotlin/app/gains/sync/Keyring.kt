package app.gains.sync

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** What a command run left behind. */
class CommandResult(val exitCode: Int, val stdout: String, val stderr: String)

/**
 * Runs a command with an optional standard input and waits for it. The keyrings take one so the
 * tests can stand in for the OS tools; the app uses [runCommand].
 */
typealias CommandRunner = (command: List<String>, stdin: String?) -> CommandResult

/**
 * The OS's own secret store, as far as the desktop needs it: one secret, the sync's bearer token,
 * read, written and deleted. Each OS has a command-line tool for its store, and calling that is a
 * few lines where a keyring library would be a dependency plus native code to ship: `security` on
 * macOS, `secret-tool` on Linux and PowerShell's DPAPI on Windows. The token never goes on a
 * command line, where `ps` could see it; it travels on standard input or output only.
 *
 * [detect] picks the keyring for this machine, or none, and then
 * [app.gains.sync.KeyringTokenVault] wraps it; a machine without one keeps the token in the
 * database ([SqliteTokenVault]). See docs/sync.md.
 */
interface Keyring {
    /** The token, or null when the store holds none. Throws when the store can't answer. */
    fun read(): String?

    fun write(token: String)

    /** Removes the token; a store that never had it is fine. */
    fun delete()

    companion object {
        /** How the token's item is found: the same service and account as the iOS Keychain item. */
        const val SERVICE = "app.gains.sync"
        const val ACCOUNT = "token"

        /**
         * The keyring this machine has, or null when it has none and the token stays in the
         * database: a headless Linux box, or one without `secret-tool` or a Secret Service on its
         * session bus. The Linux probe looks up an item that never exists, so it tells a reachable
         * service from none without touching the token's item, which could ask to unlock the
         * keyring at start.
         */
        fun detect(
            osName: String = System.getProperty("os.name") ?: "",
            onPath: (String) -> Boolean = ::isOnPath,
            run: CommandRunner = ::runCommand,
            home: File = File(System.getProperty("user.home")),
        ): Keyring? = when {
            osName.startsWith("Mac") -> if (onPath("security")) MacKeychain(run) else null
            osName.startsWith("Windows") -> if (onPath("powershell")) WindowsDpapi(run, File(home, ".gains/token.dpapi")) else null
            osName.startsWith("Linux") -> {
                if (!onPath("secret-tool")) null else {
                    val probe = run(SecretService.lookup("probe"), null)
                    if (probe.exitCode == 0 || (probe.exitCode == 1 && probe.stderr.isBlank())) SecretService(run) else null
                }
            }
            else -> null
        }

        /**
         * Refuses a token that would need quoting: a JWT is Base64 and dots, so a space, a quote
         * or a line break in one is not a token, and it would break the line `security -i` parses.
         */
        internal fun checkToken(token: String) {
            require(token.isNotBlank() && token.none { it.isWhitespace() || it == '"' || it == '\'' || it == '\\' }) { "Not a token" }
        }
    }
}

/**
 * The login keychain on macOS, through `security`. The item is a generic password with the same
 * service and account as the iOS one. It is written in `security`'s interactive mode, which reads
 * commands from standard input, so the token stays off the command line. The item's access list
 * then holds `security` itself, so later reads don't prompt.
 */
class MacKeychain(private val run: CommandRunner) : Keyring {
    override fun read(): String? {
        val result = run(listOf("security", "find-generic-password", "-s", Keyring.SERVICE, "-a", Keyring.ACCOUNT, "-w"), null)
        return when (result.exitCode) {
            0 -> result.stdout.trimEnd('\n', '\r')
            NOT_FOUND -> null
            else -> error("Keychain read failed: ${result.exitCode} ${result.stderr.trim()}")
        }
    }

    override fun write(token: String) {
        Keyring.checkToken(token)
        // -U replaces the item when it is there; without it a second sign-in would fail.
        val line = "add-generic-password -U -s ${Keyring.SERVICE} -a ${Keyring.ACCOUNT} -l Gains -w $token\n"
        val result = run(listOf("security", "-i"), line)
        // The interactive mode's exit code is not the command's, so the write is read back.
        check(result.exitCode == 0 && result.stderr.isBlank() && read() == token) { "Keychain write failed: ${result.stderr.trim()}" }
    }

    override fun delete() {
        val result = run(listOf("security", "delete-generic-password", "-s", Keyring.SERVICE, "-a", Keyring.ACCOUNT), null)
        check(result.exitCode == 0 || result.exitCode == NOT_FOUND) { "Keychain delete failed: ${result.exitCode} ${result.stderr.trim()}" }
    }

    private companion object {
        /** `security`'s exit code for `errSecItemNotFound`. */
        const val NOT_FOUND = 44
    }
}

/**
 * The Secret Service on Linux (GNOME Keyring, KWallet's bridge), through `secret-tool`, which
 * takes the secret on standard input. `lookup` exits 1 both when there is no such item and when
 * there is no service to ask; only the second says why on standard error.
 */
class SecretService(private val run: CommandRunner) : Keyring {
    override fun read(): String? {
        val result = run(lookup(Keyring.ACCOUNT), null)
        return when {
            result.exitCode == 0 -> result.stdout.trimEnd('\n', '\r')
            result.exitCode == 1 && result.stderr.isBlank() -> null
            else -> error("Keyring read failed: ${result.exitCode} ${result.stderr.trim()}")
        }
    }

    override fun write(token: String) {
        Keyring.checkToken(token)
        val result = run(listOf("secret-tool", "store", "--label=Gains", "service", Keyring.SERVICE, "account", Keyring.ACCOUNT), token)
        check(result.exitCode == 0) { "Keyring write failed: ${result.exitCode} ${result.stderr.trim()}" }
    }

    override fun delete() {
        val result = run(listOf("secret-tool", "clear", "service", Keyring.SERVICE, "account", Keyring.ACCOUNT), null)
        check(result.exitCode == 0) { "Keyring delete failed: ${result.exitCode} ${result.stderr.trim()}" }
    }

    companion object {
        /** The lookup for the item with [account] under the sync's service. */
        fun lookup(account: String): List<String> = listOf("secret-tool", "lookup", "service", Keyring.SERVICE, "account", account)
    }
}

/**
 * DPAPI on Windows, through Windows PowerShell, which every Windows has: the token is encrypted
 * for the signed-in Windows account (`ProtectedData`, `CurrentUser`) and the ciphertext is kept
 * in [file], next to the database. Only that account on that machine can open it, so a copied
 * profile or a database file carried elsewhere holds nothing. A ciphertext that won't open (a
 * file from another account) is dropped and read as no token, like Android's Keystore vault
 * does, and the person signs in again.
 */
class WindowsDpapi(private val run: CommandRunner, private val file: File) : Keyring {
    override fun read(): String? {
        if (!file.isFile) return null
        val result = run(powershell(UNPROTECT), file.readText())
        check(result.exitCode == 0) { "DPAPI read failed: ${result.exitCode} ${result.stderr.trim()}" }
        val token = result.stdout.trim()
        if (token.isEmpty()) file.delete()
        return token.ifEmpty { null }
    }

    override fun write(token: String) {
        Keyring.checkToken(token)
        val result = run(powershell(PROTECT), token)
        val ciphertext = result.stdout.trim()
        check(result.exitCode == 0 && ciphertext.isNotEmpty()) { "DPAPI write failed: ${result.exitCode} ${result.stderr.trim()}" }
        file.parentFile?.mkdirs()
        file.writeText(ciphertext)
    }

    override fun delete() {
        check(!file.isFile || file.delete()) { "DPAPI delete failed: $file" }
    }

    private companion object {
        fun powershell(script: String) = listOf("powershell", "-NoProfile", "-NonInteractive", "-Command", script)

        const val PRELUDE = "\$ErrorActionPreference = 'Stop'; Add-Type -AssemblyName System.Security; " +
            "\$in = [Console]::In.ReadToEnd().Trim(); "

        /** Standard input: the token. Standard output: its DPAPI ciphertext, Base64. */
        const val PROTECT = PRELUDE +
            "[Convert]::ToBase64String([Security.Cryptography.ProtectedData]::Protect([Text.Encoding]::UTF8.GetBytes(\$in), \$null, 'CurrentUser'))"

        /** Standard input: the ciphertext. Standard output: the token, or nothing when it won't open. */
        const val UNPROTECT = PRELUDE +
            "try { [Text.Encoding]::UTF8.GetString([Security.Cryptography.ProtectedData]::Unprotect([Convert]::FromBase64String(\$in), \$null, 'CurrentUser')) } " +
            "catch [Security.Cryptography.CryptographicException] { '' }"
    }
}

/** Whether [name] (or `name.exe`) is an executable in one of `PATH`'s directories. */
internal fun isOnPath(name: String): Boolean =
    (System.getenv("PATH") ?: "").split(File.pathSeparator).filter { it.isNotEmpty() }
        .any { dir -> File(dir, name).canExecute() || File(dir, "$name.exe").canExecute() }

/**
 * Runs [command], hands it [stdin] and collects what it prints. The outputs are a token or an
 * error line, far below a pipe's buffer, so reading them one after the other can't block. A tool
 * that hangs (a keyring prompt nobody answers) is killed after a while rather than holding the
 * sync forever.
 */
internal fun runCommand(command: List<String>, stdin: String?): CommandResult {
    val process = ProcessBuilder(command).start()
    try {
        process.outputStream.bufferedWriter().use { if (stdin != null) it.write(stdin) }
    } catch (e: IOException) {
        // The tool quit before reading its input; its exit code and standard error say why.
    }
    val stdout = process.inputStream.bufferedReader().readText()
    val stderr = process.errorStream.bufferedReader().readText()
    if (!process.waitFor(2, TimeUnit.MINUTES)) {
        process.destroyForcibly()
        error("${command.first()} did not finish")
    }
    return CommandResult(process.exitValue(), stdout, stderr)
}
