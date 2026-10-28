package dev.lip.keyboard

import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

/** Process-local mutation fence, not a persisted vocabulary or editor identity.
 * No IO, IPC, observer or editor dispatch may run under its admission lock.
 */
class SwipeVocabularyEpoch {
    data class Snapshot internal constructor(internal val generation: Long, internal val origin: Any)
    class Write internal constructor(private val owner: SwipeVocabularyEpoch) {
        private val finished = AtomicBoolean(false)
        fun finish() { if (finished.compareAndSet(false, true)) owner.finishWrite() }
    }
    private val lock = Any()
    private var generation = 0L
    private var writers = 0
    private var exhausted = false
    private val observers = CopyOnWriteArraySet<() -> Unit>()
    private fun advance() { if (generation == Long.MAX_VALUE) exhausted = true else generation++ }
    fun beginWrite(): Write {
        val write = Write(this)
        synchronized(lock) { check(!exhausted && writers < Int.MAX_VALUE); advance(); writers++ }
        notifyObservers()
        return write
    }
    private fun finishWrite() {
        synchronized(lock) { check(writers > 0); writers--; advance() }
        notifyObservers()
    }
    fun snapshot(): Snapshot? = synchronized(lock) { if (writers == 0 && !exhausted) Snapshot(generation, lock) else null }
    fun isCurrent(expected: Snapshot): Boolean = synchronized(lock) { current(expected) }
    private fun current(expected: Snapshot) = !exhausted && writers == 0 && expected.origin === lock && expected.generation == generation
    /** The lambda must be a CPU-only one-use ownership claim. Dispatch happens AFTER this returns. */
    fun claim(expected: Snapshot, claim: () -> Boolean): Boolean = synchronized(lock) { current(expected) && claim() }
    fun observe(observer: () -> Unit) { observers.add(observer) }
    fun unobserve(observer: () -> Unit) { observers.remove(observer) }
    private fun notifyObservers() { observers.forEach { runCatching(it) } } // Never let UI observation prevent writer finalization.
    companion object { val process = SwipeVocabularyEpoch() }
}

/** Issued only by an eligible, visible IME; revoked before lifecycle/context departure.
 * Background work checks this CPU token before private IO and discards stale results.
 */
class SwipeVocabularyAccess {
    private val active = AtomicBoolean(true)
    fun revoke() { active.set(false) }
    fun isCurrent() = active.get()
}
