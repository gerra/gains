package app.gains.server

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import org.slf4j.LoggerFactory
import java.io.File

/** Reads the configuration, opens the database and serves on 127.0.0.1 behind nginx (deploy/nginx). */
fun main() {
    val log = LoggerFactory.getLogger("app.gains.server")
    val config = Config.fromEnvironment()
    val store = Store.open(File(config.dataDir, "gains-server.db"))
    val services = Services(
        store = store,
        tokens = SessionTokens(config.jwtSecret),
        verifier = JwksIdentityVerifier(config.googleClientIds, config.appleClientIds),
        appleTokens = config.appleKey?.let { AppleTokenClient(it.keyId, it.teamId, it.privateKey) } ?: NoAppleTokens,
        refreshTokenCipher = RefreshTokenCipher(config.refreshTokenKey),
        appleWeb = config.appleServicesId?.let { AppleWebSignIn(it, "${config.publicUrl}/auth/apple/callback") },
        passwords = config.smtp?.let { PasswordSignIn(store, SmtpMailer(it.host, it.port, it.user, it.password, it.from), config.siteUrl) },
    )
    val sealed = sealStoredRefreshTokens(services)
    if (sealed > 0) log.info("refresh tokens: sealed {} stored in plain text", sealed)
    log.info(startLine(config, services))
    embeddedServer(CIO, port = config.port, host = "127.0.0.1") { gainsServer(services) }.start(wait = true)
}

/**
 * Seals the refresh tokens stored before `REFRESH_TOKEN_KEY` was set, once at start, and returns
 * how many. Nothing happens without a key, or once every row is sealed.
 */
fun sealStoredRefreshTokens(services: Services): Int =
    services.store.rewriteRefreshTokens(services.refreshTokenCipher::upgrade)

/**
 * The start-up line: what is switched on. Every optional secret says `on` or `off` here, so
 * `journalctl -u gains-server` answers "did the new secret take?" after `deploy_server.py secrets`.
 */
fun startLine(config: Config, services: Services): String =
    "gains-server on port ${config.port} (data ${config.dataDir.absolutePath}; " +
        "google ${onOff(config.googleClientIds.isNotEmpty())}; apple ${onOff(config.appleClientIds.isNotEmpty())}; " +
        "apple web ${onOff(services.appleWeb != null)}; apple revoke ${onOff(services.appleTokens.enabled)}; " +
        "refresh token encryption ${onOff(services.refreshTokenCipher.enabled)}; email ${onOff(services.passwords != null)})"

private fun onOff(on: Boolean) = if (on) "on" else "off"
