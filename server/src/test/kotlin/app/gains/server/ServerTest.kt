package app.gains.server

import app.gains.sync.BlobResponse
import app.gains.sync.GuestListRequest
import app.gains.sync.PullResponse
import app.gains.sync.PushRequest
import app.gains.sync.PushResponse
import app.gains.sync.SignInRequest
import app.gains.sync.SignInResponse
import app.gains.sync.SyncDocument
import app.gains.sync.SyncJson
import app.gains.sync.SyncKinds
import app.gains.sync.SyncProtocol
import app.gains.sync.UserInfo
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.KSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The routes one at a time, against the real verifier with a key pair of the test's own. */
class ServerTest {
    private val google = FakeProvider(JwksIdentityVerifier.GOOGLE_ISSUERS.first())
    private val apple = FakeProvider(JwksIdentityVerifier.APPLE_ISSUERS.first())

    private suspend fun <T> HttpResponse.read(serializer: KSerializer<T>): T {
        assertTrue(status.value in 200..299, "$status: ${bodyAsText()}")
        return SyncJson.decodeFromString(serializer, bodyAsText())
    }

    private suspend fun HttpClient.signIn(provider: String, token: String, name: String? = null): SignInResponse =
        post("/auth/$provider") {
            contentType(ContentType.Application.Json)
            setBody(SyncJson.encodeToString(SignInRequest.serializer(), SignInRequest(token, name)))
        }.read(SignInResponse.serializer())

    @Test
    fun signInVerifiesTheProvidersToken() = testApplication {
        application { gainsServer(testServices(google, apple)) }

        val ok = client.signIn("google", google.token("sub-1", GOOGLE_AUDIENCE, email = "a@b.c", name = "Ada"))
        assertEquals(UserInfo(1, "a@b.c", "Ada", listOf("google")), ok.user)
        val me = client.get("/auth/me") { header("Authorization", "Bearer ${ok.token}") }.read(UserInfo.serializer())
        assertEquals(ok.user, me)

        // Wrong audience, wrong issuer, expired, unknown key, garbage: all 401.
        for (bad in listOf(
            google.token("sub-1", "someone-else.apps.googleusercontent.com"),
            apple.token("sub-1", GOOGLE_AUDIENCE),
            google.token("sub-1", GOOGLE_AUDIENCE, expired = true),
            google.token("sub-1", GOOGLE_AUDIENCE, keyId = "rotated"),
            "not.a.jwt",
        )) {
            val response = client.post("/auth/google") {
                contentType(ContentType.Application.Json)
                setBody(SyncJson.encodeToString(SignInRequest.serializer(), SignInRequest(bad)))
            }
            assertEquals(HttpStatusCode.Unauthorized, response.status, response.bodyAsText())
        }

    }

