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
    )
    log.info(
        "gains-server on port {} (data {}; google {}; apple {}; apple revoke {})",
        config.port, config.dataDir.absolutePath,
        if (config.googleClientIds.isEmpty()) "off" else "on", if (config.appleClientIds.isEmpty()) "off" else "on",
        if (services.appleTokens.enabled) "on" else "off",
    )
    embeddedServer(CIO, port = config.port, host = "127.0.0.1") { gainsServer(services) }.start(wait = true)
}
