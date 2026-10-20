package dev.cardvault.auth

import dev.cardvault.BuildConfig
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** The JSON settings for all Proton API answers. Unknown keys are ignored. */
internal val AccountJson = Json { ignoreUnknownKeys = true }

@Serializable
data class ForkInitResponse(
    @SerialName("Selector") val selector: String,
    @SerialName("UserCode") val userCode: String,
)

@Serializable
data class ForkSessionResponse(
    @SerialName("UID") val uid: String,
    @SerialName("AccessToken") val accessToken: String,
    @SerialName("RefreshToken") val refreshToken: String,
    @SerialName("UserID") val userId: String,
    @SerialName("Payload") val payload: String,
)

@Serializable
data class RefreshResponse(
    @SerialName("AccessToken") val accessToken: String? = null,
    @SerialName("RefreshToken") val refreshToken: String? = null,
    @SerialName("UID") val uid: String? = null,
)

@Serializable
data class UserKeyDto(
    @SerialName("ID") val id: String,
    @SerialName("PrivateKey") val privateKey: String,
    @SerialName("Primary") val primary: Int = 0,
    @SerialName("Active") val active: Int = 1,
)

@Serializable
data class UserDto(
    @SerialName("ID") val id: String,
    @SerialName("Keys") val keys: List<UserKeyDto> = emptyList(),
)

@Serializable
data class UserResponse(@SerialName("User") val user: UserDto)

@Serializable
data class AddressKeyDto(
    @SerialName("ID") val id: String,
    @SerialName("PrivateKey") val privateKey: String? = null,
    @SerialName("PublicKey") val publicKey: String? = null,
    @SerialName("Token") val token: String? = null,
    @SerialName("Signature") val signature: String? = null,
    @SerialName("Primary") val primary: Int = 0,
    @SerialName("Active") val active: Int = 1,
    @SerialName("Flags") val flags: Int = 3,
)

@Serializable
data class AddressDto(
    @SerialName("ID") val id: String,
    @SerialName("Email") val email: String,
    /** 0 disabled, 1 enabled, 2 deleting. */
    @SerialName("Status") val status: Int = 1,
    @SerialName("Order") val order: Int = 0,
    @SerialName("Keys") val keys: List<AddressKeyDto> = emptyList(),
)

@Serializable
data class AddressesResponse(@SerialName("Addresses") val addresses: List<AddressDto> = emptyList())

@Serializable
data class PublicKeyEntry(
    @SerialName("Flags") val flags: Int = 0,
    @SerialName("PublicKey") val publicKey: String,
)

@Serializable
data class PublicKeysAddress(@SerialName("Keys") val keys: List<PublicKeyEntry> = emptyList())

@Serializable
data class PublicKeysResponse(@SerialName("Address") val address: PublicKeysAddress? = null)

@Serializable
private data class ApiErrorBody(
    @SerialName("Code") val code: Int? = null,
    @SerialName("Error") val error: String? = null,
)

/** Credentials of the current session for one request. */
data class Credentials(val uid: String, val accessToken: String)

/** The tokens after a refresh. */
data class Tokens(val uid: String, val accessToken: String, val refreshToken: String)

/** Gives [AccountApi] the credentials for authenticated requests. */
interface SessionAuth {
    suspend fun current(): Credentials?

    /** Returns new credentials after a 401 for [rejectedAccessToken], or null when the session cannot be refreshed. */
    suspend fun refreshAfter401(rejectedAccessToken: String): Credentials?
}

/** An API answer with an error status. [apiCode] is the `Code` of the JSON body, when present. */
class AccountApiException(val httpCode: Int, val apiCode: Int?, message: String) : Exception(message)

