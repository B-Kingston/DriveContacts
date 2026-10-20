package dev.cardvault.auth

import java.io.File
import java.security.GeneralSecurityException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionManagerTest {
    private val dir = File.createTempFile("cardvault-session", "").also { it.delete(); it.mkdirs() }
    private val file = File(dir, "session.bin")

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    /** Reverses and marks the bytes. It rejects bytes that it did not make. */
    private object FakeBox : SecretBox {
        override fun seal(plain: ByteArray) = byteArrayOf(0x7F) + plain.reversedArray()
        override fun open(sealed: ByteArray): ByteArray {
            if (sealed.isEmpty() || sealed[0] != 0x7F.toByte()) throw GeneralSecurityException("bad")
            return sealed.copyOfRange(1, sealed.size).reversedArray()
        }
    }

    private val start = Session("uid", "access-1", "refresh-1", "user", "me@proton.me", "pw", "client", ByteArray(32) { it.toByte() })

    private fun manager(server: FakeServer) = SessionManager(file, FakeBox, AccountApi(server.client, sleep = {}))

    private fun refreshAnswer(n: Int) = reply(200, """{"Code":1000,"AccessToken":"access-$n","RefreshToken":"refresh-$n","UID":"uid"}""")

    @Test
    fun savedSessionIsLoadedAgainAndNotStoredAsPlainText() = runTest {
        manager(FakeServer { refreshAnswer(2) }).save(start)
        assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains("access-1"))
        val loaded = manager(FakeServer { refreshAnswer(2) }).load()!!
        val none = ByteArray(0)
        assertEquals(start.copy(cacheKey = none), loaded.copy(cacheKey = none))
        assertArrayEquals(start.cacheKey, loaded.cacheKey)
        assertFalse(File(dir, "session.bin.tmp").exists())
    }

    @Test
    fun unreadableFileIsDeletedAndGivesNoSession() = runTest {
        file.writeBytes(byteArrayOf(1, 2, 3))
        assertNull(manager(FakeServer { refreshAnswer(2) }).load())
        assertFalse(file.exists())
    }

    @Test
    fun clearDeletesTheFile() = runTest {
        val sessions = manager(FakeServer { refreshAnswer(2) })
        sessions.save(start)
        sessions.clear()
        assertNull(sessions.session.value)
        assertFalse(file.exists())
    }

    @Test
    fun refreshStoresNewTokens() = runTest {
        val sessions = manager(FakeServer { refreshAnswer(2) })
        sessions.save(start)
        val next = sessions.refresh("access-1")!!
        assertEquals("access-2", next.accessToken)
        assertEquals("access-2", manager(FakeServer { refreshAnswer(3) }).load()!!.accessToken)
    }

    @Test
    fun parallelRefreshesForTheSameRejectedTokenRunOnce() = runTest {
        val calls = AtomicInteger()
        val sessions = manager(FakeServer { calls.incrementAndGet(); refreshAnswer(2) })
        sessions.save(start)
        val results = (1..5).map { async { sessions.refreshAfter401("access-1") } }.awaitAll()
        assertEquals(1, calls.get())
        assertTrue(results.all { it?.accessToken == "access-2" })
    }

    @Test
    fun clientErrorOnRefreshSignsOut() = runTest {
        val sessions = manager(FakeServer { reply(400, """{"Code":10013,"Error":"invalid refresh token"}""") })
        sessions.save(start)
        val event = async(start = CoroutineStart.UNDISPATCHED) { sessions.signedOut.first() }
        assertNull(sessions.refresh("access-1"))
        event.await()
        assertNull(sessions.session.value)
        assertFalse(file.exists())
    }

    @Test
    fun rateLimitAndServerErrorsKeepTheSession() = runTest {
        for (code in listOf(429, 500, 503)) {
            val sessions = manager(FakeServer { reply(code, """{"Code":1,"Error":"x"}""") })
            sessions.save(start)
            assertNull(sessions.refresh("access-1"))
            assertNotNull(sessions.session.value)
            assertTrue(file.exists())
        }
    }

    @Test
    fun updateChangesAndSavesTheSession() = runTest {
        val sessions = manager(FakeServer { refreshAnswer(2) })
        assertNull(sessions.update { it.copy(email = "x") })
        sessions.save(start.copy(email = ""))
        sessions.update { it.copy(email = "new@proton.me") }
        assertEquals("new@proton.me", manager(FakeServer { refreshAnswer(2) }).load()!!.email)
    }
}
