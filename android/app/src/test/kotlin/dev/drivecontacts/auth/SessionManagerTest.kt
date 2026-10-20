package dev.drivecontacts.auth

import java.io.File
import java.security.KeyStoreException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.AEADBadTagException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionManagerTest {
    private val dir = File.createTempFile("drivecontacts-session", "").also { it.delete(); it.mkdirs() }
    private val file = File(dir, "session.bin")

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    /** Reverses and marks the bytes. It rejects bytes that it did not make. [failSeal] and [failOpen] simulate a broken key store. */
    private class FakeBox : SecretBox {
        var failSeal: Exception? = null
        var failOpen: Exception? = null
        var resets = 0

        override fun seal(plain: ByteArray): ByteArray {
            failSeal?.let { throw it }
            return byteArrayOf(0x7F) + plain.reversedArray()
        }

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

    private val start = Session("uid", "access-1", "refresh-1", "user", "me@proton.me", "pw", "client", ByteArray(32) { it.toByte() })

    private fun manager(server: FakeServer) = SessionManager(file, box, AccountApi(server.client, sleep = {}))

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
    fun fileIsKeptWhenTheKeyStoreFailsInAnotherWay() = runTest {
        manager(FakeServer { refreshAnswer(2) }).save(start)
        box.failOpen = KeyStoreException("not ready")
        assertThrows(SessionStoreException::class.java) { kotlinx.coroutines.runBlocking { manager(FakeServer { refreshAnswer(2) }).load() } }
        assertTrue(file.exists())
        box.failOpen = null
        assertEquals("access-1", manager(FakeServer { refreshAnswer(2) }).load()!!.accessToken)
    }

    @Test
    fun fileIsDeletedWhenTheKeyRejectsTheBytes() = runTest {
        manager(FakeServer { refreshAnswer(2) }).save(start)
        box.failOpen = AEADBadTagException("tag")
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
    fun refreshKeepsNewTokensInMemoryWhenTheFileCannotBeWritten() = runTest {
        val sessions = manager(FakeServer { refreshAnswer(2) })
        sessions.save(start)
        box.failSeal = KeyStoreException("not ready")
        assertEquals("access-2", sessions.refresh("access-1")!!.accessToken)
        assertEquals("access-2", sessions.session.value!!.accessToken)
        // The next write saves the tokens that are in memory.
        box.failSeal = null
        sessions.update { it.copy(email = "new@proton.me") }
        val loaded = manager(FakeServer { refreshAnswer(3) }).load()!!
        assertEquals("access-2", loaded.accessToken)
        assertEquals("refresh-2", loaded.refreshToken)
    }

    @Test
    fun refreshKeepsNewTokensWhenTheCallerIsCancelledAfterTheServerAnswered() = runTest {
        val asked = CountDownLatch(1)
        val answer = CountDownLatch(1)
        val sessions = manager(FakeServer { asked.countDown(); answer.await(); refreshAnswer(2) })
        sessions.save(start)
        val job = launch(Dispatchers.Default) { sessions.refresh("access-1") }
        withContext(Dispatchers.IO) { asked.await() }
        job.cancel()
        answer.countDown()
        job.join()
        assertEquals("access-2", sessions.session.value!!.accessToken)
        assertEquals("refresh-2", manager(FakeServer { refreshAnswer(3) }).load()!!.refreshToken)
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
    fun onlyAnswersForAnInvalidRefreshTokenSignOut() = runTest {
        for (code in listOf(401, 422)) {
            val sessions = manager(FakeServer { reply(code, """{"Code":1,"Error":"x"}""") })
            sessions.save(start)
            assertNull(sessions.refresh("access-1"))
            assertNull(sessions.session.value)
            assertFalse(file.exists())
        }
    }

    @Test
    fun refreshDoesNotWaitForARateLimit() = runTest {
        val waits = ArrayList<Long>()
        val server = FakeServer { reply(429, "{}", mapOf("Retry-After" to "30")) }
        val sessions = SessionManager(file, box, AccountApi(server.client, sleep = { waits += it }))
        sessions.save(start)
        assertNull(sessions.refresh("access-1"))
        assertTrue(waits.isEmpty())
        assertEquals(1, server.requests.size)
        assertNotNull(sessions.session.value)
    }

    @Test
    fun resetDeletesTheFileAndTheKey() = runTest {
        val sessions = manager(FakeServer { refreshAnswer(2) })
        sessions.save(start)
        sessions.reset()
        assertNull(sessions.session.value)
        assertFalse(file.exists())
        assertEquals(1, box.resets)
    }

    @Test
    fun rateLimitAndServerErrorsKeepTheSession() = runTest {
        for (code in listOf(403, 404, 408, 409, 429, 500, 503)) {
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
