package dev.lip.vocabulary

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Consumer eligibility/authorization only; not Android Keystore/provider/ICU evidence. */
class PortableVocabularyConsumerTest {
    @Test fun onlySupportedEnglishWordsActivateWithoutDroppingStoredKinds() {
        val accepted = listOf(PortableVocabulary.Entry("Pianoforte"), PortableVocabulary.Entry("getUser"),
            PortableVocabulary.Entry("don't", "und"))
        accepted.forEach { assertTrue(PortableVocabularyStore.activeEnglish(it)) }
        val inactive = listOf(PortableVocabulary.Entry("Ada Rivers"), PortableVocabulary.Entry("東京", "ja"),
            PortableVocabulary.Entry("中文", "zh"), PortableVocabulary.Entry("mañana", "es"),
            PortableVocabulary.Entry("a".repeat(49)), PortableVocabulary.Entry("'word"), PortableVocabulary.Entry("word'"))
        inactive.forEach { assertFalse(PortableVocabularyStore.activeEnglish(it)) }
        assertEquals("Ada Rivers", inactive.first().term) // Eligibility is not a storage mutation/filter.
    }
    @Test fun canceledOrConsumedImportCannotClaimAgain() {
        val canceled = VocabularyMergeLease(); canceled.cancel()
        assertTrue(runCatching { canceled.claim() }.isFailure)
        val accepted = VocabularyMergeLease(); accepted.claim()
        assertTrue(runCatching { accepted.claim() }.isFailure)
    }
    @Test fun concurrentDeliberateClaimAdmitsExactlyOnce() {
        val lease = VocabularyMergeLease(); val start = CountDownLatch(1); val done = CountDownLatch(8)
        val admitted = AtomicInteger()
        val workers = List(8) { Thread { start.await(); if (runCatching { lease.claim() }.isSuccess) admitted.incrementAndGet(); done.countDown() } }
        workers.forEach { it.start() }; start.countDown()
        assertTrue(done.await(3, TimeUnit.SECONDS)); workers.forEach { it.join(1000); assertFalse(it.isAlive) }
        assertEquals(1, admitted.get())
    }
}
