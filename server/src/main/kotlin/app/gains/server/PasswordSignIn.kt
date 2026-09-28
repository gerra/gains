package app.gains.server

import app.gains.sync.UserInfo
import io.ktor.http.HttpStatusCode
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * Email and password accounts (docs/sync.md, "Signing in"; docs/launch-plan.md, item 18). Four
 * steps and a reset, none of which says whether an address is known:
 *
 * 1. [signUp] stores the address and the hash of the password with no user yet, and mails a
 *    confirmation link. An address already confirmed gets a mail saying so, with a reset link,
 *    and the same 204 as everyone else.
 * 2. [verify] takes the link's token, once. Only now does the address get a user: through
 *    [Store.signIn] with the provider `password`, so a Google or Apple identity whose provider
 *    verified the same address becomes the same account, and an unverified one never does. This
 *    is why nothing is merged at sign-up: until the mail is answered, the address is a claim.
 * 3. [signIn] checks the password against the hash and issues our token for the user; an address
 *    still unconfirmed is told so, but only when the password is right.
 * 4. [requestReset] mails a reset link when the address has a credential, and answers the same
 *    either way; [reset] takes the token and sets the new password. A reset link proves the
 *    address as well as a confirmation link does, so it also confirms a sign-up that never was.
 *
 * Links go to the site ([siteUrl]: `/verify?token=…`, `/reset?token=…`), whose pages post the
 * token back here, because a mail scanner that follows every link would otherwise use up a
 * one-time token before the person could. Tokens are stored hashed, with an expiry, and deleted
 * when taken. Sign-ins and mails are limited per address and per IP, and an unknown address
 * costs the same time as a wrong password, so neither answers nor timing enumerate accounts.
 */
