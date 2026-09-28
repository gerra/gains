package app.gains.server

import app.gains.sync.PasskeyChallenge
import app.gains.sync.SyncJson
import app.gains.sync.UserInfo
import com.yubico.webauthn.AssertionRequest
import com.yubico.webauthn.CredentialRepository
import com.yubico.webauthn.FinishAssertionOptions
import com.yubico.webauthn.FinishRegistrationOptions
import com.yubico.webauthn.RegisteredCredential
import com.yubico.webauthn.RelyingParty
import com.yubico.webauthn.StartAssertionOptions
import com.yubico.webauthn.StartRegistrationOptions
import com.yubico.webauthn.data.AuthenticatorSelectionCriteria
import com.yubico.webauthn.data.PublicKeyCredential
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor
import com.yubico.webauthn.data.RelyingPartyIdentity
import com.yubico.webauthn.data.ResidentKeyRequirement
import com.yubico.webauthn.data.UserIdentity
import com.yubico.webauthn.data.UserVerificationRequirement
import com.yubico.webauthn.exception.AssertionFailedException
import com.yubico.webauthn.exception.RegistrationFailedException
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory
import java.io.IOException
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Optional
import com.yubico.webauthn.data.ByteArray as Bytes

/**
 * Passkeys (docs/sync.md, "Signing in"; docs/launch-plan.md, item 19), with Yubico's relying
 * party doing the WebAuthn checks: the challenge, the origin, the relying party id's hash, the
 * user-verification flag, the signature and the counter.
 *
 * A passkey is added from Settings to an account that already exists, never on its own, so every
 * account keeps the provider or email it can be recovered through:
 *
 * 1. [startRegistration] (signed in) makes creation options for the user, with the user handle
 *    their earlier passkeys have (or a new random one) and those passkeys excluded, so the same
 *    device isn't asked to make a second; [finishRegistration] checks what the device made and
 *    stores its public key.
 * 2. [startSignIn] (anyone) makes request options that name no account, so the device offers any
 *    passkey it has for [rpId]; [finishSignIn] finds the user through the passkey's user handle
 *    and checks the signature against the stored key.
 *
 * Each start is remembered under a random id until its finish takes it, once, within [TTL]. Both
 * maps live in memory like [AppleWebSignIn]'s, capped at [maxPending], and anonymous starts are
 * limited per IP, so a loop can't fill them.
 */
