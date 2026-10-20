package dev.cardvault.drive

import android.content.Context
import android.util.Log
import dev.cardvault.BuildConfig
import dev.cardvault.auth.DriveAccess
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.proton.core.domain.entity.UserId
import me.proton.drive.sdk.LoggerProvider
import me.proton.drive.sdk.ProtonDriveClient
import me.proton.drive.sdk.ProtonDriveSdk
import me.proton.drive.sdk.entity.ClientCreateRequest

/**
 * Makes one [ProtonDriveClient] for the signed-in session and keeps it until sign-out.
 * The client is closed by the sign-out hook of [access].
 */
class DriveClientProvider(
    context: Context,
    private val scope: CoroutineScope,
    private val access: DriveAccess,
) {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private var client: ProtonDriveClient? = null
    private var bridge: ProtonCoreBridge? = null

    init {
        access.addSignOutHook { close() }
    }

    /** Returns the client. It makes the client on the first call after sign-in. */
    suspend fun client(): ProtonDriveClient = mutex.withLock {
        client?.let { return@withLock it }
        val session = access.sessions.session.value ?: throw IllegalStateException("Not signed in")
        val userAddressResolver = AccountUserAddressResolver(access)
        val publicAddressResolver = AccountPublicAddressResolver(access)
        val coreBridge = bridge ?: ProtonCoreBridge(appContext, scope, access.sessions).also { bridge = it }
        val created = ProtonDriveSdk.protonDriveClientCreate(
            coroutineScope = scope,
            userId = UserId(session.userId),
            apiProvider = coreBridge.apiProvider,
            request = ClientCreateRequest(
                baseUrl = ProtonCoreBridge.BASE_URL,
                loggerProvider = ProtonDriveSdk.loggerProvider { level, category, message -> log(level, category, message) },
                cachePath = File(appContext.filesDir, CACHE_FILE).path,
                uid = session.clientUid,
                cacheEncryptionKey = session.cacheKey,
            ),
            userAddressResolver = userAddressResolver,
            publicAddressResolver = publicAddressResolver,
            featureEnabled = { false },
        )
        client = created
        created
    }

    /** Closes the client. The next call of [client] makes a new one. */
    suspend fun close() {
        mutex.withLock {
            val old = client
            client = null
            bridge = null
            if (old != null) {
                withContext(Dispatchers.IO) { old.close() }
            }
        }
    }

    /** Deletes the Drive SDK cache files. Call it after [close]. */
    suspend fun deleteCache() {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                for (suffix in listOf("", "-wal", "-shm", "-journal")) {
                    File(appContext.filesDir, CACHE_FILE + suffix).delete()
                }
            }
        }
    }

    private fun log(level: LoggerProvider.Level, category: String, message: String) {
        when (level) {
            LoggerProvider.Level.VERBOSE -> if (BuildConfig.DEBUG) Log.v(TAG, "$category: $message")
            LoggerProvider.Level.DEBUG -> if (BuildConfig.DEBUG) Log.d(TAG, "$category: $message")
            LoggerProvider.Level.INFO -> Log.i(TAG, "$category: $message")
            LoggerProvider.Level.WARN -> Log.w(TAG, "$category: $message")
            LoggerProvider.Level.ERROR -> Log.e(TAG, "$category: $message")
        }
    }

    private companion object {
        const val TAG = "CardvaultDrive"
        const val CACHE_FILE = "drive-cache.sqlite"
    }
}