class PasswordSignIn(
    private val store: Store,
    private val mailer: Mailer,
    /** Where the links point: `https://gains.gerra.sh`, without a trailing slash. */
    private val siteUrl: String,
    private val hasher: PasswordHasher = PasswordHasher(),
    private val clock: () -> Instant = Instant::now,
    private val random: SecureRandom = SecureRandom(),
    /** Sign-in attempts, and mails, allowed per address; then 429 for a while. */
    private val perEmail: RateLimit = RateLimit(10, Duration.ofMinutes(15), clock),
    /** The same per calling IP, wider since a household or an office shares one. */
    private val perIp: RateLimit = RateLimit(40, Duration.ofMinutes(15), clock),
) {
    /** A hash of no password: verified against when the address is unknown, so that case takes as long as a wrong password. */
    private val decoy = hasher.hash(randomToken())

    /** Stores the sign-up and mails the confirmation link. Throws 400 for a bad address or password and 429 past the limits. */
    fun signUp(email: String, password: String, ip: String) {
        val address = normalize(email)
        checkPassword(password)
        limit(address, ip)
        val existing = store.credential(address)
        when {
            existing == null -> store.createCredential(address, hasher.hash(password), clock().toString())
            existing.userId == null -> store.setCredentialHash(address, hasher.hash(password))
            else -> {
                // Someone signing up again with a confirmed address: the owner, who forgot, or
                // someone else, who learns nothing. The mail goes to the owner either way.
                send(Mails.alreadyRegistered(address, link("reset", token(address, RESET, RESET_TTL))))
                return
            }
        }
        send(Mails.confirm(address, link("verify", token(address, VERIFY, VERIFY_TTL))))
        log.info("password sign-up: confirmation mailed")
    }

    /** Confirms the address the token was mailed to and gives it its user. Throws 400 for a token that is unknown, used or expired. */
    fun verify(token: String): UserInfo {
        val address = store.takeEmailToken(hash(token), VERIFY, clock().toEpochMilli())
            ?: throw HttpError(HttpStatusCode.BadRequest, "this link is no longer valid")
        val user = store.confirmCredential(address) ?: throw HttpError(HttpStatusCode.BadRequest, "this link is no longer valid")
        log.info("password sign-up confirmed: user {}", user.id)
        return user
    }

    /** The user behind the address and password, or 401; 403 when the password is right but the address is not confirmed yet. */
    fun signIn(email: String, password: String, ip: String): UserInfo {
        val address = normalize(email)
        limit(address, ip)
        val credential = store.credential(address)
        val matches = hasher.verify(password, credential?.hash ?: decoy)
        if (credential == null || !matches) throw HttpError(HttpStatusCode.Unauthorized, "wrong email or password")
        val userId = credential.userId ?: throw HttpError(HttpStatusCode.Forbidden, "email not confirmed")
        return store.user(userId) ?: throw HttpError(HttpStatusCode.Unauthorized, "wrong email or password")
    }

    /** Mails a reset link when the address has a credential; answers alike when it has none. */
    fun requestReset(email: String, ip: String) {
        val address = normalize(email)
        limit(address, ip)
        if (store.credential(address) == null) return
        send(Mails.reset(address, link("reset", token(address, RESET, RESET_TTL))))
        log.info("password reset mailed")
    }

    /** Sets the password the reset link's address, confirming it if it never was. Throws 400 for a bad token or password. */
    fun reset(token: String, password: String): UserInfo {
        checkPassword(password)
        val address = store.takeEmailToken(hash(token), RESET, clock().toEpochMilli())
            ?: throw HttpError(HttpStatusCode.BadRequest, "this link is no longer valid")
        store.setCredentialHash(address, hasher.hash(password))
        val user = store.confirmCredential(address) ?: throw HttpError(HttpStatusCode.BadRequest, "this link is no longer valid")
        log.info("password reset: user {}", user.id)
        return user
    }

    private fun normalize(email: String): String {
        val address = email.trim().lowercase()
        if (!isEmailAddress(address)) throw HttpError(HttpStatusCode.BadRequest, "not an email address")
        return address
    }

    private fun checkPassword(password: String) {
        if (password.length < MIN_PASSWORD) throw HttpError(HttpStatusCode.BadRequest, "the password needs at least $MIN_PASSWORD characters")
        if (password.length > MAX_PASSWORD) throw HttpError(HttpStatusCode.BadRequest, "the password is too long")
    }

    private fun limit(address: String, ip: String) {
        if (!perIp.allow(ip) || !perEmail.allow(address)) throw HttpError(HttpStatusCode.TooManyRequests, "too many tries; wait a while")
    }

    /** A fresh token for [address], stored hashed with its expiry; the token itself only ever goes into the mail. */
    private fun token(address: String, purpose: String, ttl: Duration): String {
        val token = randomToken()
        store.putEmailToken(hash(token), address, purpose, (clock() + ttl).toEpochMilli(), now = clock().toEpochMilli())
        return token
    }

    private fun link(page: String, token: String) = "$siteUrl/$page?token=$token"

    private fun send(mail: Mail) {
        if (!mailer.enabled) throw HttpError(HttpStatusCode.ServiceUnavailable, "email sign-in is not configured")
        try {
            mailer.send(mail)
        } catch (e: Exception) {
            log.warn("mail to a sign-up failed: {}", e.message)
            throw HttpError(HttpStatusCode.ServiceUnavailable, "the confirmation mail could not be sent; try again later")
        }
    }

    private fun randomToken(): String = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    companion object {
        const val VERIFY = "verify"
        const val RESET = "reset"
        const val MIN_PASSWORD = 8
        const val MAX_PASSWORD = 128

        /** Long enough to read the mail tomorrow; a sign-up can always be repeated. */
        val VERIFY_TTL: Duration = Duration.ofHours(24)

        /** A reset link is asked for and used in one sitting. */
        val RESET_TTL: Duration = Duration.ofHours(1)

        private val log = LoggerFactory.getLogger(PasswordSignIn::class.java)

        fun hash(token: String): String =
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

/** The three mails, plain text. English only: the site the links open is, too. */
object Mails {
    fun confirm(to: String, link: String) = Mail(
        to,
        "Confirm your email for Gains",
        """
        |Hi,
        |
        |Confirm this address to finish creating your Gains account:
        |
        |$link
        |
        |The link works once and for 24 hours. If you didn't sign up for Gains, ignore this mail: nothing happens without it.
        |
        |Gains · https://gains.gerra.sh
        """.trimMargin(),
    )

    fun reset(to: String, link: String) = Mail(
        to,
        "Reset your Gains password",
        """
        |Hi,
        |
        |Set a new password for your Gains account here:
        |
        |$link
        |
        |The link works once and for an hour. If you didn't ask for this, ignore this mail: your password stays as it is.
        |
        |Gains · https://gains.gerra.sh
        """.trimMargin(),
    )

    fun alreadyRegistered(to: String, link: String) = Mail(
        to,
        "You already have a Gains account",
        """
        |Hi,
        |
        |Someone, probably you, tried to create a Gains account with this address, which already has one. Sign in with your password, or set a new one here:
        |
        |$link
        |
        |The link works once and for an hour. If this wasn't you, ignore this mail: nothing changes without it.
        |
        |Gains · https://gains.gerra.sh
        """.trimMargin(),
    )
}
