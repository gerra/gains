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
    /** The Sign in with Apple key that revokes a deleted account's Apple tokens; null leaves them be. */
    val appleKey: AppleKey? = null,
    /** The Services ID of Apple's web flow (Android, desktop); also in [appleClientIds]. Null turns the web flow off. */
    val appleServicesId: String? = null,
    /** Where the internet reaches this server, for the redirect URL registered with Apple. */
    val publicUrl: String = DEFAULT_PUBLIC_URL,
) {
    /** A key from developer.apple.com → Keys with Sign in with Apple enabled. [privateKey] is the `.p8` file's contents. */
    data class AppleKey(val keyId: String, val teamId: String, val privateKey: String) {
        override fun toString() = "AppleKey(keyId=$keyId, teamId=$teamId)"
    }

    companion object {
        fun fromEnvironment(env: Map<String, String> = System.getenv(), workingDir: File = File(".")): Config {
            val values = HashMap<String, String>()
            val dotenv = File(workingDir, "secrets/.env")
            if (dotenv.isFile) values.putAll(parseDotenv(dotenv.readText()))
            values.putAll(env)
            val secret = values["JWT_SECRET"].orEmpty()
            require(secret.length >= 32) { "JWT_SECRET must be set to at least 32 characters (see secrets/README.md)" }
            // The key's newlines are written as \n, since a dotenv value is one line.
            val appleKeyParts = listOf("APPLE_KEY_ID", "APPLE_TEAM_ID", "APPLE_PRIVATE_KEY").map { values[it].orEmpty().trim().replace("\\n", "\n") }
            require(appleKeyParts.all { it.isEmpty() } || appleKeyParts.none { it.isEmpty() }) {
                "APPLE_KEY_ID, APPLE_TEAM_ID and APPLE_PRIVATE_KEY go together: set all three or none (see secrets/README.md)"
            }
            val servicesId = values["APPLE_SERVICES_ID"]?.trim()?.ifEmpty { null }
            val appleClientIds = values["APPLE_CLIENT_IDS"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
            return Config(
                jwtSecret = secret,
                googleClientIds = values["GOOGLE_CLIENT_IDS"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() },
                // The web flow's tokens are issued to the Services ID, so it is always an accepted audience.
                appleClientIds = (appleClientIds + listOfNotNull(servicesId)).distinct(),
                dataDir = File(values["GAINS_DATA_DIR"]?.ifBlank { null } ?: "data"),
                port = values["PORT"]?.toIntOrNull() ?: 5003,
                appleKey = appleKeyParts.takeIf { parts -> parts.none { it.isEmpty() } }?.let { (id, team, key) -> AppleKey(id, team, key) },
                appleServicesId = servicesId,
                publicUrl = values["GAINS_PUBLIC_URL"]?.trim()?.trimEnd('/')?.ifEmpty { null } ?: DEFAULT_PUBLIC_URL,
            )
        }

        const val DEFAULT_PUBLIC_URL = "https://api.gains.gerra.sh"

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
