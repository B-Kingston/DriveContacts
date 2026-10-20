package dev.cardvault.drive

import android.content.Context
import android.os.Build
import android.util.Log
import dev.cardvault.BuildConfig
import dev.cardvault.auth.Session as CardvaultSession
import dev.cardvault.auth.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import me.proton.core.domain.entity.UserId
import me.proton.core.network.data.ApiManagerFactory
import me.proton.core.network.data.ApiProvider
import me.proton.core.network.data.ProtonCookieStore
import me.proton.core.network.data.client.ClientIdProviderImpl
import me.proton.core.network.data.client.ClientVersionValidatorImpl
import me.proton.core.network.data.cookie.MemoryCookieStorage
import me.proton.core.network.domain.ApiClient
import me.proton.core.network.domain.client.ClientId
import me.proton.core.network.domain.client.ClientIdProvider
import me.proton.core.network.domain.deviceverification.DeviceVerificationListener
import me.proton.core.network.domain.deviceverification.DeviceVerificationMethods
import me.proton.core.network.domain.deviceverification.DeviceVerificationProvider
import me.proton.core.network.domain.feature.FeatureDisabledListener
import me.proton.core.network.domain.humanverification.HumanVerificationAvailableMethods
import me.proton.core.network.domain.humanverification.HumanVerificationDetails
import me.proton.core.network.domain.humanverification.HumanVerificationListener
import me.proton.core.network.domain.humanverification.HumanVerificationProvider
import me.proton.core.network.domain.scopes.MissingScopeListener
import me.proton.core.network.domain.scopes.MissingScopeResult
import me.proton.core.network.domain.scopes.MissingScopeState
import me.proton.core.network.domain.scopes.Scope
import me.proton.core.network.domain.server.ServerTimeListener
import me.proton.core.network.domain.session.Session
import me.proton.core.network.domain.session.SessionId
import me.proton.core.network.domain.session.SessionListener
import me.proton.core.network.domain.session.SessionProvider
import me.proton.core.util.kotlin.DefaultDispatcherProvider
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import me.proton.core.network.data.NetworkManager as createNetworkManager
import me.proton.core.network.data.NetworkPrefs as createNetworkPrefs

/**
 * Builds the ProtonCore [ApiProvider] without Hilt. The Drive SDK sends all its HTTP requests through it.
 * The session comes from [SessionManager]. ProtonCore adds the app version, the user agent and the auth headers.
 */
class ProtonCoreBridge(context: Context, scope: CoroutineScope, sessions: SessionManager) {
    val apiProvider: ApiProvider

    init {
        val baseUrl = BASE_URL.toHttpUrl()
        val cookieStore = ProtonCookieStore(MemoryCookieStorage(), MemoryCookieStorage())
        val sessionProvider = CardvaultSessionProvider(sessions)
        val factory = ApiManagerFactory(
            baseUrl = baseUrl,
            apiClient = CardvaultApiClient(),
            clientIdProvider = ClientIdProviderImpl(baseUrl, cookieStore),
            serverTimeListener = IgnoreServerTime,
            networkManager = createNetworkManager(context.applicationContext),
            prefs = createNetworkPrefs(context.applicationContext),
            sessionProvider = sessionProvider,
            sessionListener = CardvaultSessionListener(sessions),
            humanVerificationProvider = NoHumanVerificationProvider,
            humanVerificationListener = NoHumanVerificationListener,
            deviceVerificationProvider = NoDeviceVerificationProvider,
            deviceVerificationListener = NoDeviceVerificationListener,
            missingScopeListener = NoMissingScopeListener,
            featureDisabledListener = IgnoreFeatureDisabled,
            cookieStore = cookieStore,
            scope = scope,
            clientVersionValidator = ClientVersionValidatorImpl(),
            dohAlternativesListener = null,
            okHttpClient = OkHttpClient(),
            interceptors = emptySet(),
        )
        apiProvider = ApiProvider(factory, sessionProvider, DefaultDispatcherProvider())
    }

    companion object {
        const val BASE_URL = "https://drive-api.proton.me/"

        /** The value of the User-Agent header. */
        fun userAgent(versionName: String, androidRelease: String) = "Cardvault/$versionName (Android $androidRelease)"
    }
}

