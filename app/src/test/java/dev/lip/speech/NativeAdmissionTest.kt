package dev.lip.speech

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class NativeAdmissionTest {
    private class Fixture : WhisperEngine.NativeCalls {
        val events = mutableListOf<String>()
        @Volatile var retained = false
        @Volatile var available = true
        var failDecode = false
        var queryFails = false
        var closeResult = true
        var closing: CountDownLatch? = null
        var finishClose: CountDownLatch? = null
        override fun open(modelPath: ByteArray) = 17L
        override fun transcribe(handle: Long, pcm: FloatArray, language: ByteArray, prompt: ByteArray): Array<WhisperEngine.Segment> {
            events.add("infer:$handle")
            if (failDecode) { retained = true; error("synthetic unknown retirement") }
            return arrayOf(WhisperEngine.Segment("safe".toByteArray(), 0, 1, 0f))
        }
        override fun admission(handle: Long): Boolean = available && !retained
        override fun retainedWork(handle: Long): Boolean { if (queryFails) error("query unavailable"); return retained }
        override fun abort(handle: Long, abort: Boolean) { events.add("abort:$abort") }
        override fun close(handle: Long) = error("unchecked close must not run")
        override fun closeChecked(handle: Long): Boolean {
            events.add("close:$handle")
            closing?.countDown()
            check(finishClose?.await(3, TimeUnit.SECONDS) != false)
            return closeResult
        }
    }

    @Test fun returningNativeErrorDoesNotGrantBorrowZeroOrAbortReset() {
        val native = Fixture().apply { failDecode = true; closeResult = false }
        val engine = WhisperEngine("fixture", native)
        val lease = engine.borrow()
        assertTrue(runCatching { lease.transcribe(floatArrayOf(.1f), "en") }.isFailure)
        val before = native.events.toList()
        assertTrue(runCatching { lease.transcribe(floatArrayOf(0f), "en", reuseIdentical = true) }.isFailure)
        assertTrue(runCatching { engine.borrow() }.isFailure)
        assertEquals("No cached/zero bypass, native call or abort reset after unknown work", before, native.events)
        engine.close()
        assertEquals("close:17", native.events.last())
        val afterClose = native.events.toList()
        engine.close()
        assertEquals("Unconfirmed close preserves its handle and never retries retirement", afterClose, native.events)
    }

    @Test fun completedCacheCannotBypassExternalRetainedOrUnavailableAdmission() {
        for (retained in listOf(false, true)) {
            val native = Fixture()
            val engine = WhisperEngine("fixture", native)
            val lease = engine.borrow()
            lease.transcribe(floatArrayOf(.1f), "en")
            native.retained = retained; native.available = false
            val before = native.events.toList()
            assertTrue(runCatching { lease.transcribe(floatArrayOf(.1f), "en", reuseIdentical = true) }.isFailure)
            assertTrue(runCatching { lease.transcribe(floatArrayOf(0f), "en") }.isFailure)
            assertEquals(before, native.events)
            engine.close()
            assertEquals("Healthy independent engine can retire even when admission is globally fenced", "close:17", native.events.last())
        }
    }

    @Test fun blockedCheckedCloseDoesNotHoldStateMonitorOrDuplicateClose() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val native = Fixture().apply { closing = entered; finishClose = release }
        val engine = WhisperEngine("fixture", native)
        val error = AtomicReference<Throwable?>()
        val closer = Thread { error.set(runCatching { engine.close() }.exceptionOrNull()) }
        closer.start()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val stateReturned = CountDownLatch(1)
            val observer = Thread {
                engine.cancel()
                assertTrue(runCatching { engine.borrow() }.isFailure)
                stateReturned.countDown()
            }
            observer.start()
            assertTrue("State lock must remain available during blocking native close", stateReturned.await(1, TimeUnit.SECONDS))
            observer.join(1000)
        } finally { release.countDown(); closer.join(2000) }
        assertFalse(closer.isAlive)
        assertNull(error.get())
        engine.close()
        assertEquals(1, native.events.count { it == "close:17" })
    }

    @Test fun uncertainAdmissionQueryFailsClosedUntilCheckedClose() {
        val native = Fixture().apply { queryFails = true }
        val engine = WhisperEngine("fixture", native)
        assertTrue(runCatching { engine.borrow() }.isFailure)
        native.queryFails = false
        val before = native.events.toList()
        assertTrue(runCatching { engine.transcribe(floatArrayOf(0f), "en") }.isFailure)
        assertEquals(before, native.events)
        engine.close()
        assertEquals("close:17", native.events.last())
    }
}
