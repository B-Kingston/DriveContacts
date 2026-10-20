package dev.drivecontacts.auth

import android.content.Context
import android.util.Log
import dev.drivecontacts.AuthService
import dev.drivecontacts.AuthState
import java.io.IOException
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
class ProtonAuthService internal constructor(
    override val accountApi: AccountApi,
    override val sessions: SessionManager,
    private val scope: CoroutineScope,
) : AuthService, DriveAccess {
    constructor(context: Context, scope: CoroutineScope) : this(AccountApi(), context.applicationContext, scope)

    private constructor(accountApi: AccountApi, appContext: Context, scope: CoroutineScope) :
        this(accountApi, SessionManager(appContext, accountApi), scope)

    private val mutableState = MutableStateFlow<AuthState>(AuthState.Loading)
    override val state: StateFlow<AuthState> = mutableState.asStateFlow()

    private val hooks = CopyOnWriteArrayList<suspend () -> Unit>()
    private val keysMutex = Mutex()
    private var keys: UnlockedAccount? = null
    private var keysUnlockedAt: TimeMark? = null
    @Volatile private var restoreFailures = 0
    private var signInJob: Job? = null

    init {
        scope.launch { restore() }
        scope.launch { sessions.signedOut.collect { onSessionLost() } }
    }

    override fun addSignOutHook(hook: suspend () -> Unit) {
        hooks.add(hook)
    }

    /**
     * The unlocked keys. They are cached for [KEYS_MAX_AGE], so that new address keys show up.
     * When the keys cannot be unlocked while the user is signed in, the key password is not valid any more
     * (for example, after a password change). Then the session is invalidated and the user signs in again.
     */
    override suspend fun accountKeys(): UnlockedAccount = keysMutex.withLock {
        val cached = keys
        val unlockedAt = keysUnlockedAt
        if (cached != null && unlockedAt != null && unlockedAt.elapsedNow() < KEYS_MAX_AGE) return@withLock cached
        val session = sessions.session.value ?: throw IllegalStateException("Not signed in")
        val user = accountApi.getUser(sessions)
        val addresses = accountApi.getAddresses(sessions)
        val account = try {
            withContext(Dispatchers.Default) { KeyUnlocker.unlock(session.keyPassword, user, addresses) }
        } catch (e: KeyUnlockException) {
            if (mutableState.value is AuthState.SignedIn) {
                Log.w(TAG, "The account keys cannot be unlocked. The session is not valid any more.")
                sessions.invalidate()
            }
            throw e
        }
        keys = account
        keysUnlockedAt = TimeSource.Monotonic.markNow()
        account
    }

    override fun startSignIn() {
        if (mutableState.value is AuthState.SignedIn) return
        when (restoreFailures) {
            0 -> {
                signInJob?.cancel()
                signInJob = scope.launch { runSignIn() }
            }
            1 -> {
                // The session file could not be read. Read it again before anything is deleted.
                mutableState.value = AuthState.Loading
                scope.launch { restore() }
            }
            else -> {
                // The file cannot be read again. The user accepted to remove it and to sign in again.
                restoreFailures = 0
                signInJob = scope.launch {
                    sessions.reset()
                    runSignIn()
                }
            }
        }
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
        revokeBestEffort()
        runHooks()
        sessions.clear()
        forgetKeys()
        mutableState.value = AuthState.SignedOut
    }

    /**
     * Reads the session file. It never throws. When the key store fails, the state is [AuthState.Failed] and the
     * file stays. The next [startSignIn] reads the file again. When that fails too, the state says that the saved
     * sign-in is removed by the next [startSignIn], which then deletes the file and the key and signs in anew.
     */
    private suspend fun restore() {
        val next = try {
            val session = sessions.load()
            when {
                session == null -> AuthState.SignedOut
                session.email.isEmpty() -> {
                    // A sign-in did not finish. The session has no email and no key check.
                    discardPartialSession()
                    AuthState.SignedOut
                }
                else -> AuthState.SignedIn(session.email, session.userId)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "The session restore failed: ${e.javaClass.simpleName}")
            restoreFailures++
            AuthState.Failed(
                if (restoreFailures == 1) {
                    "The saved sign-in could not be read. Try again."
                } else {
                    "The saved sign-in still cannot be read. Try again to remove it and sign in with Proton anew."
                },
            )
        }
        if (next !is AuthState.Failed) restoreFailures = 0
        mutableState.compareAndSet(AuthState.Loading, next)
    }

    private suspend fun onSessionLost() {
        runHooks()
        forgetKeys()
        mutableState.value = AuthState.SignedOut
    }

    private suspend fun forgetKeys() {
        keysMutex.withLock {
            keys = null
            keysUnlockedAt = null
        }
    }

    /** Revokes the session on the server. It waits at most [REVOKE_TIMEOUT_MS] and ignores failures. */
    private suspend fun revokeBestEffort() {
        if (sessions.session.value == null) return
        try {
            withTimeoutOrNull(REVOKE_TIMEOUT_MS) { accountApi.revoke(sessions) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "The session revoke failed: ${e.javaClass.simpleName}")
        }
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

    /** Revokes the session of an unfinished sign-in and deletes it. It runs to the end, also when cancelled. */
    private suspend fun discardPartialSession() {
        withContext(NonCancellable) {
            revokeBestEffort()
            sessions.clear()
            forgetKeys()
        }
    }

    private suspend fun runSignIn() {
        try {
            val fork = accountApi.forkInit()
            val key = ForkSignIn.newKey()
            val userId = try {
                mutableState.value = AuthState.WaitingForBrowser(ForkSignIn.signInUrl(fork.userCode, key))
                val forkSession = pollFork(fork.selector) ?: run {
                    mutableState.value = AuthState.Failed("The sign-in timed out. Try again.")
                    return
                }
                // Save the forked session first. When a later step fails, failSignIn can revoke it.
                sessions.save(
                    Session(
                        uid = forkSession.uid,
                        accessToken = forkSession.accessToken,
                        refreshToken = forkSession.refreshToken,
                        userId = forkSession.userId,
                        email = "",
                        keyPassword = "",
                        clientUid = UUID.randomUUID().toString(),
                        cacheKey = ByteArray(CACHE_KEY_SIZE).also { SecureRandom().nextBytes(it) },
                    ),
                )
                val keyPassword = ForkSignIn.decryptKeyPassword(forkSession.payload, key)
                sessions.update { it.copy(keyPassword = keyPassword) }
                forkSession.userId
            } finally {
                key.fill(0)
            }
            val account = accountKeys()
            val email = account.defaultAddress.email
            sessions.update { it.copy(email = email) }
            mutableState.value = AuthState.SignedIn(email, userId)
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
        discardPartialSession()
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
        const val TAG = "DriveContactsAuth"
        const val CACHE_KEY_SIZE = 32
        const val REVOKE_TIMEOUT_MS = 10_000L
        val KEYS_MAX_AGE = 1.hours
        val POLL_INTERVAL = 5.seconds
        val MAX_POLL_TIME = 10.minutes
    }
}
