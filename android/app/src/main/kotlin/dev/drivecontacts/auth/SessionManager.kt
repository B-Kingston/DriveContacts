package dev.drivecontacts.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable

/** The session secrets. [cacheKey] is the 32-byte key of the Drive SDK cache. */
data class Session(
    val uid: String,
    val accessToken: String,
    val refreshToken: String,
    val userId: String,
    val email: String,
    val keyPassword: String,
    val clientUid: String,
    val cacheKey: ByteArray,
) {
    /** Without the secrets. */
    override fun toString() = "Session(uid=$uid, userId=$userId)"
}

/** The session file cannot be read now, and it may be fine later (for example, the Keystore is not ready). */
class SessionStoreException(cause: Throwable) : Exception("The session file cannot be read: ${cause.javaClass.simpleName}", cause)

/** Encrypts and decrypts the bytes of the session file. */
interface SecretBox {
    fun seal(plain: ByteArray): ByteArray

    /**
     * Throws [AEADBadTagException] when the bytes are not valid (too short, changed, or made with another key)
     * and [KeyPermanentlyInvalidatedException] when the key is gone for good.
     * Any other exception means a failure of the key store. The bytes can still be valid.
     */
    fun open(sealed: ByteArray): ByteArray

    /** Deletes the key. The next [seal] makes a new key. */
    fun reset()
}

/** AES-256-GCM with a key in the Android Keystore. The file bytes are iv(12) || ciphertext || tag(16). */
class KeystoreSecretBox(private val alias: String = DEFAULT_ALIAS) : SecretBox {
    override fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(AAD)
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        if (sealed.size < IV_SIZE + TAG_SIZE) throw AEADBadTagException("The session file is too short")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_SIZE * 8, sealed, 0, IV_SIZE))
        cipher.updateAAD(AAD)
        return cipher.doFinal(sealed, IV_SIZE, sealed.size - IV_SIZE)
    }

    override fun reset() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        const val DEFAULT_ALIAS = "drivecontacts-session"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
        private const val TAG_SIZE = 16
        private val AAD = "drivecontacts-session".toByteArray(Charsets.UTF_8)
    }
}

@Serializable
private class SessionRecord(
    val uid: String,
    val accessToken: String,
    val refreshToken: String,
    val userId: String,
    val email: String,
    val keyPassword: String,
    val clientUid: String,
    val cacheKey: String,
)

/**
 * Keeps the session in memory and in `session.bin`. Only one refresh runs at a time.
 * A refresh answer with HTTP status 400, 401 or 422 deletes the session and emits on [signedOut].
 * The memory is the truth: when the file cannot be written, the session stays in memory and the next
 * [update] or [refresh] writes the file again.
 */