private class CardvaultApiClient : ApiClient {
    override val appVersionHeader: String = BuildConfig.PROTON_APP_VERSION
    override val userAgent: String = ProtonCoreBridge.userAgent(BuildConfig.VERSION_NAME, Build.VERSION.RELEASE)
    override val enableDebugLogging: Boolean = false

    override suspend fun shouldUseDoh(): Boolean = false

    override fun forceUpdate(errorMessage: String) {
        Log.w(TAG, "The server asks for an app update: $errorMessage")
    }

    private companion object {
        const val TAG = "CardvaultApi"
    }
}

/** Gives ProtonCore the one session of [SessionManager]. The user id is the Proton user id. */
private class CardvaultSessionProvider(private val sessions: SessionManager) : SessionProvider {
    override suspend fun getSession(sessionId: SessionId?): Session? =
        sessions.session.value?.takeIf { it.uid == sessionId?.id }?.toCore()

    override suspend fun getSessions(): List<Session> = listOfNotNull(sessions.session.value?.toCore())

    override suspend fun getSessionId(userId: UserId?): SessionId? =
        sessions.session.value?.takeIf { userId == null || it.userId == userId.id }?.let { SessionId(it.uid) }

    override suspend fun getUserId(sessionId: SessionId): UserId? =
        sessions.session.value?.takeIf { it.uid == sessionId.id }?.let { UserId(it.userId) }

    private fun CardvaultSession.toCore() =
        Session.Authenticated(UserId(userId), SessionId(uid), accessToken, refreshToken, emptyList())
}

private class CardvaultSessionListener(private val sessions: SessionManager) : SessionListener {
    override suspend fun <T> withLock(sessionId: SessionId?, action: suspend () -> T): T = action()

    override suspend fun requestSession(): Boolean = false

    override suspend fun refreshSession(session: Session): Boolean = sessions.refresh(session.accessToken) != null

    override suspend fun onSessionTokenCreated(userId: UserId?, session: Session) = Unit

    override suspend fun onSessionTokenRefreshed(session: Session) = Unit

    override suspend fun onSessionScopesRefreshed(sessionId: SessionId, scopes: List<String>) = Unit

    override suspend fun onSessionForceLogout(session: Session, httpCode: Int) {
        sessions.invalidate()
    }
}

private object IgnoreServerTime : ServerTimeListener {
    override fun onServerTimeMillisUpdated(epochMillis: Long) = Unit
}

private object IgnoreFeatureDisabled : FeatureDisabledListener {
    override suspend fun onFeatureDisabled(sessionId: SessionId?) = Unit
}

private object NoHumanVerificationProvider : HumanVerificationProvider {
    override suspend fun getHumanVerificationDetails(clientId: ClientId): HumanVerificationDetails? = null
}

private object NoHumanVerificationListener : HumanVerificationListener {
    override suspend fun onHumanVerificationNeeded(
        clientId: ClientId,
        methods: HumanVerificationAvailableMethods,
    ): HumanVerificationListener.HumanVerificationResult = HumanVerificationListener.HumanVerificationResult.Failure

    override suspend fun onHumanVerificationInvalid(clientId: ClientId) = Unit
}

private object NoDeviceVerificationProvider : DeviceVerificationProvider {
    override suspend fun getSolvedChallenge(sessionId: SessionId?): String? = null

    override suspend fun getSolvedChallenge(challengePayload: String): String? = null

    override suspend fun setSolvedChallenge(sessionId: SessionId, challengePayload: String, solved: String) = Unit
}

private object NoDeviceVerificationListener : DeviceVerificationListener {
    override suspend fun onDeviceVerification(
        sessionId: SessionId,
        methods: DeviceVerificationMethods,
    ): DeviceVerificationListener.DeviceVerificationResult = DeviceVerificationListener.DeviceVerificationResult.Failure
}

private object NoMissingScopeListener : MissingScopeListener {
    private val events = MutableSharedFlow<MissingScopeState>()
    override val state: SharedFlow<MissingScopeState> = events.asSharedFlow()

    override suspend fun onMissingScope(userId: UserId, scopes: List<Scope>): MissingScopeResult = MissingScopeResult.Failure

    override suspend fun onMissingScopeSuccess() = Unit

    override suspend fun onMissingScopeFailure() = Unit
}
