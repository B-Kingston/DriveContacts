package dev.drivecontacts.auth

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ForkSignInTest {
    private val key = ByteArray(32) { (it * 7 + 1).toByte() }

    /** Makes a payload like the web page: base64(nonce || ciphertext || tag). */
    private fun payload(plain: String, aad: String = "fork", nonce: ByteArray = ByteArray(12) { it.toByte() }): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad.toByteArray())
        return Base64.getEncoder().encodeToString(nonce + cipher.doFinal(plain.toByteArray()))
    }

    @Test
    fun signInUrlHasTheEncodedPayloadInTheFragment() {
        val url = ForkSignIn.signInUrl("ABC123", key)
        val expectedPayload = "0:ABC123:" + Base64.getEncoder().encodeToString(key) + ":external-drive"
        assertTrue(url.startsWith("https://account.proton.me/desktop/login?app=drive&pv=3#payload="))
        assertEquals(ForkSignIn.encodeUriComponent(expectedPayload), url.substringAfter("#payload="))
    }

    @Test
    fun payloadEncodingFollowsEncodeUriComponent() {
        // Reserved characters, base64 characters and non-ASCII text.
        assertEquals("0%3AA%2FB%2BC%3D%3D%3Aexternal-drive", ForkSignIn.encodeUriComponent("0:A/B+C==:external-drive"))
        assertEquals("a-b_c.d!e~f*g'h(i)j", ForkSignIn.encodeUriComponent("a-b_c.d!e~f*g'h(i)j"))
        assertEquals("%20%C3%A9%E2%82%AC", ForkSignIn.encodeUriComponent(" é€"))
    }

    @Test
    fun newKeyIsRandomAnd32Bytes() {
        val a = ForkSignIn.newKey()
        assertEquals(32, a.size)
        assertTrue(!a.contentEquals(ForkSignIn.newKey()))
    }

    @Test
    fun decryptReturnsKeyPassword() {
        assertEquals("s3cret/+=", ForkSignIn.decryptKeyPassword(payload("""{"type":"default","keyPassword":"s3cret/+=","x":1}"""), key))
    }

    @Test
    fun decryptAcceptsEmptyCiphertextBoundary() {
        // 12 + 16 = 28 bytes is the smallest valid blob. Its plaintext is empty, so the JSON check fails, not the length.
        val blob = payload("")
        assertEquals(28, Base64.getDecoder().decode(blob).size)
        val error = assertThrows(ForkPayloadException::class.java) { ForkSignIn.decryptKeyPassword(blob, key) }
        assertNotEquals("The payload is too short", error.message)
    }

    @Test
    fun decryptRejectsShortBlob() {
        val short = Base64.getEncoder().encodeToString(ByteArray(27))
        val error = assertThrows(ForkPayloadException::class.java) { ForkSignIn.decryptKeyPassword(short, key) }
        assertEquals("The payload is too short", error.message)
    }

    @Test
    fun decryptRejectsTamperedTag() {
        val bytes = Base64.getDecoder().decode(payload("""{"keyPassword":"x"}"""))
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        assertThrows(ForkPayloadException::class.java) {
            ForkSignIn.decryptKeyPassword(Base64.getEncoder().encodeToString(bytes), key)
        }
    }

    @Test
    fun decryptRejectsTamperedCiphertext() {
        val bytes = Base64.getDecoder().decode(payload("""{"keyPassword":"x"}"""))
        bytes[12] = (bytes[12].toInt() xor 1).toByte()
        assertThrows(ForkPayloadException::class.java) {
            ForkSignIn.decryptKeyPassword(Base64.getEncoder().encodeToString(bytes), key)
        }
    }

    @Test
    fun decryptRejectsWrongAadAndWrongKey() {
        assertThrows(ForkPayloadException::class.java) {
            ForkSignIn.decryptKeyPassword(payload("""{"keyPassword":"x"}""", aad = "other"), key)
        }
        assertThrows(ForkPayloadException::class.java) {
            ForkSignIn.decryptKeyPassword(payload("""{"keyPassword":"x"}"""), ByteArray(32))
        }
    }

    @Test
    fun decryptRejectsNonStringKeyPassword() {
        for (json in listOf("""{"keyPassword":12}""", """{"keyPassword":null}""", """{"keyPassword":["a"]}""", """{"type":"x"}""", "[]", "not json")) {
            assertThrows(json, ForkPayloadException::class.java) { ForkSignIn.decryptKeyPassword(payload(json), key) }
        }
    }

    @Test
    fun decryptRejectsInvalidBase64() {
        assertThrows(ForkPayloadException::class.java) { ForkSignIn.decryptKeyPassword("***", key) }
    }
}