class SessionManager(
    private val file: File,
    private val box: SecretBox,
    private val accountApi: AccountApi,
) : SessionAuth {
    constructor(context: Context, accountApi: AccountApi) :
        this(File(context.filesDir, "session.bin"), KeystoreSecretBox(), accountApi)

    private val mutex = Mutex()
    private val state = MutableStateFlow<Session?>(null)
    private val signedOutEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** The current session. It is null before [load] and after sign-out. */
    val session: StateFlow<Session?> = state.asStateFlow()

    /** Emits when the server rejected the refresh token and the session was deleted. */
    val signedOut: SharedFlow<Unit> = signedOutEvents.asSharedFlow()

    /**
     * Reads the session file. A file that is corrupt, or that the key cannot open, is deleted.
     * @throws SessionStoreException when the key store fails in another way. The file stays.
     */
    suspend fun load(): Session? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val loaded = readFile()
            state.value = loaded
            loaded
        }
    }

    suspend fun save(session: Session) {
        mutex.withLock {
            state.value = session
            persist(session)
        }
    }

    /** Changes the session and saves it. Returns null when there is no session. */
    suspend fun update(transform: (Session) -> Session): Session? = mutex.withLock {
        val current = state.value ?: return@withLock null
        val next = transform(current)
        state.value = next
        persist(next)
        next
    }

    /** Deletes the session file and forgets the session. */
    suspend fun clear() {
        mutex.withLock { clearLocked() }
    }

    /**
     * Deletes the session file and the key of the file, and forgets the session. Use it when the file cannot be
     * read and the user accepts to lose it. A failure to delete the key is logged and ignored.
     */
    suspend fun reset() {
        mutex.withLock {
            clearLocked()
            withContext(Dispatchers.IO) {
                try {
                    box.reset()
                } catch (e: Exception) {
                    Log.w(TAG, "The session key was not deleted: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    /** Clears the session and tells the observers of [signedOut]. */
    suspend fun invalidate() {
        mutex.withLock { clearLocked() }
        signedOutEvents.tryEmit(Unit)
    }

    override suspend fun current(): Credentials? = state.value?.let { Credentials(it.uid, it.accessToken) }

    override suspend fun refreshAfter401(rejectedAccessToken: String): Credentials? =
        refresh(rejectedAccessToken)?.let { Credentials(it.uid, it.accessToken) }

    /**
     * Refreshes the tokens. When [rejectedAccessToken] is not the current token, another caller already refreshed
     * and the current session is returned. Returns null when there is no session or the refresh failed.
     */
    suspend fun refresh(rejectedAccessToken: String?): Session? = mutex.withLock {
        val current = state.value ?: return@withLock null
        if (rejectedAccessToken != null && current.accessToken != rejectedAccessToken) return@withLock current
        // The server rotates the refresh token. After its answer, the new tokens must not get lost,
        // so a cancel of the caller must not stop this one HTTP exchange or the commit. It has no wait for a 429.
        withContext(NonCancellable) {
            try {
                val tokens = accountApi.refresh(current.uid, current.accessToken, current.refreshToken)
                val next = current.copy(uid = tokens.uid, accessToken = tokens.accessToken, refreshToken = tokens.refreshToken)
                state.value = next
                persist(next)
                next
            } catch (e: AccountApiException) {
                if (e.httpCode in SIGN_OUT_CODES) {
                    clearLocked()
                    signedOutEvents.tryEmit(Unit)
                }
                null
            } catch (e: IOException) {
                null
            } catch (e: SerializationException) {
                null
            }
        }
    }

    /** Writes the file. A failure is logged and ignored: the session stays in memory. */
    private suspend fun persist(session: Session) {
        try {
            withContext(Dispatchers.IO) { writeFile(session) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "The session file was not saved: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun clearLocked() {
        withContext(Dispatchers.IO) {
            file.delete()
            File(file.parentFile, file.name + ".tmp").delete()
        }
        state.value = null
    }

    private fun readFile(): Session? {
        if (!file.exists()) return null
        val plain = try {
            box.open(file.readBytes())
        } catch (e: AEADBadTagException) {
            file.delete()
            return null
        } catch (e: KeyPermanentlyInvalidatedException) {
            file.delete()
            return null
        } catch (e: Exception) {
            throw SessionStoreException(e)
        }
        return try {
            val record = AccountJson.decodeFromString<SessionRecord>(String(plain, Charsets.UTF_8))
            Session(
                record.uid, record.accessToken, record.refreshToken, record.userId, record.email,
                record.keyPassword, record.clientUid, Base64.getDecoder().decode(record.cacheKey),
            )
        } catch (e: SerializationException) {
            file.delete()
            null
        } catch (e: IllegalArgumentException) {
            file.delete()
            null
        } finally {
            plain.fill(0)
        }
    }

    private fun writeFile(session: Session) {
        val record = SessionRecord(
            session.uid, session.accessToken, session.refreshToken, session.userId, session.email,
            session.keyPassword, session.clientUid, Base64.getEncoder().encodeToString(session.cacheKey),
        )
        val plain = AccountJson.encodeToString(SessionRecord.serializer(), record).toByteArray(Charsets.UTF_8)
        val sealed = try {
            box.seal(plain)
        } finally {
            plain.fill(0)
        }
        val temp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temp).use {
            it.write(sealed)
            it.fd.sync()
        }
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private companion object {
        const val TAG = "DriveContactsSession"

        /** The answers that mean "this refresh token is not valid". Others (403, 404, 429, ...) keep the session. */
        val SIGN_OUT_CODES = setOf(400, 401, 422)
    }
}
