package dev.cardvault.auth

import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Thrown when the fork payload is not valid. */
class ForkPayloadException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Session-fork sign-in (docs/spec.md section 9): sign-in URL and payload decryption. */
object ForkSignIn {
    const val ACCOUNT_LOGIN_URL = "https://account.proton.me/desktop/login?app=drive&pv=3"
    const val CLIENT_ID = "external-drive"
    const val KEY_SIZE = 32
    private const val NONCE_SIZE = 12
    private const val TAG_SIZE = 16
    private val AAD = "fork".toByteArray(Charsets.UTF_8)
    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()"

    /** Makes a new random key for one sign-in. */
    fun newKey(): ByteArray = ByteArray(KEY_SIZE).also { SecureRandom().nextBytes(it) }

    /** Returns the URL that the user opens in the browser. */
    fun signInUrl(userCode: String, key: ByteArray): String {
        val payload = "0:" + userCode + ":" + Base64.getEncoder().encodeToString(key) + ":" + CLIENT_ID
        return "$ACCOUNT_LOGIN_URL#payload=" + encodeUriComponent(payload)
    }

    /**
     * Decrypts the fork payload and returns `keyPassword`.
     * The payload is base64 of nonce(12) || ciphertext || tag(16). The cipher is AES-256-GCM with AAD "fork".
     */
    fun decryptKeyPassword(payloadBase64: String, key: ByteArray): String {
        val blob = try {
            Base64.getDecoder().decode(payloadBase64)
        } catch (e: IllegalArgumentException) {
            throw ForkPayloadException("The payload is not base64", e)
        }
        if (blob.size < NONCE_SIZE + TAG_SIZE) throw ForkPayloadException("The payload is too short")
        val plain = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_SIZE * 8, blob, 0, NONCE_SIZE))
            cipher.updateAAD(AAD)
            cipher.doFinal(blob, NONCE_SIZE, blob.size - NONCE_SIZE)
        } catch (e: GeneralSecurityException) {
            throw ForkPayloadException("The payload cannot be decrypted", e)
        }
        return try {
            val value = (Json.parseToJsonElement(String(plain, Charsets.UTF_8)) as? JsonObject)?.get("keyPassword")
            if (value is JsonPrimitive && value.isString) value.content
            else throw ForkPayloadException("The payload has no keyPassword string")
        } catch (e: ForkPayloadException) {
            throw e
        } catch (e: Exception) {
            throw ForkPayloadException("The payload is not valid JSON", e)
        } finally {
            plain.fill(0)
        }
    }

    /** Percent-encoding with the rules of JavaScript `encodeURIComponent`. */
    fun encodeUriComponent(text: String): String {
        val out = StringBuilder()
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            val c = (byte.toInt() and 0xFF).toChar()
            if (UNRESERVED.indexOf(c) >= 0) {
                out.append(c)
            } else {
                out.append('%')
                out.append("0123456789ABCDEF"[(byte.toInt() shr 4) and 0xF])
                out.append("0123456789ABCDEF"[byte.toInt() and 0xF])
            }
        }
        return out.toString()
    }
}