/** Proton account API (docs/spec.md section 9) over its own HTTP client. */
class AccountApi(
    private val client: OkHttpClient = newClient(),
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val appVersion: String = BuildConfig.PROTON_APP_VERSION,
    private val sleep: suspend (millis: Long) -> Unit = { delay(it) },
) {
    private class Reply(val code: Int, val body: String, val retryAfter: String?) {
        val ok get() = code in 200..299
    }

    /** Step 1 of the sign-in: asks for a fork selector. */
    suspend fun forkInit(): ForkInitResponse =
        decode(send(null) { it.url(url("auth/v4/sessions/forks")).get() }.requireOk())

    /** Step 3 of the sign-in: returns null while the user did not finish the sign-in (HTTP 422). */
    suspend fun forkPoll(selector: String): ForkSessionResponse? {
        val reply = send(null) {
            it.url(baseUrl.toHttpUrl().newBuilder().addPathSegments("auth/v4/sessions/forks").addPathSegment(selector).build()).get()
        }
        if (reply.code == 422) return null
        return decode(reply.requireOk())
    }

    /** Refreshes the tokens. Absent fields in the answer keep their old values. */
    suspend fun refresh(uid: String, accessToken: String, refreshToken: String): Tokens {
        val body = buildJsonObject {
            put("ResponseType", "token")
            put("GrantType", "refresh_token")
            put("RefreshToken", refreshToken)
            put("RedirectURI", "https://proton.me")
        }.toString().toRequestBody(JSON_TYPE)
        val reply = send(null) {
            it.url(url("auth/v4/refresh"))
                .header("x-pm-uid", uid)
                .header("Authorization", "Bearer $accessToken")
                .post(body)
        }
        val parsed = decode<RefreshResponse>(reply.requireOk())
        return Tokens(parsed.uid ?: uid, parsed.accessToken ?: accessToken, parsed.refreshToken ?: refreshToken)
    }

    suspend fun getUser(auth: SessionAuth): UserDto =
        decode<UserResponse>(send(auth) { it.url(url("core/v4/users")).get() }.requireOk()).user

    suspend fun getAddresses(auth: SessionAuth): List<AddressDto> {
        val target = baseUrl.toHttpUrl().newBuilder().addPathSegments("core/v4/addresses")
            .addQueryParameter("Page", "0").addQueryParameter("PageSize", "150").build()
        return decode<AddressesResponse>(send(auth) { it.url(target).get() }.requireOk()).addresses
    }

    /** Public keys of any address. Codes 33102 and 33103 (no Proton account) give an empty list. */
    suspend fun getPublicKeys(auth: SessionAuth, email: String): List<PublicKeyEntry> {
        val target = baseUrl.toHttpUrl().newBuilder().addPathSegments("core/v4/keys/all")
            .addQueryParameter("Email", email).addQueryParameter("InternalOnly", "1").build()
        val reply = send(auth) { it.url(target).get() }
        if (!reply.ok && apiError(reply.body)?.code in NO_ACCOUNT_CODES) return emptyList()
        return decode<PublicKeysResponse>(reply.requireOk()).address?.keys.orEmpty()
    }

    /** Revokes the session on the server. */
    suspend fun revoke(auth: SessionAuth) {
        send(auth) { it.url(url("auth/v4")).delete() }.requireOk()
    }

    private fun url(path: String) = baseUrl.toHttpUrl().newBuilder().addPathSegments(path).build()

    private suspend fun send(auth: SessionAuth?, build: (Request.Builder) -> Request.Builder): Reply {
        var credentials = if (auth != null) {
            auth.current() ?: throw AccountApiException(401, null, "No session")
        } else {
            null
        }
        var refreshed = false
        var waited = false
        while (true) {
            val builder = Request.Builder()
                .header("x-pm-appversion", appVersion)
                .header("Accept", "application/vnd.protonmail.v1+json")
            if (credentials != null) {
                builder.header("x-pm-uid", credentials.uid).header("Authorization", "Bearer ${credentials.accessToken}")
            }
            val reply = client.newCall(build(builder).build()).await()
            if (reply.code == 401 && auth != null && credentials != null && !refreshed) {
                refreshed = true
                credentials = auth.refreshAfter401(credentials.accessToken) ?: return reply
            } else if (reply.code == 429 && !waited) {
                waited = true
                sleep(retryAfterMillis(reply.retryAfter))
            } else {
                return reply
            }
        }
    }

    private fun Reply.requireOk(): Reply {
        if (ok) return this
        val error = apiError(body)
        throw AccountApiException(code, error?.code, error?.error ?: "HTTP $code")
    }

    private inline fun <reified T> decode(reply: Reply): T = AccountJson.decodeFromString<T>(reply.body)

    private suspend fun Call.await(): Reply = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        continuation.resume(Reply(it.code, it.body?.string().orEmpty(), it.header("Retry-After")))
                    }
                } catch (e: IOException) {
                    continuation.resumeWithException(e)
                }
            }
        })
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://drive-api.proton.me/"
        private val JSON_TYPE = "application/json".toMediaType()
        private val NO_ACCOUNT_CODES = setOf(33102, 33103)
        private const val DEFAULT_RETRY_AFTER_SECONDS = 5L
        private const val MAX_RETRY_AFTER_SECONDS = 60L

        fun newClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()

        /** The wait for a 429 answer: the Retry-After seconds (default 5), at most 60 seconds. */
        internal fun retryAfterMillis(header: String?): Long {
            val seconds = header?.trim()?.toLongOrNull() ?: DEFAULT_RETRY_AFTER_SECONDS
            return seconds.coerceIn(0L, MAX_RETRY_AFTER_SECONDS) * 1000L
        }

        private fun apiError(body: String): ApiErrorBody? = try {
            AccountJson.decodeFromString<ApiErrorBody>(body)
        } catch (e: Exception) {
            null
        }
    }
}
