package dev.lip.keyboard

import org.junit.Assert.*
import org.junit.Test

class StickerBrowserAdmissionTest {
    @Test fun cancelDoesNotReleaseCapacity(){
        val gate=StickerBrowserAdmission();val old=gate.begin()!!;old.cancel()
        assertFalse(old.isLive());assertTrue(gate.isActive());assertNull(gate.begin())
        assertTrue(gate.finish(old));assertFalse(gate.isActive());assertNotNull(gate.begin())
    }
    @Test fun oldCompletionCannotReleaseNewOwner(){
        val gate=StickerBrowserAdmission();val old=gate.begin()!!;assertTrue(gate.finish(old))
        val next=gate.begin()!!;assertFalse(gate.finish(old));assertTrue(gate.isActive())
        assertTrue(next.isLive());assertTrue(gate.finish(next))
    }
    @Test fun concurrentBeginAdmitsOnlyOne(){
        val gate=StickerBrowserAdmission();val results=java.util.Collections.synchronizedList(mutableListOf<StickerBrowserAdmission.Ticket>())
        val start=java.util.concurrent.CountDownLatch(1)
        val threads=(1..16).map{Thread{start.await();gate.begin()?.let(results::add)}.apply{start()}}
        start.countDown();threads.forEach{it.join(2000);assertFalse(it.isAlive)}
        assertEquals(1,results.size);assertTrue(gate.finish(results.single()))
    }
}
