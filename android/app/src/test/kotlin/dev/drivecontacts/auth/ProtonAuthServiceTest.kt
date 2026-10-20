package dev.drivecontacts.auth

import dev.drivecontacts.AuthState
import java.io.File
import java.security.KeyStoreException
import javax.crypto.AEADBadTagException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtonAuthServiceTest {
    private val dir = File.createTempFile("drivecontacts-auth", "").also { it.delete(); it.mkdirs() }
    private val file = File(dir, "session.bin")

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private class FakeBox : SecretBox {
        var failOpen: Exception? = null
        var resets = 0

        override fun seal(plain: ByteArray) = byteArrayOf(0x7F) + plain.reversedArray()

        override fun open(sealed: ByteArray): ByteArray {
            failOpen?.let { throw it }
            if (sealed.isEmpty() || sealed[0] != 0x7F.toByte()) throw AEADBadTagException("bad")
            return sealed.copyOfRange(1, sealed.size).reversedArray()
        }

        override fun reset() {
            resets++
        }
    }

    private val box = FakeBox()
    private val session = Session("uid", "access", "refresh", "user", "me@proton.me", "pw", "client", ByteArray(32))

    private val server = FakeServer { request ->
        if (request.url.encodedPath.endsWith("/forks")) reply(200, """{"Code":1000,"Selector":"sel","UserCode":"UC"}""") else reply(422, "{}")
    }

    @Test
    fun aKeyStoreErrorThatStaysFirstKeepsTheFileThenOffersAFreshStart() = runTest {
        SessionManager(file, box, AccountApi(server.client, sleep = {})).save(session)
        box.failOpen = KeyStoreException("Invalid key blob")
        val api = AccountApi(server.client, sleep = {})
        val service = ProtonAuthService(api, SessionManager(file, box, api), backgroundScope)

        val first = service.state.first { it is AuthState.Failed } as AuthState.Failed
        assertTrue(first.message.endsWith("Try again."))
        assertTrue(file.exists())

        service.startSignIn()
        val second = service.state.first { it is AuthState.Failed && it.message != first.message } as AuthState.Failed
        assertTrue(second.message.contains("remove"))
        assertTrue(file.exists())
        assertEquals(0, box.resets)

        service.startSignIn()
        service.state.first { it is AuthState.WaitingForBrowser }
        assertFalse(file.exists())
        assertEquals(1, box.resets)
    }

    @Test
    fun aKeyStoreErrorThatClearsOnRetryRestoresTheSession() = runTest {
        SessionManager(file, box, AccountApi(server.client, sleep = {})).save(session)
        box.failOpen = KeyStoreException("not ready")
        val api = AccountApi(server.client, sleep = {})
        val service = ProtonAuthService(api, SessionManager(file, box, api), backgroundScope)
        service.state.first { it is AuthState.Failed }
        box.failOpen = null
        service.startSignIn()
        assertEquals(AuthState.SignedIn("me@proton.me", "user"), service.state.first { it is AuthState.SignedIn })
        assertEquals(0, box.resets)
    }
}
