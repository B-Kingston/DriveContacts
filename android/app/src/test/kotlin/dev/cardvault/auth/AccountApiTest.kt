package dev.cardvault.auth

import dev.cardvault.BuildConfig
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** A fake server. [handler] gets every request and returns (status, body, extra headers). */
class FakeServer(private val handler: (Request) -> Triple<Int, String, Map<String, String>>) {
    val requests = ArrayList<Request>()

    val client: OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        requests += request
        val (code, body, headers) = handler(request)
        Response.Builder()
            .request(request).protocol(Protocol.HTTP_1_1).code(code).message("fake")
            .body(body.toResponseBody("application/json".toMediaType()))
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
    }.build()
}

fun reply(code: Int, body: String, headers: Map<String, String> = emptyMap()) = Triple(code, body, headers)

class AccountApiTest {
    private class FixedAuth(var credentials: Credentials?, val onRefresh: (String) -> Credentials?) : SessionAuth {
        var refreshCalls = 0
        override suspend fun current() = credentials
        override suspend fun refreshAfter401(rejectedAccessToken: String): Credentials? {
            refreshCalls++
            return onRefresh(rejectedAccessToken)?.also { credentials = it }
        }
    }

    @Test
    fun parsesForkInitWithUnknownFields() = runTest {
        val server = FakeServer { reply(200, """{"Code":1000,"Selector":"sel","UserCode":"UC","Extra":{"a":1}}""") }
        val result = AccountApi(server.client, sleep = {}).forkInit()
        assertEquals("sel", result.selector)
        assertEquals("UC", result.userCode)
        val request = server.requests.single()
        assertEquals("https://drive-api.proton.me/auth/v4/sessions/forks", request.url.toString())
        assertEquals(BuildConfig.PROTON_APP_VERSION, request.header("x-pm-appversion"))
        assertNull(request.header("Authorization"))
    }

    @Test
    fun forkPollGives422AsPendingAndParsesSuccess() = runTest {
        var ready = false
        val server = FakeServer {
            if (!ready) reply(422, """{"Code":2001,"Error":"pending"}""")
            else reply(
                200,
                """{"Code":1000,"UID":"u","AccessToken":"a","RefreshToken":"r","UserID":"id","Payload":"cGF5","Scopes":["x"],"LocalID":3}""",
            )
        }
        val api = AccountApi(server.client, sleep = {})
        assertNull(api.forkPoll("a/b"))
        ready = true
        val session = api.forkPoll("a/b")!!
        assertEquals(ForkSessionResponse("u", "a", "r", "id", "cGF5"), session)
        assertEquals("https://drive-api.proton.me/auth/v4/sessions/forks/a%2Fb", server.requests.last().url.toString())
    }

    @Test
    fun forkPollThrowsOnOtherErrors() = runTest {
        val server = FakeServer { reply(500, """{"Code":2500,"Error":"boom"}""") }
        val error = assertThrows(AccountApiException::class.java) { kotlinx.coroutines.runBlocking { AccountApi(server.client, sleep = {}).forkPoll("s") } }
        assertEquals(500, error.httpCode)
        assertEquals(2500, error.apiCode)
    }

    @Test
    fun refreshSendsBodyAndKeepsOldValuesWhenAbsent() = runTest {
        val server = FakeServer { reply(200, """{"Code":1000,"AccessToken":"new-access","Scopes":[]}""") }
        val tokens = AccountApi(server.client, sleep = {}).refresh("uid", "old-access", "old-refresh")
        assertEquals(Tokens("uid", "new-access", "old-refresh"), tokens)
        val request = server.requests.single()
        assertEquals("POST", request.method)
        assertEquals("uid", request.header("x-pm-uid"))
        val buffer = okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8()
        assertEquals(
            """{"ResponseType":"token","GrantType":"refresh_token","RefreshToken":"old-refresh","RedirectURI":"https://proton.me"}""",
            buffer,
        )
    }

    @Test
    fun parsesUserAndAddresses() = runTest {
        val server = FakeServer { request ->
            if (request.url.encodedPath == "/core/v4/users") {
                reply(200, """{"Code":1000,"User":{"ID":"U1","Name":"n","Keys":[{"ID":"k1","PrivateKey":"PK","Primary":1,"Active":0,"Fingerprint":"f"}],"Other":1}}""")
            } else {
                reply(
                    200,
                    """{"Code":1000,"Total":1,"Addresses":[{"ID":"a1","Email":"x@y.z","Status":1,"Order":2,"Send":1,
                       "Keys":[{"ID":"ak","PrivateKey":"P","Token":"T","Signature":"S","Primary":1,"Active":1,"Flags":3,"Version":3},
                               {"ID":"ak2","Primary":0}]}]}""",
                )
            }
        }
        val auth = FixedAuth(Credentials("uid", "tok")) { null }
        val api = AccountApi(server.client, sleep = {})
        val user = api.getUser(auth)
        assertEquals(UserDto("U1", listOf(UserKeyDto("k1", "PK", 1, 0))), user)
        val addresses = api.getAddresses(auth)
        assertEquals("a1", addresses.single().id)
        assertEquals(AddressKeyDto("ak", "P", null, "T", "S", 1, 1, 3), addresses.single().keys[0])
        assertEquals(1, addresses.single().keys[1].active)
        assertNull(addresses.single().keys[1].token)
        assertEquals("https://drive-api.proton.me/core/v4/addresses?Page=0&PageSize=150", server.requests.last().url.toString())
        assertEquals("Bearer tok", server.requests.last().header("Authorization"))
        assertEquals("uid", server.requests.last().header("x-pm-uid"))
    }

