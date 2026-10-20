package dev.cardvault.auth

import android.content.Context
import android.util.Log
import dev.cardvault.AuthService
import dev.cardvault.AuthState
import java.io.IOException
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException

/** Session-fork sign-in, session restore and sign-out (docs/spec.md section 9). */
class ProtonAuthService(
    context: Context,
    private val scope: CoroutineScope,
) : AuthService, DriveAccess {
    override val accountApi = AccountApi()
    override val sessions = SessionManager(context.applicationContext, accountApi)

    private val mutableState = MutableStateFlow<AuthState>(AuthState.Loading)
    override val state: StateFlow<AuthState> = mutableState.asStateFlow()

    private val hooks = CopyOnWriteArrayList<suspend () -> Unit>()
    private val keysMutex = Mutex()
    private var keys: UnlockedAccount? = null
    private var signInJob: Job? = null

    init {
        scope.launch { restore() }
        scope.launch { sessions.signedOut.collect { onSessionLost() } }
    }

    override fun addSignOutHook(hook: suspend () -> Unit) {
        hooks.add(hook)
    }

    override suspend fun accountKeys(): UnlockedAccount = keysMutex.withLock {
        keys?.let { return@withLock it }
        val session = sessions.session.value ?: throw IllegalStateException("Not signed in")
        val user = accountApi.getUser(sessions)
        val addresses = accountApi.getAddresses(sessions)
        val account = withContext(Dispatchers.Default) { KeyUnlocker.unlock(session.keyPassword, user, addresses) }
        keys = account
        account
    }

    override fun startSignIn() {
        if (mutableState.value is AuthState.SignedIn) return
        signInJob?.cancel()
        signInJob = scope.launch { runSignIn() }
    }

    override fun cancelSignIn() {
        if (mutableState.value is AuthState.SignedIn) return
        val job = signInJob
        signInJob = null
        mutableState.value = AuthState.SignedOut
        scope.launch {
            job?.cancel()
            job?.join()
            if (mutableState.value !is AuthState.SignedIn) discardPartialSession()
        }
    }

    override suspend fun signOut() {
        signInJob?.cancel()
        signInJob = null
        if (sessions.session.value != null) {
            try {
                withTimeoutOrNull(REVOKE_TIMEOUT_MS) { accountApi.revoke(sessions) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "The session revoke failed: ${e.javaClass.simpleName}")
            }
        }
        runHooks()
        sessions.clear()
        keysMutex.withLock { keys = null }
        mutableState.value = AuthState.SignedOut
    }

    private suspend fun restore() {
        val session = sessions.load()
        val next = when {
            session == null -> AuthState.SignedOut
            session.email.isEmpty() -> {
                // A sign-in did not finish. The session has no email and no key check.
                sessions.clear()
                AuthState.SignedOut
            }
            else -> AuthState.SignedIn(session.email, session.userId)
        }
        mutableState.compareAndSet(AuthState.Loading, next)
    }

    private suspend fun onSessionLost() {
        runHooks()
        keysMutex.withLock { keys = null }
        mutableState.value = AuthState.SignedOut
    }

    private suspend fun runHooks() {
        for (hook in hooks) {
            try {
                hook()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "A sign-out action failed: ${e.javaClass.simpleName}")
            }
        }
    }

    private suspend fun discardPartialSession() {
        sessions.clear()
        keysMutex.withLock { keys = null }
    }

    private suspend fun runSignIn() {
        try {
            val fork = accountApi.forkInit()
            val key = ForkSignIn.newKey()
            mutableState.value = AuthState.WaitingForBrowser(ForkSignIn.signInUrl(fork.userCode, key))
            val forkSession = pollFork(fork.selector) ?: run {
                mutableState.value = AuthState.Failed("The sign-in timed out. Try again.")
                return
            }
            val keyPassword = ForkSignIn.decryptKeyPassword(forkSession.payload, key)
            key.fill(0)
            sessions.save(
                Session(
                    uid = forkSession.uid,
                    accessToken = forkSession.accessToken,
                    refreshToken = forkSession.refreshToken,
                    userId = forkSession.userId,
                    email = "",
                    keyPassword = keyPassword,
                    clientUid = UUID.randomUUID().toString(),
                    cacheKey = ByteArray(CACHE_KEY_SIZE).also { SecureRandom().nextBytes(it) },
                ),
            )
            val account = accountKeys()
            val email = account.defaultAddress.email
            sessions.update { it.copy(email = email) }
            mutableState.value = AuthState.SignedIn(email, forkSession.userId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failSignIn(e)
        }
    }

    /** Polls every 5 seconds for at most 10 minutes. Returns null on timeout. */
    private suspend fun pollFork(selector: String): ForkSessionResponse? {
        delay(POLL_INTERVAL)
        val start = TimeSource.Monotonic.markNow()
        while (start.elapsedNow() < MAX_POLL_TIME) {
            try {
                accountApi.forkPoll(selector)?.let { return it }
            } catch (e: IOException) {
                // A network error is not final. The next poll can work.
            }
            delay(POLL_INTERVAL)
        }
        return null
    }

    private suspend fun failSignIn(error: Exception) {
        Log.w(TAG, "The sign-in failed: ${error.javaClass.simpleName}")
        withContext(kotlinx.coroutines.NonCancellable) { discardPartialSession() }
        val message = when (error) {
            is ForkPayloadException -> "The sign-in answer is not valid."
            is KeyUnlockException -> "The account keys could not be unlocked."
            is AccountApiException -> "Proton answered with an error (${error.httpCode}): ${error.message}"
            is IOException -> "No connection to Proton. Check the network and try again."
            is SerializationException -> "Proton gave an answer that the app does not understand."
            else -> "The sign-in failed."
        }
        mutableState.value = AuthState.Failed(message)
    }

    private companion object {
        const val TAG = "CardvaultAuth"
        const val CACHE_KEY_SIZE = 32
        const val REVOKE_TIMEOUT_MS = 10_000L
        val POLL_INTERVAL = 5.seconds
        val MAX_POLL_TIME = 10.minutes
    }
}
