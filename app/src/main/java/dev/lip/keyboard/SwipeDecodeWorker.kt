package dev.lip.keyboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.lip.AppStore
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** One running/one latest queued job; one native engine and immutable personal
 * lexicon per stable process vocabulary epoch. No native/model training occurs.
 */
class SwipeDecodeWorker(context: Context) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1), ThreadPoolExecutor.DiscardOldestPolicy())
    private val vocabulary = SwipeVocabularyEpoch.process
    @Volatile private var closed = false
    @Volatile private var serial = 0L
    private var engine: SwipeEngine? = null
    private var lexicon: PersonalSwipeLexicon? = null
    private var cachedEpoch: SwipeVocabularyEpoch.Snapshot? = null
    private var failed = false
    private var failedAccess: SwipeVocabularyAccess? = null
    private class Revoked : IllegalStateException("Swipe request no longer current")
    private fun valid(expected: SwipeVocabularyEpoch.Snapshot, access: SwipeVocabularyAccess, request: Long) =
        !closed && serial == request && access.isCurrent() && vocabulary.isCurrent(expected)
    private fun requireCurrent(expected: SwipeVocabularyEpoch.Snapshot, access: SwipeVocabularyAccess, request: Long) {
        if (!valid(expected, access, request)) throw Revoked()
    }
    private fun closeEngine() {
        // Clear references only after native close returns. A failed close never
        // authorizes another overlapping engine initialization.
        engine?.close(); engine = null; lexicon = null; cachedEpoch = null; failed = false; failedAccess = null
    }
    private fun engine(shape: SwipeGeometry, expected: SwipeVocabularyEpoch.Snapshot, access: SwipeVocabularyAccess, request: Long): SwipeEngine {
        requireCurrent(expected, access, request)
        // A failed initialization is sticky within one visible-context access,
        // not forever: an explicit keyboard reopen may retry once with a new
        // access token. A successful epoch cache survives ordinary view changes.
        if (cachedEpoch != expected || (failed && failedAccess !== access)) {
            closeEngine() // Old native dictionary is destroyed BEFORE any new union/model is opened.
            cachedEpoch = expected; failed = true; failedAccess = access
            try {
                val store = AppStore(context)
                requireCurrent(expected, access, request) // Before any private vocabulary read.
                val manual = store.dictionary()
                requireCurrent(expected, access, request)
                val portable = store.portableVocabularyEntries() // Failure is fail-closed, never stale/base-only fallback.
                requireCurrent(expected, access, request)
                val union = PersonalSwipeLexicon.build(SwipeAssets.dictionary(context).admitted(), manual, portable)
                requireCurrent(expected, access, request)
                val model = SwipeAssets.model(context)
                requireCurrent(expected, access, request) // Immediately before native engine initialization.
                val opened = SwipeEngine.open(model, shape, union.entries.map { it.word }, IntArray(union.entries.size) { union.entries[it].frequency })
                engine = opened; lexicon = union
                requireCurrent(expected, access, request)
                failed = false; failedAccess = null
            } catch (error: Revoked) { closeEngine(); throw error }
        }
        check(!failed) { "Swipe vocabulary unavailable" }
        requireCurrent(expected, access, request)
        return checkNotNull(engine)
    }
    private fun mainOnly() { check(Looper.myLooper() == Looper.getMainLooper()) { "Swipe queue is owned by the IME main thread" } }
    private fun enqueue(task: () -> Unit) { if (!closed) { executor.queue.clear(); executor.execute { if (!closed) task() } } }
    fun prepare(shape: SwipeGeometry, expected: SwipeVocabularyEpoch.Snapshot, access: SwipeVocabularyAccess) {
        mainOnly(); val request = serial
        if (!closed && executor.queue.isEmpty() && valid(expected, access, request)) executor.execute {
            if (valid(expected, access, request)) runCatching { engine(shape, expected, access, request).prepare(shape) }
        }
    }
    fun decode(shape: SwipeGeometry, points: List<SwipePoint>, expected: SwipeVocabularyEpoch.Snapshot, access: SwipeVocabularyAccess, done: (Result<List<String>>) -> Unit) {
        mainOnly(); val snapshot = points.toList(); val request = ++serial
        enqueue {
            val result = runCatching {
                val selected = engine(shape, expected, access, request)
                requireCurrent(expected, access, request) // Before native decode, including a reused cache.
                val candidates = selected.decodeForGeometry(shape, snapshot)
                requireCurrent(expected, access, request)
                candidates.map { checkNotNull(lexicon?.display(it)) }
            }
            main.post { if (!closed && serial == request) done(if (valid(expected, access, request)) result else Result.failure(IllegalStateException("Swipe request no longer current"))) }
        }
    }
    fun cancelQueued() { mainOnly(); serial++; executor.queue.clear() }
    fun vocabularyChanged() { mainOnly(); cancelQueued(); enqueue { if (cachedEpoch?.let { !vocabulary.isCurrent(it) } == true) closeEngine() } }
    fun close() {
        mainOnly(); if (closed) return
        closed = true; serial++; executor.queue.clear()
        executor.execute { closeEngine() }
        executor.shutdown()
    }
}