    @Test
    fun refreshesOnceOn401AndRetriesOnce() = runTest {
        val server = FakeServer { request ->
            if (request.header("Authorization") == "Bearer fresh") reply(200, """{"Code":1000,"User":{"ID":"U"}}""")
            else reply(401, """{"Code":401,"Error":"expired"}""")
        }
        val auth = FixedAuth(Credentials("uid", "stale")) { Credentials("uid", "fresh") }
        assertEquals("U", AccountApi(server.client, sleep = {}).getUser(auth).id)
        assertEquals(1, auth.refreshCalls)
        assertEquals(2, server.requests.size)
    }

    @Test
    fun secondUnauthorizedAnswerFailsWithoutAnotherRefresh() = runTest {
        val server = FakeServer { reply(401, """{"Code":401,"Error":"no"}""") }
        val auth = FixedAuth(Credentials("uid", "stale")) { Credentials("uid", "fresh") }
        val error = assertThrows(AccountApiException::class.java) { kotlinx.coroutines.runBlocking { AccountApi(server.client, sleep = {}).getUser(auth) } }
        assertEquals(401, error.httpCode)
        assertEquals(1, auth.refreshCalls)
        assertEquals(2, server.requests.size)
    }

    @Test
    fun failedRefreshGivesTheOriginal401() = runTest {
        val server = FakeServer { reply(401, """{"Code":401,"Error":"no"}""") }
        val auth = FixedAuth(Credentials("uid", "stale")) { null }
        val error = assertThrows(AccountApiException::class.java) { kotlinx.coroutines.runBlocking { AccountApi(server.client, sleep = {}).getUser(auth) } }
        assertEquals(401, error.httpCode)
        assertEquals(1, server.requests.size)
    }

    @Test
    fun waitsForRetryAfterOnceOn429() = runTest {
        val waits = ArrayList<Long>()
        var calls = 0
        val server = FakeServer { if (calls++ == 0) reply(429, "{}", mapOf("Retry-After" to "7")) else reply(200, """{"Code":1000,"Addresses":[]}""") }
        val auth = FixedAuth(Credentials("u", "t")) { null }
        assertTrue(AccountApi(server.client, sleep = { waits += it }).getAddresses(auth).isEmpty())
        assertEquals(listOf(7000L), waits)
    }

    @Test
    fun secondRateLimitAnswerFails() = runTest {
        val waits = ArrayList<Long>()
        val server = FakeServer { reply(429, """{"Code":2028,"Error":"slow down"}""", mapOf("Retry-After" to "999")) }
        val auth = FixedAuth(Credentials("u", "t")) { null }
        val error = assertThrows(AccountApiException::class.java) {
            kotlinx.coroutines.runBlocking { AccountApi(server.client, sleep = { waits += it }).getAddresses(auth) }
        }
        assertEquals(429, error.httpCode)
        assertEquals(listOf(60_000L), waits)
        assertEquals(2, server.requests.size)
    }

    @Test
    fun retryAfterIsLimitedAndHasADefault() {
        assertEquals(5000L, AccountApi.retryAfterMillis(null))
        assertEquals(5000L, AccountApi.retryAfterMillis("Wed, 21 Oct 2026 07:28:00 GMT"))
        assertEquals(0L, AccountApi.retryAfterMillis("0"))
        assertEquals(60_000L, AccountApi.retryAfterMillis("61"))
    }

    @Test
    fun publicKeysUseEncodedEmailAndMissingAccountGivesEmptyList() = runTest {
        val server = FakeServer { request ->
            if (request.url.queryParameter("Email") == "none@ext.org") reply(422, """{"Code":33103,"Error":"external"}""")
            else reply(200, """{"Code":1000,"Address":{"Keys":[{"Flags":3,"PublicKey":"A","Source":0},{"Flags":2,"PublicKey":"B"}]},"IsProton":1}""")
        }
        val auth = FixedAuth(Credentials("u", "t")) { null }
        val api = AccountApi(server.client, sleep = {})
        assertTrue(api.getPublicKeys(auth, "none@ext.org").isEmpty())
        val keys = api.getPublicKeys(auth, "a+b@x.org")
        assertEquals(listOf(PublicKeyEntry(3, "A"), PublicKeyEntry(2, "B")), keys)
        assertTrue(server.requests.last().url.toString().contains("Email=a%2Bb%40x.org") || server.requests.last().url.toString().contains("Email=a%2Bb@x.org"))
        assertEquals("1", server.requests.last().url.queryParameter("InternalOnly"))
    }

    @Test
    fun otherPublicKeyErrorsThrow() = runTest {
        val server = FakeServer { reply(422, """{"Code":2001,"Error":"bad"}""") }
        val auth = FixedAuth(Credentials("u", "t")) { null }
        assertThrows(AccountApiException::class.java) { kotlinx.coroutines.runBlocking { AccountApi(server.client, sleep = {}).getPublicKeys(auth, "a@b.c") } }
    }
}