    @Test
    fun aProviderWithoutClientIdsIsSwitchedOff() = testApplication {
        application { gainsServer(testServices(google, apple, appleEnabled = false)) }
        val response = client.post("/auth/apple") {
            contentType(ContentType.Application.Json)
            setBody(SyncJson.encodeToString(SignInRequest.serializer(), SignInRequest(apple.token("x", APPLE_AUDIENCE))))
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
    }

    @Test
    fun oneAccountAcrossProvidersByEmailAndAppleKeepsItsFirstName() = testApplication {
        application { gainsServer(testServices(google, apple)) }
        val viaGoogle = client.signIn("google", google.token("g-1", GOOGLE_AUDIENCE, email = "same@x.y", name = "Ada", emailVerified = true))
        // Apple's token has the email; the name came to the app separately and is passed along only the first time.
        val viaApple = client.signIn("apple", apple.token("a-1", APPLE_AUDIENCE, email = "same@x.y", emailVerified = "true"), name = "Ada L.")
        assertEquals(viaGoogle.user.id, viaApple.user.id)
        assertEquals(listOf("apple", "google"), viaApple.user.providers)
        assertEquals("Ada", viaApple.user.name, "the first name given is kept")

        val again = client.signIn("apple", apple.token("a-1", APPLE_AUDIENCE))
        assertEquals(viaGoogle.user.id, again.user.id)
        assertEquals("same@x.y", again.user.email, "a later token without the email does not blank it")

        val other = client.signIn("apple", apple.token("a-2", APPLE_AUDIENCE, email = "other@x.y", emailVerified = true))
        assertNotEquals(viaGoogle.user.id, other.user.id)
    }

    @Test
    fun onlyVerifiedEmailsJoinAccounts() = testApplication {
        application { gainsServer(testServices(google, apple)) }
        val viaApple = client.signIn("apple", apple.token("a-1", APPLE_AUDIENCE, email = "same@x.y", emailVerified = "true"))

        // An unverified Google identity with the same email gets an account of its own, and keeps its email.
        for (claim in listOf<Any?>(false, "false", null, "yes")) {
            val unverified = client.signIn("google", google.token("g-$claim", GOOGLE_AUDIENCE, email = "same@x.y", emailVerified = claim))
            assertNotEquals(viaApple.user.id, unverified.user.id, "email_verified = $claim")
            assertEquals(listOf("google"), unverified.user.providers)
            assertEquals("same@x.y", unverified.user.email)
        }

        // A verified one joins, whatever the case of the address.
        val verified = client.signIn("google", google.token("g-ok", GOOGLE_AUDIENCE, email = "Same@X.y", emailVerified = true))
        assertEquals(viaApple.user.id, verified.user.id)
        assertEquals(listOf("apple", "google"), verified.user.providers)
    }

    @Test
    fun aVerifiedEmailNeverJoinsAnAccountThatOnlyClaimedIt() = testApplication {
        application { gainsServer(testServices(google, apple)) }
        val squatter = client.signIn("google", google.token("g-1", GOOGLE_AUDIENCE, email = "same@x.y", emailVerified = false))
        val owner = client.signIn("apple", apple.token("a-1", APPLE_AUDIENCE, email = "same@x.y", emailVerified = true))
        assertNotEquals(squatter.user.id, owner.user.id)

        // Once its provider verifies the address, the next sign-in records it, and later identities join.
        val nowVerified = client.signIn("google", google.token("g-1", GOOGLE_AUDIENCE, email = "same@x.y", emailVerified = true))
        assertEquals(squatter.user.id, nowVerified.user.id, "an identity never moves between users")
        val third = client.signIn("google", google.token("g-2", GOOGLE_AUDIENCE, email = "same@x.y", emailVerified = true))
        assertEquals(minOf(squatter.user.id, owner.user.id), third.user.id, "the oldest verified user wins")
    }

    @Test
    fun tokensExpireAndRefresh() = testApplication {
        application { gainsServer(testServices(google, apple)) }
        val signedIn = client.signIn("google", google.token("g-1", GOOGLE_AUDIENCE))
        val refreshed = client.post("/auth/refresh") { header("Authorization", "Bearer ${signedIn.token}") }.read(SignInResponse.serializer())
        assertEquals(signedIn.user, refreshed.user)

        assertEquals(HttpStatusCode.Unauthorized, client.get("/auth/me").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/auth/me") { header("Authorization", "Bearer nope") }.status)
        val stale = SessionTokens("a-test-secret-that-is-long-enough-for-hmac-256", clock = { java.time.Instant.now().minusSeconds(40L * 24 * 3600) }).issue(1)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/auth/me") { header("Authorization", "Bearer $stale") }.status)
        val otherSecret = SessionTokens("another-secret-that-is-also-long-enough-xx").issue(1)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/auth/me") { header("Authorization", "Bearer $otherSecret") }.status)
    }

    @Test
    fun pushPullAndLastWriterWins() = testApplication {
        application { gainsServer(testServices(google, apple)) }
        val token = client.signIn("google", google.token("g-1", GOOGLE_AUDIENCE)).token
        suspend fun push(vararg docs: SyncDocument) = client.post("/sync/push") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(SyncJson.encodeToString(PushRequest.serializer(), PushRequest(docs.toList())))
        }.read(PushResponse.serializer())
        suspend fun pull(since: Long, limit: Int = 500) = client.get("/sync/pull?since=$since&limit=$limit") { header("Authorization", "Bearer $token") }.read(PullResponse.serializer())

        val first = push(
            SyncDocument(SyncKinds.BODYWEIGHT, "2026-09-20", "2026-09-20T10:00:00.000Z", payload = """{"kg":82.4}"""),
            SyncDocument(SyncKinds.SETTING, "weight_unit", "2026-09-20T10:00:01.000Z", payload = """{"value":"KG"}"""),
        )
        assertEquals(listOf(1L, 2L), first.results.map { it.seq })
        assertTrue(first.results.all { it.accepted })

        // An older version is declined and answered with the current seq; a newer one moves the row to the front.
        val second = push(
            SyncDocument(SyncKinds.BODYWEIGHT, "2026-09-20", "2026-09-20T09:00:00.000Z", payload = """{"kg":1.0}"""),
            SyncDocument(SyncKinds.BODYWEIGHT, "2026-09-20", "2026-09-20T11:00:00.000Z", payload = """{"kg":82.0}"""),
        )
        assertEquals(listOf(false, true), second.results.map { it.accepted })
        assertEquals(listOf(1L, 3L), second.results.map { it.seq })

        val all = pull(0)
        assertEquals(listOf("weight_unit" to 2L, "2026-09-20" to 3L), all.documents.map { it.id to it.seq })
        assertEquals("""{"kg":82.0}""", all.documents.last().payload)
        assertEquals(3L, all.cursor)
        assertEquals(false, all.more)

        val page = pull(0, limit = 1)
        assertEquals(1, page.documents.size)
        assertEquals(true, page.more)
        assertEquals(2L, page.cursor)
        val rest = pull(page.cursor, limit = 1)
        assertEquals(listOf(3L), rest.documents.map { it.seq })
        assertEquals(false, rest.more)

        val nothing = pull(3)
        assertEquals(emptyList(), nothing.documents)
        assertEquals(3L, nothing.cursor)

        // A tombstone keeps the row, empties it, and is what the next pull returns.
        push(SyncDocument(SyncKinds.BODYWEIGHT, "2026-09-20", "2026-09-20T12:00:00.000Z", deleted = true))
        val afterDelete = pull(3).documents.single()
        assertEquals(true, afterDelete.deleted)
        assertEquals("", afterDelete.payload)

        // Kinds the server does not know, and photos through the feed, are refused.
        val bad = client.post("/sync/push") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(SyncJson.encodeToString(PushRequest.serializer(), PushRequest(listOf(SyncDocument("secret", "x", "2026-09-20T12:00:00.000Z")))))
        }
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        val photoInFeed = client.post("/sync/push") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(SyncJson.encodeToString(PushRequest.serializer(), PushRequest(listOf(SyncDocument(SyncKinds.SESSION_PHOTO, "x", "2026-09-20T12:00:00.000Z", payload = "{}")))))
        }
        assertEquals(HttpStatusCode.BadRequest, photoInFeed.status)
    }

    @Test
    fun blobsRideTheFeedAndTheirBytesTheirOwnRoute() = testApplication {
        application { gainsServer(testServices(google, apple)) }
        val token = client.signIn("google", google.token("g-1", GOOGLE_AUDIENCE)).token
        val bytes = ByteArray(300) { it.toByte() }

        val put = client.put("/sync/blobs/session_photo/2026-09-20T10:00") {
            header("Authorization", "Bearer $token")
            header(SyncProtocol.HEADER_UPDATED_AT, "2026-09-20T10:00:00.000Z")
            contentType(ContentType.Application.OctetStream)
            setBody(bytes)
        }.read(BlobResponse.serializer())
        assertEquals(1L, put.seq)

        val feed = client.get("/sync/pull?since=0") { header("Authorization", "Bearer $token") }.read(PullResponse.serializer()).documents.single()
        assertEquals(SyncKinds.SESSION_PHOTO, feed.kind)
        assertTrue(""""size":300""" in feed.payload, feed.payload)
        assertTrue(""""sha256":"""" in feed.payload, feed.payload)

        val got = client.get("/sync/blobs/session_photo/2026-09-20T10:00") { header("Authorization", "Bearer $token") }
        assertEquals(bytes.toList(), got.bodyAsBytes().toList())

        // Too big, and an older upload than what is stored.
        assertEquals(HttpStatusCode.PayloadTooLarge, client.put("/sync/blobs/session_photo/big") {
            header("Authorization", "Bearer $token"); header(SyncProtocol.HEADER_UPDATED_AT, "2026-09-20T10:00:00.000Z"); setBody(ByteArray(2000))
        }.status)
        assertEquals(HttpStatusCode.Conflict, client.put("/sync/blobs/session_photo/2026-09-20T10:00") {
            header("Authorization", "Bearer $token"); header(SyncProtocol.HEADER_UPDATED_AT, "2026-09-20T09:00:00.000Z"); setBody(bytes)
        }.status)

        // A tombstone through the feed drops the bytes.
        client.post("/sync/push") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(SyncJson.encodeToString(PushRequest.serializer(), PushRequest(listOf(SyncDocument(SyncKinds.SESSION_PHOTO, "2026-09-20T10:00", "2026-09-20T11:00:00.000Z", deleted = true)))))
        }.read(PushResponse.serializer())
        assertEquals(HttpStatusCode.NotFound, client.get("/sync/blobs/session_photo/2026-09-20T10:00") { header("Authorization", "Bearer $token") }.status)
    }

    @Test
    fun aPushOverTheDocumentCeilingIsRefusedAndWritesNothing() = testApplication {
        val services = testServices(google, apple).let { Services(it.store, it.tokens, it.verifier, maxDocumentsPerUser = 3) }
        application { gainsServer(services) }
        val signedIn = client.signIn("google", google.token("g-1", GOOGLE_AUDIENCE))
        val token = signedIn.token
        suspend fun push(vararg docs: SyncDocument) = client.post("/sync/push") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(SyncJson.encodeToString(PushRequest.serializer(), PushRequest(docs.toList())))
        }
        fun weight(day: String, at: String, kg: Double) = SyncDocument(SyncKinds.BODYWEIGHT, day, at, payload = """{"kg":$kg}""")

        assertEquals(HttpStatusCode.OK, push(weight("d1", "2026-09-20T10:00:00.000Z", 80.0), weight("d2", "2026-09-20T10:00:00.000Z", 81.0)).status)
        // Two new rows on top of two would make four: refused whole, the edit riding with them too.
        val over = push(weight("d1", "2026-09-21T10:00:00.000Z", 70.0), weight("d3", "2026-09-21T10:00:00.000Z", 82.0), weight("d4", "2026-09-21T10:00:00.000Z", 83.0))
        assertEquals(HttpStatusCode.PayloadTooLarge, over.status, over.bodyAsText())
        val stored = services.store.pull(signedIn.user.id, 0, SyncProtocol.PAGE).first
        assertEquals(listOf("d1" to 1L, "d2" to 2L), stored.map { it.id to it.seq }, "nothing written, no seq taken")
        assertEquals("""{"kg":80.0}""", stored.first().payload)

        // Up to the ceiling is fine; at it, edits and deletes of what is there still go through.
        assertEquals(HttpStatusCode.OK, push(weight("d3", "2026-09-21T10:00:00.000Z", 82.0)).status)
        assertEquals(HttpStatusCode.PayloadTooLarge, push(weight("d4", "2026-09-21T10:00:00.000Z", 83.0)).status)
        assertEquals(HttpStatusCode.OK, push(weight("d1", "2026-09-22T10:00:00.000Z", 79.0), SyncDocument(SyncKinds.BODYWEIGHT, "d2", "2026-09-22T10:00:00.000Z", deleted = true)).status)
        // A new photo is a feed row too.
        assertEquals(HttpStatusCode.PayloadTooLarge, client.put("/sync/blobs/session_photo/p") {
            header("Authorization", "Bearer $token"); header(SyncProtocol.HEADER_UPDATED_AT, "2026-09-22T10:00:00.000Z"); setBody(ByteArray(10))
        }.status)
        assertEquals(null, services.store.blob(signedIn.user.id, SyncKinds.SESSION_PHOTO, "p"))

        // The ceiling is per account: someone else starts from zero.
        val other = client.signIn("google", google.token("g-2", GOOGLE_AUDIENCE)).token
        assertEquals(HttpStatusCode.OK, client.post("/sync/push") {
            header("Authorization", "Bearer $other")
            contentType(ContentType.Application.Json)
            setBody(SyncJson.encodeToString(PushRequest.serializer(), PushRequest(listOf(weight("d1", "2026-09-20T10:00:00.000Z", 60.0)))))
        }.status)
    }

    @Test
    fun aBlobOverTheByteCeilingIsRefusedAndStoresNothing() = testApplication {
        val services = testServices(google, apple).let { Services(it.store, it.tokens, it.verifier, maxBlobBytesPerUser = 1000) }
        application { gainsServer(services) }
        val signedIn = client.signIn("google", google.token("g-1", GOOGLE_AUDIENCE))
        val token = signedIn.token
        suspend fun put(id: String, at: String, size: Int) = client.put("/sync/blobs/session_photo/$id") {
            header("Authorization", "Bearer $token"); header(SyncProtocol.HEADER_UPDATED_AT, at); setBody(ByteArray(size) { 7 })
        }

        assertEquals(HttpStatusCode.OK, put("a", "2026-09-20T10:00:00.000Z", 600).status)
        val over = put("b", "2026-09-20T10:00:00.000Z", 500)
        assertEquals(HttpStatusCode.InsufficientStorage, over.status, over.bodyAsText())
        assertEquals(null, services.store.blob(signedIn.user.id, SyncKinds.SESSION_PHOTO, "b"))
        assertEquals(listOf("a"), services.store.pull(signedIn.user.id, 0, SyncProtocol.PAGE).first.map { it.id }, "no feed row for the refused one")

        // What fits still does, and replacing a photo counts the new bytes instead of the old.
        assertEquals(HttpStatusCode.OK, put("b", "2026-09-20T10:00:00.000Z", 400).status)
        assertEquals(HttpStatusCode.OK, put("a", "2026-09-21T10:00:00.000Z", 600).status)
        assertEquals(HttpStatusCode.InsufficientStorage, put("a", "2026-09-22T10:00:00.000Z", 601).status)
        assertEquals(600, services.store.blob(signedIn.user.id, SyncKinds.SESSION_PHOTO, "a")!!.size)

        // Deleting a photo frees its bytes.
        client.post("/sync/push") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(SyncJson.encodeToString(PushRequest.serializer(), PushRequest(listOf(SyncDocument(SyncKinds.SESSION_PHOTO, "b", "2026-09-22T10:00:00.000Z", deleted = true)))))
        }.read(PushResponse.serializer())
        assertEquals(HttpStatusCode.OK, put("c", "2026-09-22T10:00:00.000Z", 400).status)
    }

    @Test
    fun usersNeverSeeEachOtherAndDeletingAnAccountRemovesEverything() = testApplication {
        application { gainsServer(testServices(google, apple)) }
        val a = client.signIn("google", google.token("a", GOOGLE_AUDIENCE)).token
        val b = client.signIn("google", google.token("b", GOOGLE_AUDIENCE)).token
        client.post("/sync/push") {
            header("Authorization", "Bearer $a")
            contentType(ContentType.Application.Json)
            setBody(SyncJson.encodeToString(PushRequest.serializer(), PushRequest(listOf(SyncDocument(SyncKinds.BODYWEIGHT, "d", "2026-09-20T10:00:00.000Z", payload = "{\"kg\":1}")))))
        }.read(PushResponse.serializer())
        client.put("/sync/blobs/session_photo/p") {
            header("Authorization", "Bearer $a"); header(SyncProtocol.HEADER_UPDATED_AT, "2026-09-20T10:00:00.000Z"); setBody(ByteArray(10))
        }.read(BlobResponse.serializer())

        assertEquals(emptyList(), client.get("/sync/pull?since=0") { header("Authorization", "Bearer $b") }.read(PullResponse.serializer()).documents)
        assertEquals(HttpStatusCode.NotFound, client.get("/sync/blobs/session_photo/p") { header("Authorization", "Bearer $b") }.status)

        assertEquals(HttpStatusCode.NoContent, client.delete("/auth/account") { header("Authorization", "Bearer $a") }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/auth/me") { header("Authorization", "Bearer $a") }.status)
        // The same person signing in again starts from nothing.
        val fresh = client.signIn("google", google.token("a", GOOGLE_AUDIENCE))
        assertEquals(emptyList(), client.get("/sync/pull?since=0") { header("Authorization", "Bearer ${fresh.token}") }.read(PullResponse.serializer()).documents)
        assertEquals(HttpStatusCode.NotFound, client.get("/sync/blobs/session_photo/p") { header("Authorization", "Bearer ${fresh.token}") }.status)
    }

    @Test
    fun aDeletedAccountsTokenOpensNoRoute() = testApplication {
        val services = testServices(google, apple)
        application { gainsServer(services) }
        val signedIn = client.signIn("google", google.token("a", GOOGLE_AUDIENCE))
        val a = signedIn.token
        assertEquals(HttpStatusCode.NoContent, client.delete("/auth/account") { header("Authorization", "Bearer $a") }.status)

        // Every protected route, for the deleted account's token and for one minted for an id
        // that never existed. The second device's copy of the token is the case that matters:
        // it must not write the deleted data back.
        val never = services.tokens.issue(999)
        for (token in listOf(a, never)) {
            val auth = "Bearer $token"
            val responses = mapOf(
                "GET /auth/me" to client.get("/auth/me") { header("Authorization", auth) },
                "POST /auth/refresh" to client.post("/auth/refresh") { header("Authorization", auth) },
                "DELETE /auth/account" to client.delete("/auth/account") { header("Authorization", auth) },
                "POST /sync/push" to client.post("/sync/push") {
                    header("Authorization", auth)
                    contentType(ContentType.Application.Json)
                    setBody(SyncJson.encodeToString(PushRequest.serializer(), PushRequest(listOf(SyncDocument(SyncKinds.BODYWEIGHT, "d", "2026-09-20T10:00:00.000Z", payload = "{\"kg\":1}")))))
                },
                "GET /sync/pull" to client.get("/sync/pull?since=0") { header("Authorization", auth) },
                "PUT /sync/blobs" to client.put("/sync/blobs/session_photo/x") {
                    header("Authorization", auth); header(SyncProtocol.HEADER_UPDATED_AT, "2026-09-20T10:00:00.000Z"); setBody(ByteArray(10))
                },
                "GET /sync/blobs" to client.get("/sync/blobs/session_photo/x") { header("Authorization", auth) },
            )
            for ((route, response) in responses) {
                assertEquals(HttpStatusCode.Unauthorized, response.status, "$route: ${response.bodyAsText()}")
            }
        }

        // The push and the upload wrote nothing under either id.
        for (id in listOf(signedIn.user.id, 999L)) {
            assertEquals(emptyList(), services.store.pull(id, 0, SyncProtocol.PAGE).first)
            assertEquals(null, services.store.blob(id, SyncKinds.SESSION_PHOTO, "x"))
        }
    }

    @Test
    fun theGuestListTakesEachAddressOnceAndStopsWhenFull() = testApplication {
        val services = testServices(google, apple).let { Services(it.store, it.tokens, it.verifier, maxGuestList = 2) }
        application { gainsServer(services) }
        suspend fun join(email: String) = client.post("/guest-list") {
            contentType(ContentType.Application.Json)
            setBody(SyncJson.encodeToString(GuestListRequest.serializer(), GuestListRequest(email)))
        }.status

        assertEquals(HttpStatusCode.NoContent, join("ada@example.com"))
        // Already listed, in any case or with stray spaces: the same answer, and no second row.
        assertEquals(HttpStatusCode.NoContent, join("  Ada@Example.COM "))
        assertEquals(HttpStatusCode.NoContent, join("grace@example.org"))
        assertEquals(HttpStatusCode.ServiceUnavailable, join("linus@example.net"))
        assertEquals(HttpStatusCode.NoContent, join("grace@example.org"), "a listed address still answers when full")

        for (bad in listOf("", "ada", "ada@", "@example.com", "ada@example", "a da@example.com", "a@b@c.d", "a".repeat(250) + "@x.yz")) {
            assertEquals(HttpStatusCode.BadRequest, join(bad), bad)
        }
        val garbage = client.post("/guest-list") {
            contentType(ContentType.Application.Json)
            setBody("{\"mail\":\"ada@example.com\"}")
        }
        assertEquals(HttpStatusCode.BadRequest, garbage.status)
    }

    @Test
    fun dotenvIsReadTheWayItIsWritten() {
        val parsed = Config.parseDotenv(
            """
            # a comment
            JWT_SECRET="quoted value"
            GOOGLE_CLIENT_IDS=a.apps.googleusercontent.com, b.apps.googleusercontent.com
            export PORT=5003

            EMPTY=
            """.trimIndent(),
        )
        assertEquals("quoted value", parsed["JWT_SECRET"])
        assertEquals("5003", parsed["PORT"])
        assertEquals("", parsed["EMPTY"])
        val config = Config.fromEnvironment(parsed + ("JWT_SECRET" to "x".repeat(32)), workingDir = java.io.File("/nonexistent"))
        assertEquals(listOf("a.apps.googleusercontent.com", "b.apps.googleusercontent.com"), config.googleClientIds)
        assertEquals(emptyList(), config.appleClientIds)
        assertEquals(5003, config.port)
        assertEquals(null, config.smtp, "no mail account: email sign-in off")
        assertEquals("https://gains.gerra.sh", config.siteUrl)

        val mail = Config.fromEnvironment(mapOf("JWT_SECRET" to "x".repeat(32), "SMTP_HOST" to "smtp.example", "SMTP_USER" to "u", "SMTP_PASSWORD" to "p", "MAIL_FROM" to "gains@gerra.sh", "GAINS_SITE_URL" to "https://site.example/"), workingDir = java.io.File("/nonexistent"))
        assertEquals(Config.Smtp("smtp.example", 587, "u", "p", "gains@gerra.sh"), mail.smtp)
        assertEquals("https://site.example", mail.siteUrl)
        assertEquals("Smtp(host=smtp.example, port=587, user=u, from=gains@gerra.sh)", mail.smtp.toString(), "the password never goes in a log")
        for (partial in listOf(mapOf("SMTP_HOST" to "smtp.example"), mapOf("MAIL_FROM" to "gains@gerra.sh"), mapOf("SMTP_HOST" to "h", "MAIL_FROM" to "f", "SMTP_USER" to "u"))) {
            kotlin.test.assertFailsWith<IllegalArgumentException>(partial.toString()) { Config.fromEnvironment(partial + ("JWT_SECRET" to "x".repeat(32)), workingDir = java.io.File("/nonexistent")) }
        }
    }
}
