package dev.lip.speech

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class BarkLoadingTicketTest {
    @Test fun cancellationBeforeNativeBindingReachesPreparationExactlyOnce() {
        val ticket = BarkLoadingTicket()
        val count = AtomicInteger()
        ticket.cancel(); ticket.cancel()
        ticket.bind { count.incrementAndGet() }
        ticket.cancel()
        assertEquals(1, count.get())
        assertFalse(ticket.commit())
        assertTrue(runCatching { ticket.bind {} }.isFailure)
    }
    @Test fun canceledLoadingCannotCommitAndLaterCancelCannotTouchCommittedEngine() {
        val canceled = BarkLoadingTicket()
        val count = AtomicInteger()
        canceled.bind { count.incrementAndGet() }
        canceled.cancel()
        assertFalse(canceled.commit())
        val finished = BarkLoadingTicket()
        finished.bind { count.incrementAndGet() }
        assertTrue(finished.commit())
        finished.cancel(); finished.cancel()
        assertEquals("Stale loading ticket cannot abort a future cached-engine capture", 1, count.get())
    }
    @Test fun failureDropsCallbackAndTicketCannotBeReused() {
        val ticket = BarkLoadingTicket()
        ticket.bind { fail("No native callback after preparation has been retired") }
        ticket.finishFailed()
        ticket.cancel()
        assertTrue(runCatching { ticket.bind {} }.isFailure)
    }
    @Test fun callbackDoesNotHoldTicketMonitor() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val ticket = BarkLoadingTicket()
        ticket.bind { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
        val canceler = Thread { ticket.cancel() }
        canceler.start()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertFalse("Commit must see cancellation without waiting on callback", ticket.commit())
        } finally { release.countDown(); canceler.join(2000) }
        assertFalse(canceler.isAlive)
    }
}
