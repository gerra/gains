package app.gains.server

import java.io.File

/**
 * What the server is told from outside. Read from the environment, with `secrets/.env` under
 * the working directory loaded first when it exists (the systemd unit has no EnvironmentFile
 * on purpose: systemd and dotenv quote differently, so the file is read by one parser only).
 * `secrets/README.md` says what each variable is and how to get it.
 */
data class Config(
    /** Signs the tokens this server issues. Rotating it signs every device out. */
    val jwtSecret: String,
    /** Google OAuth client ids whose ID tokens are accepted: the web, Android and iOS clients. Empty disables Google. */
    val googleClientIds: List<String>,
    /** Sign in with Apple audiences: the iOS bundle id and the Services ID for the web flow. Empty disables Apple. */
    val appleClientIds: List<String>,
    /** Where the SQLite file lives. */
    val dataDir: File,
    val port: Int,
) {
    companion object {
        fun fromEnvironment(env: Map<String, String> = System.getenv(), workingDir: File = File(".")): Config {
            val values = HashMap<String, String>()
            val dotenv = File(workingDir, "secrets/.env")
            if (dotenv.isFile) values.putAll(parseDotenv(dotenv.readText()))
            values.putAll(env)
            val secret = values["JWT_SECRET"].orEmpty()
            require(secret.length >= 32) { "JWT_SECRET must be set to at least 32 characters (see secrets/README.md)" }
            return Config(
                jwtSecret = secret,
                googleClientIds = values["GOOGLE_CLIENT_IDS"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() },
                appleClientIds = values["APPLE_CLIENT_IDS"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() },
                dataDir = File(values["GAINS_DATA_DIR"]?.ifBlank { null } ?: "data"),
                port = values["PORT"]?.toIntOrNull() ?: 5003,
            )
        }

        /** `KEY=value` lines; blank lines and `#` comments skipped; surrounding single or double quotes dropped. */
        fun parseDotenv(text: String): Map<String, String> = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && '=' in it }
            .associate { line ->
                val key = line.substringBefore('=').trim().removePrefix("export ").trim()
                var value = line.substringAfter('=').trim()
                if (value.length >= 2 && (value.first() == '"' && value.last() == '"' || value.first() == '\'' && value.last() == '\'')) {
                    value = value.substring(1, value.length - 1)
                }
                key to value
            }
    }
}
