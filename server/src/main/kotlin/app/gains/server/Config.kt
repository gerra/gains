package app.gains.server

import java.io.File
import java.net.URI

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
    /** The mail submission account the confirmation and reset links of email accounts go out through; null turns email sign-in off. */
    val smtp: Smtp? = null,
    /** Where those links point: the site's pages, which post the token back to this server. */
    val siteUrl: String = DEFAULT_SITE_URL,
    /**
     * Origins a passkey ceremony may come from besides the site itself: the Android app's,
     * `android:apk-key-hash:<base64url SHA-256 of a signing certificate>`, one per key it is signed with.
     */
    val passkeyOrigins: List<String> = emptyList(),
) {
    /** The passkeys' relying party id: the site's host, whose `.well-known` files vouch for the apps. */
    val passkeyRpId: String get() = URI(siteUrl).host

    /** Every origin a passkey ceremony may come from: the site (iOS writes that one), then [passkeyOrigins]. */
    val allPasskeyOrigins: Set<String> get() = linkedSetOf("https://$passkeyRpId") + passkeyOrigins

    /** A key from developer.apple.com → Keys with Sign in with Apple enabled. [privateKey] is the `.p8` file's contents. */
    data class AppleKey(val keyId: String, val teamId: String, val privateKey: String) {
        override fun toString() = "AppleKey(keyId=$keyId, teamId=$teamId)"
    }

    /** An SMTP submission account: TLS from the start on port 465, `STARTTLS` on any other; [user] and [password] go together or not at all. */
    data class Smtp(val host: String, val port: Int, val user: String?, val password: String?, val from: String) {
        override fun toString() = "Smtp(host=$host, port=$port, user=$user, from=$from)"
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
            val smtpHost = values["SMTP_HOST"]?.trim()?.ifEmpty { null }
            val mailFrom = values["MAIL_FROM"]?.trim()?.ifEmpty { null }
            require((smtpHost == null) == (mailFrom == null)) { "SMTP_HOST and MAIL_FROM go together: set both or neither (see secrets/README.md)" }
            val smtpUser = values["SMTP_USER"]?.trim()?.ifEmpty { null }
            val smtpPassword = values["SMTP_PASSWORD"]?.ifEmpty { null }
            require((smtpUser == null) == (smtpPassword == null)) { "SMTP_USER and SMTP_PASSWORD go together: set both or neither (see secrets/README.md)" }
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
                smtp = smtpHost?.let { host ->
                    val port = values["SMTP_PORT"]?.trim()?.ifEmpty { null }?.let { it.toIntOrNull() ?: throw IllegalArgumentException("SMTP_PORT is not a port: $it") } ?: 587
                    Smtp(host, port, smtpUser, smtpPassword, mailFrom!!)
                },
                siteUrl = values["GAINS_SITE_URL"]?.trim()?.trimEnd('/')?.ifEmpty { null } ?: DEFAULT_SITE_URL,
                passkeyOrigins = values["PASSKEY_ORIGINS"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() },
            )
        }

        const val DEFAULT_PUBLIC_URL = "https://api.gains.gerra.sh"
        const val DEFAULT_SITE_URL = "https://gains.gerra.sh"

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