class Passkeys(
    private val store: Store,
    /** The relying party id: the domain every passkey is bound to, `gains.gerra.sh`, whose site serves the apps' association files. */
    val rpId: String,
    /**
     * Where a ceremony may come from, as the platform writes it into the client data:
     * `https://gains.gerra.sh` for iOS (and a browser), `android:apk-key-hash:<hash>` for each key
     * the Android app is signed with.
     */
    origins: Set<String>,
    private val clock: () -> Instant = Instant::now,
    private val maxPending: Int = 10_000,
    /** Anonymous sign-in starts allowed per calling IP. */
    private val perIp: RateLimit = RateLimit(30, Duration.ofMinutes(15), clock),
) {
    private class Pending<T>(val value: T, val expiresAt: Instant)

    private class Registration(val userId: Long, val options: PublicKeyCredentialCreationOptions)

    private val random = SecureRandom()
    private val registrations = HashMap<String, Pending<Registration>>()
    private val signIns = HashMap<String, Pending<AssertionRequest>>()

    private val relyingParty = RelyingParty.builder()
        .identity(RelyingPartyIdentity.builder().id(rpId).name("Gains").build())
        .credentialRepository(StoreCredentials(store))
        .origins(origins)
        .build()

    /** Creation options for a new passkey on [user]'s account. Throws 503 when too many ceremonies are in flight. */
    fun startRegistration(user: UserInfo): PasskeyChallenge {
        val handle = store.passkeyHandle(user.id)?.let(Bytes::fromBase64Url) ?: Bytes(ByteArray(32).also(random::nextBytes))
        // What the device's passkey list shows: the address, since that is what tells two accounts apart.
        val label = user.email ?: user.name ?: "Gains"
        val options = relyingParty.startRegistration(
            StartRegistrationOptions.builder()
                .user(UserIdentity.builder().name(label).displayName(user.name ?: label).id(handle).build())
                .authenticatorSelection(
                    AuthenticatorSelectionCriteria.builder()
                        // Discoverable, so signing in needs no account name first.
                        .residentKey(ResidentKeyRequirement.REQUIRED)
                        .userVerification(UserVerificationRequirement.REQUIRED)
                        .build()
                )
                .timeout(TTL.toMillis())
                .build()
        ).toBuilder()
            .excludeCredentials(store.passkeyIds(user.id).map { PublicKeyCredentialDescriptor.builder().id(Bytes.fromBase64Url(it)).build() }.toSet())
            .build()
        val id = remember(registrations, Registration(user.id, options))
        return PasskeyChallenge(id, innerOptions(options.toCredentialsCreateJson()))
    }

    /**
     * Checks the device's answer to [id] and keeps the passkey on [userId]'s account. Throws 400
     * for a ceremony that is unknown, expired or someone else's, or an answer that fails the
     * checks, and 409 for a passkey already stored.
     */
    fun finishRegistration(userId: Long, id: String, credentialJson: String) {
        val registration = take(registrations, id)?.takeIf { it.userId == userId }
            ?: throw HttpError(HttpStatusCode.BadRequest, "this passkey request has expired; try again")
        val credential = try {
            PublicKeyCredential.parseRegistrationResponseJson(credentialJson)
        } catch (e: IOException) {
            throw HttpError(HttpStatusCode.BadRequest, "not a passkey")
        }
        val result = try {
            relyingParty.finishRegistration(FinishRegistrationOptions.builder().request(registration.options).response(credential).build())
        } catch (e: RegistrationFailedException) {
            log.warn("passkey registration refused: {}", e.message)
            throw HttpError(HttpStatusCode.BadRequest, "the passkey was refused")
        }
        val credentialId = result.keyId.id.base64Url
        if (store.passkey(credentialId) != null) throw HttpError(HttpStatusCode.Conflict, "this passkey is already added")
        store.addPasskey(Store.Passkey(credentialId, userId, registration.options.user.id.base64Url, result.publicKeyCose.bytes, result.signatureCount))
        log.info("passkey added: user {}", userId)
    }

    /** Request options for a sign-in with any passkey for [rpId]. Throws 429 past the per-IP limit and 503 when too many are in flight. */
    fun startSignIn(ip: String): PasskeyChallenge {
        if (!perIp.allow(ip)) throw HttpError(HttpStatusCode.TooManyRequests, "too many tries; wait a while")
        val request = relyingParty.startAssertion(
            StartAssertionOptions.builder().userVerification(UserVerificationRequirement.REQUIRED).timeout(TTL.toMillis()).build()
        )
        val id = remember(signIns, request)
        return PasskeyChallenge(id, innerOptions(request.toCredentialsGetJson()))
    }

    /**
     * The user whose passkey signed [id]'s challenge. Throws 401 for a ceremony that is unknown or
     * expired, a passkey this server doesn't know (one whose account was deleted), and a signature
     * or counter that doesn't check out; 400 for an answer that isn't a passkey's at all.
     */
    fun finishSignIn(id: String, credentialJson: String): Long {
        val request = take(signIns, id) ?: throw HttpError(HttpStatusCode.Unauthorized, "this sign-in has expired; try again")
        val credential = try {
            PublicKeyCredential.parseAssertionResponseJson(credentialJson)
        } catch (e: IOException) {
            throw HttpError(HttpStatusCode.BadRequest, "not a passkey")
        }
        val result = try {
            relyingParty.finishAssertion(FinishAssertionOptions.builder().request(request).response(credential).build())
        } catch (e: AssertionFailedException) {
            log.warn("passkey sign-in refused: {}", e.message)
            throw HttpError(HttpStatusCode.Unauthorized, "this passkey is not recognised")
        }
        if (!result.isSuccess) throw HttpError(HttpStatusCode.Unauthorized, "this passkey is not recognised")
        store.usePasskey(result.credential.credentialId.base64Url, result.signatureCount)
        return result.username.toLong()
    }

    @Synchronized
    private fun <T> remember(map: HashMap<String, Pending<T>>, value: T): String {
        val now = clock()
        map.values.removeIf { it.expiresAt <= now }
        if (map.size >= maxPending) throw HttpError(HttpStatusCode.ServiceUnavailable, "too many passkey requests in progress")
        val id = Bytes(ByteArray(24).also(random::nextBytes)).base64Url
        map[id] = Pending(value, now + TTL)
        return id
    }

    @Synchronized
    private fun <T> take(map: HashMap<String, Pending<T>>, id: String): T? = map.remove(id)?.takeIf { it.expiresAt > clock() }?.value

    /**
     * The stored passkeys as Yubico's relying party asks for them. Its "username" is our user id
     * in decimal: [finishSignIn] reads the user back from it. Its username lookups serve only
     * flows that name an account first, which Gains doesn't have, so they find nothing; the
     * registration's exclude list is filled in by [startRegistration] instead.
     */
    private class StoreCredentials(private val store: Store) : CredentialRepository {
        override fun getCredentialIdsForUsername(username: String): Set<PublicKeyCredentialDescriptor> = emptySet()

        override fun getUserHandleForUsername(username: String): Optional<Bytes> = Optional.empty()

        override fun getUsernameForUserHandle(userHandle: Bytes): Optional<String> =
            Optional.ofNullable(store.userIdForPasskeyHandle(userHandle.base64Url)?.toString())

        override fun lookup(credentialId: Bytes, userHandle: Bytes): Optional<RegisteredCredential> =
            Optional.ofNullable(store.passkey(credentialId.base64Url)?.takeIf { it.userHandle == userHandle.base64Url }?.let(::registered))

        override fun lookupAll(credentialId: Bytes): Set<RegisteredCredential> =
            setOfNotNull(store.passkey(credentialId.base64Url)?.let(::registered))

        private fun registered(passkey: Store.Passkey): RegisteredCredential = RegisteredCredential.builder()
            .credentialId(Bytes.fromBase64Url(passkey.credentialId))
            .userHandle(Bytes.fromBase64Url(passkey.userHandle))
            .publicKeyCose(Bytes(passkey.publicKey))
            .signatureCount(passkey.signCount)
            .build()
    }

    companion object {
        /** How long a ceremony may take, from its start to its finish; the platform's sheet is given the same. */
        val TTL: Duration = Duration.ofMinutes(5)

        private val log = LoggerFactory.getLogger(Passkeys::class.java)

        /** The options object itself out of `{"publicKey": …}`, the wrapper a browser's `navigator.credentials` call takes. */
        private fun innerOptions(json: String): String = SyncJson.parseToJsonElement(json).jsonObject.getValue("publicKey").toString()
    }
}
